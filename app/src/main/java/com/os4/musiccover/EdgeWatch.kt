// SPDX-License-Identifier: Apache-2.0
package com.os4.musiccover

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import io.github.libxposed.api.XposedInterface
import java.util.WeakHashMap

/**
 * Who touches the islands' and the discs' material, and whether it arrives as it was sent.
 *
 * The jagged rim was one cause on the phone it was fixed on (MaterialElement, #21 A2) and is
 * still reported on others (2026-09-30) that it cannot be reproduced on. What differs between
 * phones is not our code but what else runs in SystemUI - HyperLight's 统一柔光玻璃 hooks
 * View.setMiGlass and rewrites the whole 42-float array, glass shape [34] included, for any view
 * whose call comes up through the notification code, which our replay does - and the renderer.
 *
 * So every View.setMi*, setOutlineProvider and setClipToOutline on a watched view is hooked
 * twice: outermost, where the caller's arguments are, and innermost, where the arguments reach
 * the method after every other module's hook has run. A difference between the two is a rewrite
 * by a hook in between, and names the indices; a call that never reaches the inside was
 * swallowed. The caller is taken from the stack for the first few calls of each kind, so a
 * foreign module or an OEM path setting our views shows by name. Read through `op edge`, and
 * the first of each is logged (MCEdge) for a tester's LSPosed log.
 */
internal object EdgeWatch {
    private const val TAG = "MCEdge: "

    private const val CALLERS_PER_KEY = 6
    private const val REWRITES_LOGGED = 4

    private val watched = WeakHashMap<View, String>()

    /** The glass SDF size each watched view last really got, after every hook. */
    private val sdf = WeakHashMap<View, String>()

    /** For EdgeProbe: [v]'s glass SDF size as last set, to hold against its own size. */
    fun sdfOf(v: View): String? = synchronized(watched) { sdf[v] }
    private val stats = LinkedHashMap<String, Stat>()
    private val events = ArrayDeque<String>()
    @Volatile private var installed = false

    /** Our own replay and dim, so their calls are told from anyone else's without a stack. */
    private var oursDepth = 0

    private class Frame(val role: String, val args: Array<Any?>) {
        var reached = false
    }
    private val frames = ThreadLocal<ArrayDeque<Frame>>()

    private class Stat {
        var calls = 0
        var ours = 0
        var rewritten = 0
        var swallowed = 0
        var sampled = 0
        val callers = LinkedHashMap<String, Int>()
        var lastDiff = ""
    }

    fun watch(view: View, role: String) {
        synchronized(watched) { watched[view] = role }
        install()
        ModuleScan.start(view.context)
    }

    /** [block] runs as ours: its calls are counted but not attributed. */
    inline fun <T> ours(block: () -> T): T {
        enterOurs()
        try {
            return block()
        } finally {
            exitOurs()
        }
    }

    fun enterOurs() { oursDepth++ }
    fun exitOurs() { oursDepth-- }

    private fun roleOf(v: Any?): String? =
        if (v is View) synchronized(watched) { watched[v] } else null

    private fun install() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            installed = true
        }
        val targets = View::class.java.declaredMethods.filter {
            it.parameterCount > 0 && (it.name.startsWith("setMi") || it.name == "setOutlineProvider" ||
                it.name == "setClipToOutline")
        }
        var hooked = 0
        for (m in targets) {
            runCatching {
                m.isAccessible = true
                Xp.api().hook(m).setPriority(XposedInterface.PRIORITY_HIGHEST).intercept { chain -> outer(chain) }
                Xp.api().hook(m).setPriority(XposedInterface.PRIORITY_LOWEST).intercept { chain -> inner(chain) }
                hooked++
            }.onFailure { Xp.log(TAG + "cannot watch ${m.name}: $it") }
        }
        Xp.log(TAG + "watching $hooked View setters")
    }

    private fun outer(chain: XposedInterface.Chain): Any? {
        val role = roleOf(chain.thisObject) ?: return chain.proceed()
        val name = chain.executable.name
        val key = "$role.$name"
        val args = chain.args.toTypedArray()
        val frame = Frame(role, Array(args.size) { copy(args[it]) })
        val stack = frames.get() ?: ArrayDeque<Frame>().also(frames::set)
        stack.addLast(frame)
        try {
            return chain.proceed()
        } finally {
            stack.removeLast()
            var said: String? = null
            synchronized(stats) {
                val stat = stats.getOrPut(key) { Stat() }
                stat.calls++
                if (oursDepth > 0) stat.ours++
                if (!frame.reached && ++stat.swallowed == 1) said = "swallowed before View got it, by ${caller(name)}"
                if (oursDepth == 0 && stat.sampled < CALLERS_PER_KEY) {
                    stat.sampled++
                    val who = caller(name)
                    val seen = stat.callers[who]
                    stat.callers[who] = (seen ?: 0) + 1
                    if (seen == null) said = (said?.let { "$it; " } ?: "") + "set by $who ${brief(args)}"
                }
            }
            said?.let { note(key, it) }
        }
    }

    private fun inner(chain: XposedInterface.Chain): Any? {
        val frame = frames.get()?.lastOrNull()
        if (frame == null || roleOf(chain.thisObject) == null) return chain.proceed()
        frame.reached = true
        val now = chain.args.toTypedArray()
        if (chain.executable.name == "setMiGlassSdfMaxSize" && now.size == 2) {
            val v = chain.thisObject as View
            synchronized(watched) { sdf[v] = "${short(now[0])}x${short(now[1])}" }
        }
        val diff = diff(frame.args, now)
        if (diff.isNotEmpty()) {
            val key = "${frame.role}.${chain.executable.name}"
            val count = synchronized(stats) {
                val stat = stats.getOrPut(key) { Stat() }
                stat.lastDiff = diff
                ++stat.rewritten
            }
            if (count <= REWRITES_LOGGED) {
                note(key, "rewritten by a hook between the caller and View: $diff" +
                    if (oursDepth > 0) " (our replay)" else " (caller ${caller(chain.executable.name)})")
            }
        }
        return chain.proceed()
    }

    private fun note(key: String, what: String) {
        val line = "${android.os.SystemClock.uptimeMillis() % 1000000} $key $what"
        synchronized(events) {
            events.addLast(line)
            while (events.size > 40) events.removeFirst()
        }
        Xp.log(TAG + line)
    }

    private fun copy(v: Any?): Any? = when (v) {
        is FloatArray -> v.copyOf()
        is IntArray -> v.copyOf()
        else -> v
    }

    /** Which arguments differ, and for an array which indices: `a0[34] 1.00->3.00`. */
    private fun diff(before: Array<Any?>, after: Array<Any?>): String {
        val sb = StringBuilder()
        for (i in 0 until minOf(before.size, after.size)) {
            val a = before[i]
            val b = after[i]
            when {
                a is FloatArray && b is FloatArray -> {
                    if (a.size != b.size) sb.append(" a$i size ${a.size}->${b.size}")
                    for (j in 0 until minOf(a.size, b.size)) {
                        if (kotlin.math.abs(a[j] - b[j]) > 1e-4f)
                            sb.append(" a$i[$j] ${"%.3f".format(a[j])}->${"%.3f".format(b[j])}")
                    }
                }
                a is IntArray && b is IntArray -> {
                    for (j in 0 until minOf(a.size, b.size)) {
                        if (a[j] != b[j]) sb.append(" a$i[$j] ${Integer.toHexString(a[j])}->${Integer.toHexString(b[j])}")
                    }
                }
                a !== b && a != b -> sb.append(" a$i ${short(a)}->${short(b)}")
            }
        }
        return sb.toString().trim()
    }

    private fun brief(args: Array<Any?>): String = args.joinToString(",", "(", ")") { short(it) }

    private fun short(v: Any?): String = when (v) {
        null -> "null"
        is FloatArray -> "float[${v.size}]"
        is IntArray -> "int[${v.size}]"
        is Float -> "%.2f".format(v)
        is Number, is Boolean -> v.toString()
        is View -> v.javaClass.simpleName
        else -> v.javaClass.simpleName
    }

    /**
     * Where the call came from: the frames past the setter itself. LSPosed's own plumbing sits
     * between this hook and the setter under names it randomises (a hooker class, obfuscated
     * chain classes), so the stack is read from the first frame named as the setter - LSPosed's
     * stub for it, then any override of it such as MaterialElement's - and the callers begin
     * after that run. Reflection is skipped: MiGlassCompat and MiBlurCompat are reflection
     * shells, and it is their caller that says who it was. A module's frames keep its package;
     * ours are obfuscated in a release and read through mapping.txt.
     */
    private fun caller(method: String): String {
        val frames = Thread.currentThread().stackTrace
        var i = frames.indexOfFirst { it.methodName == method }
        if (i < 0) return "?"
        while (i < frames.size && frames[i].methodName == method) i++
        val out = ArrayList<String>(3)
        while (i < frames.size && out.size < 3) {
            val f = frames[i++]
            val c = f.className
            if (c.startsWith("java.") || c.startsWith("dalvik.") || c.startsWith("jdk.") ||
                c.startsWith("kotlin.")) continue
            out.add("${c.substringAfterLast('.')}.${f.methodName}")
        }
        return if (out.isEmpty()) "?" else out.joinToString("<")
    }

    /** For `op edge`: counts per view role and setter, and the last notable events. */
    fun describe(): String {
        val sb = StringBuilder("watch: ")
        if (!installed) return sb.append("not installed").toString()
        synchronized(stats) {
            for ((key, s) in stats) {
                sb.append("\n  ").append(key).append(" calls=").append(s.calls).append(" ours=").append(s.ours)
                if (s.rewritten > 0) sb.append(" REWRITTEN=").append(s.rewritten).append(" last:").append(s.lastDiff)
                if (s.swallowed > 0) sb.append(" SWALLOWED=").append(s.swallowed)
                if (s.callers.isNotEmpty()) sb.append(" by ").append(s.callers.entries.joinToString(" | ") { "${it.key}x${it.value}" })
            }
        }
        synchronized(events) {
            if (events.isNotEmpty()) sb.append("\nevents:").append(events.joinToString("") { "\n  $it" })
        }
        return sb.toString()
    }

    /** The phone, its OS and its renderers: what the reports that see jaggies may share. */
    fun device(ctx: Context): String {
        val dm = ctx.resources.displayMetrics
        fun prop(name: String): String = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                .invoke(null, name) as String
        }.getOrDefault("").ifEmpty { "-" }
        return "device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) sdk=${Build.VERSION.SDK_INT}" +
            " os=${prop("ro.mi.os.version.name")}/${prop("ro.mi.os.version.incremental")}" +
            " build=${Build.VERSION.INCREMENTAL}" +
            "\n  screen=${dm.widthPixels}x${dm.heightPixels} density=${dm.density} dpi=${dm.densityDpi}" +
            " soc=${prop("ro.soc.model")}/${prop("ro.board.platform")} egl=${prop("ro.hardware.egl")}" +
            "\n  hwui=${prop("debug.hwui.renderer")} vulkan=${prop("ro.hwui.use_vulkan")}" +
            " re=${prop("debug.renderengine.backend")} sfblur=${prop("ro.surface_flinger.supports_background_blur")}" +
            " blur=${prop("persist.sys.background_blur_supported")}/${prop("persist.sys.background_blur_status_default")}" +
            " advfx=${prop("persist.sys.advanced_visual_release")}" +
            "\nmodules: " + ModuleScan.result()
    }

    /**
     * The Xposed modules installed on the phone, by package: whichever of them are enabled for
     * SystemUI is not something this side can see, but a module that is not installed is not
     * the cause. Legacy modules declare `xposedmodule` in their manifest; libxposed ones carry
     * META-INF/xposed. Scanned once, off the main thread.
     */
    private object ModuleScan {
        @Volatile private var found: String? = null
        @Volatile private var started = false

        fun start(ctx: Context) {
            if (started) return
            started = true
            val app = ctx.applicationContext ?: ctx
            Thread({ found = runCatching { scan(app) }.getOrElse { "scan failed: $it" } }, "MCEdgeScan").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
                start()
            }
        }

        fun result(): String = found ?: if (started) "scanning" else "not scanned"

        private fun scan(ctx: Context): String {
            val pm = ctx.packageManager
            val hits = ArrayList<String>()
            @Suppress("DEPRECATION")
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (info in apps) {
                if (info.flags and ApplicationInfo.FLAG_SYSTEM != 0 && info.packageName != "com.android.systemui") continue
                val legacy = info.metaData?.containsKey("xposedmodule") == true
                val modern = !legacy && runCatching {
                    java.util.zip.ZipFile(info.sourceDir).use { zip ->
                        zip.getEntry("META-INF/xposed/java_init.list") != null ||
                            zip.getEntry("META-INF/xposed/module.prop") != null
                    }
                }.getOrDefault(false)
                if (!legacy && !modern) continue
                val version = runCatching {
                    @Suppress("DEPRECATION") pm.getPackageInfo(info.packageName, 0).versionName
                }.getOrNull()
                val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault("")
                hits.add("${info.packageName}($label ${version ?: "?"})")
            }
            return if (hits.isEmpty()) "none found" else hits.sorted().joinToString(", ")
        }
    }
}
