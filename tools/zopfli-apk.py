#!/usr/bin/env python3
"""
Re-deflate a signed release APK with Zopfli, then align and sign it again, in place.

    python3 tools/zopfli-apk.py app/build/outputs/apk/release/app-release.apk

Zopfli writes ordinary deflate streams, only smaller - about 4% on this app's dex, which is most of
the APK - at the cost of some seconds of CPU. Nothing reading the APK can tell the difference.

Every entry keeps its name, order, timestamp and storage method; stored entries (resources.arsc, the
native library) are copied byte for byte and zipalign puts their alignment back. The old signature
is in the APK Signing Block, which this rewrite drops, so the result is signed again with the same
key the build used:

- SIGNING_STORE_FILE / SIGNING_STORE_PASSWORD / SIGNING_KEY_ALIAS / SIGNING_KEY_PASSWORD, as CI sets
  them for Gradle;
- otherwise keystore.properties next to this directory, as a local release build reads it;
- otherwise the debug keystore, which is what Gradle falls back to as well.

Needs `pip install zopfli`, and zipalign and apksigner from the Android SDK's build-tools (the newest
one under $ANDROID_HOME or $ANDROID_SDK_ROOT, or local.properties' sdk.dir).
"""
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import zipfile
import zlib

import zopfli.zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ITERATIONS = 15


def raw_deflate(data):
    # zlib container minus its 2-byte header and 4-byte Adler-32 trailer
    return zopfli.zlib.compress(data, numiterations=ITERATIONS)[2:-4]


def dos_time(date_time):
    y, mo, d, h, mi, s = date_time
    return (h << 11) | (mi << 5) | (s // 2), ((y - 1980) << 9) | (mo << 5) | d


def repack(src, dst):
    """Writes [src]'s entries to [dst], each deflated one re-deflated with Zopfli if that is smaller."""
    before = after = 0
    with zipfile.ZipFile(src) as zin, open(src, "rb") as raw, open(dst, "wb") as out:
        central = []
        for info in zin.infolist():
            data = zin.read(info)
            # The entry's compressed bytes as they sit in the file, to keep when Zopfli does not win.
            raw.seek(info.header_offset)
            header = raw.read(30)
            name_len, extra_len = struct.unpack("<HH", header[26:30])
            raw.seek(info.header_offset + 30 + name_len + extra_len)
            packed = raw.read(info.compress_size)

            if info.compress_type == zipfile.ZIP_DEFLATED:
                candidate = raw_deflate(data)
                if len(candidate) < len(packed):
                    packed = candidate
                if zlib.decompress(packed, -15) != data:
                    sys.exit("zopfli-apk: %s did not round-trip" % info.filename)
            elif info.compress_type != zipfile.ZIP_STORED:
                sys.exit("zopfli-apk: %s uses compression method %d" % (info.filename, info.compress_type))
            before += info.compress_size
            after += len(packed)

            name = info.filename.encode("utf-8")
            flags = info.flag_bits & 0x0800  # keep only the UTF-8 name flag; no data descriptors
            t, d = dos_time(info.date_time)
            crc = zlib.crc32(data) & 0xFFFFFFFF
            offset = out.tell()
            out.write(struct.pack("<IHHHHHIIIHH", 0x04034B50, 20, flags, info.compress_type, t, d,
                                  crc, len(packed), len(data), len(name), 0))
            out.write(name)
            out.write(packed)
            central.append(struct.pack("<IHHHHHHIIIHHHHHII", 0x02014B50, 20, 20, flags,
                                       info.compress_type, t, d, crc, len(packed), len(data),
                                       len(name), 0, 0, 0, 0, info.external_attr, offset) + name)

        cd_offset = out.tell()
        for entry in central:
            out.write(entry)
        cd_size = out.tell() - cd_offset
        out.write(struct.pack("<IHHHHIIH", 0x06054B50, 0, 0, len(central), len(central),
                              cd_size, cd_offset, 0))
    return before, after


def build_tools():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    local = os.path.join(ROOT, "local.properties")
    if not sdk and os.path.exists(local):
        for line in open(local, encoding="utf-8"):
            if line.startswith("sdk.dir="):
                sdk = line.split("=", 1)[1].strip().replace("\\:", ":").replace("\\\\", "/")
    if not sdk:
        sys.exit("zopfli-apk: no Android SDK (set ANDROID_HOME)")
    base = os.path.join(sdk, "build-tools")

    def version(name):
        return [int(p) if p.isdigit() else 0 for p in name.replace("-", ".").split(".")]

    newest = max(os.listdir(base), key=version)
    exe = ".bat" if os.name == "nt" else ""
    zipalign = os.path.join(base, newest, "zipalign" + (".exe" if os.name == "nt" else ""))
    apksigner = os.path.join(base, newest, "apksigner" + exe)
    return zipalign, apksigner


def signing():
    """(keystore, store password, alias, key password) for the key this build is signed with."""
    env = [os.environ.get(k) for k in ("SIGNING_STORE_FILE", "SIGNING_STORE_PASSWORD",
                                       "SIGNING_KEY_ALIAS", "SIGNING_KEY_PASSWORD")]
    if all(env):
        return [os.path.join(ROOT, env[0])] + env[1:]
    props = os.path.join(ROOT, "keystore.properties")
    if os.path.exists(props):
        p = dict(line.strip().split("=", 1) for line in open(props, encoding="utf-8")
                 if "=" in line and not line.lstrip().startswith("#"))
        return [os.path.join(ROOT, p["storeFile"]), p["storePassword"], p["keyAlias"], p["keyPassword"]]
    return [os.path.join(os.path.expanduser("~"), ".android", "debug.keystore"),
            "android", "androiddebugkey", "android"]


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    apk = sys.argv[1]
    zipalign, apksigner = build_tools()
    store, store_pass, alias, key_pass = signing()
    tmp = tempfile.mkdtemp()
    try:
        repacked = os.path.join(tmp, "repacked.apk")
        aligned = os.path.join(tmp, "aligned.apk")
        before, after = repack(apk, repacked)
        # -P 16: native libraries on 16KB page boundaries, as AGP lays them out.
        subprocess.run([zipalign, "-f", "-P", "16", "4", repacked, aligned], check=True)
        env = dict(os.environ, ZOPFLI_KS_PASS=store_pass, ZOPFLI_KEY_PASS=key_pass)
        # v2 only, as Gradle signs it for this minSdk; apksigner on its own would pick v3.
        subprocess.run([apksigner, "sign", "--ks", store, "--ks-key-alias", alias,
                        "--ks-pass", "env:ZOPFLI_KS_PASS", "--key-pass", "env:ZOPFLI_KEY_PASS",
                        "--v1-signing-enabled", "false", "--v2-signing-enabled", "true",
                        "--v3-signing-enabled", "false", aligned],
                       check=True, env=env, shell=os.name == "nt")
        subprocess.run([apksigner, "verify", aligned], check=True, shell=os.name == "nt")
        size_before = os.path.getsize(apk)
        shutil.copyfile(aligned, apk)
        print("zopfli-apk: entries %d -> %d bytes, APK %d -> %d bytes"
              % (before, after, size_before, os.path.getsize(apk)))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


if __name__ == "__main__":
    main()
