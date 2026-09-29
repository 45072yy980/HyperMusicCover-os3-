package com.os4.musiccover;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.SystemClock;
import android.os.UserHandle;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * The lock screen as a host for 高德's navigation map, the way ColorOS 16 does it.
 *
 * On ColorOS the map is not drawn by the system at all. 高德 has a service,
 * {@code com.autonavi.minimap/.immersenavi.AMapImmerseNaviService}, whose onBind hands back a
 * Messenger; the host sends it a window token and a size, 高德 builds a SurfaceControlViewHost in
 * its own process, renders its map into it, and sends the SurfacePackage back. The host puts that
 * package into a SurfaceView in the lock screen window and the map is simply there, live, under
 * the clock. The same APK is on this phone - all eight dex files are byte-identical to the ColorOS
 * phone's - and the service's side of the protocol has no vendor test in it, so this class plays
 * the ColorOS plugin's part (SystemUIPlugin's IntentMessenger, class z5.h) from inside SystemUI.
 *
 * The protocol, as the plugin speaks it:
 *   11  host -> app  data{hostToken: SurfaceView.getHostToken(), extra{
 *                         livealert.immersive.display{width, height, orientation, infoBounds},
 *                         livealert.immersive.card{cardKey}}}
 *   12  host -> app  the same without the token, plus event=1: the size changed
 *   13  host -> app  data{state}: 1 shown / 2 hidden / 3, 4 starting to show / hide
 *   14  host -> app  data{hostToken}: let go
 *   21  app -> host  data{SurfacePackage} - 高德 sends it only once its map has rendered
 *
 * The token is SurfaceView.getHostToken() and nothing else: it is an IBinder (the window's input
 * token), which is what the plugin puts in the bundle, and what 高德 reads back with getBinder.
 *
 * Where the surface sits: a SurfaceView left at its default z-order is composited *below* its
 * window, so the lock screen's own views stay on top and the map shows through wherever the
 * keyguard is transparent. That is exactly ColorOS's arrangement - its SurfaceView sits at
 * relative z -2 under NotificationShade.
 *
 * The one thing this cannot supply is 高德's side being ready: the service only draws once 高德's
 * own navigation page has called NativesModuleImmerseNavi.init(), and on this phone that may never
 * happen (see AmapImmerse, which watches for it). A bind that never gets a 21 back is that case.
 *
 * Driven by hand for now, through the SystemUI probe: {@code op navmap --es do start|stop|state}.
 */
final class NavImmerse {

    private NavImmerse() {
    }

    private static final String TAG = "MCNavMap: ";

    static final String PKG = "com.autonavi.minimap";
    private static final String SERVICE = "com.autonavi.minimap.immersenavi.AMapImmerseNaviService";

    /** 高德's card id on ColorOS; the service does not read it, but the plugin always sends one. */
    private static final String CARD_ID = "536879184";

    private static final int MSG_BIND = 11;
    private static final int MSG_RESIZE = 12;
    private static final int MSG_STATE = 13;
    private static final int MSG_UNBIND = 14;
    private static final int MSG_SURFACE = 21;

    private static final Handler sMain = new Handler(Looper.getMainLooper());

    private static Context sCtx;
    private static SurfaceView sSurface;
    private static Messenger sServer;
    private static ServiceConnection sConn;
    private static String sCardKey;
    private static boolean sSurfaceReady;
    private static boolean sBindSent;
    private static long sStartedAt;
    private static int sReplies;

    /** Where 高德's replies land. On the main thread, like the plugin's MsgReceiver. */
    private static final Messenger sReplyTo = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            sReplies++;
            Bundle data = msg.getData();
            Xp.log(TAG + "reply what=" + msg.what + " after "
                    + (SystemClock.uptimeMillis() - sStartedAt) + "ms data=" + data);
            if (msg.what != MSG_SURFACE) return;
            SurfaceControlViewHost.SurfacePackage pkg = null;
            try {
                pkg = data.getParcelable("SurfacePackage",
                        SurfaceControlViewHost.SurfacePackage.class);
            } catch (Throwable t) {
                Xp.log(TAG + "21 unreadable: " + t);
            }
            if (pkg == null || sSurface == null) {
                Xp.log(TAG + "21 without a package or after stop (surface=" + sSurface + ")");
                return;
            }
            sSurface.setChildSurfacePackage(pkg);
            send(MSG_STATE, stateData(1));
            Xp.log(TAG + "map attached");
        }
    });

    /** Put the surface in the lock screen window and ask 高德 for its map. */
    static void start(View anyViewInShade) {
        if (sSurface != null) {
            Xp.log(TAG + "start: already running");
            return;
        }
        View root = anyViewInShade.getRootView();
        if (!(root instanceof ViewGroup)) {
            Xp.log(TAG + "start: no window root (" + root + ")");
            return;
        }
        sCtx = root.getContext().getApplicationContext();
        sStartedAt = SystemClock.uptimeMillis();
        sReplies = 0;
        sBindSent = false;
        sSurfaceReady = false;
        sCardKey = CARD_ID + "||0|" + System.currentTimeMillis();

        SurfaceView sv = new SurfaceView(root.getContext());
        sv.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                sSurfaceReady = true;
                Xp.log(TAG + "surface created " + sv.getWidth() + "x" + sv.getHeight());
                maybeSendBind();
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
                if (sBindSent) send(MSG_RESIZE, resizeData());
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                sSurfaceReady = false;
            }
        });
        // Index 0: under every view the keyguard draws, though as a SurfaceView at its default
        // z-order the surface itself is composited below the whole window anyway.
        ((ViewGroup) root).addView(sv, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sSurface = sv;

        sConn = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                sServer = new Messenger(service);
                Xp.log(TAG + "connected " + name);
                maybeSendBind();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                sServer = null;
                Xp.log(TAG + "disconnected " + name);
            }
        };
        Intent intent = new Intent().setComponent(new ComponentName(PKG, SERVICE));
        boolean ok = bind(sCtx, intent, sConn);
        Xp.log(TAG + "bind " + (ok ? "requested" : "REFUSED"));
    }

    static void stop() {
        if (sSurface == null) return;
        IBinder token = sSurface.getHostToken();
        if (sServer != null && token != null) {
            Bundle data = new Bundle();
            data.putBinder("hostToken", token);
            data.putBundle("extra", cardExtra());
            send(MSG_UNBIND, data);
        }
        try {
            if (sConn != null) sCtx.unbindService(sConn);
        } catch (Throwable t) {
            Xp.log(TAG + "unbind: " + t);
        }
        sConn = null;
        sServer = null;
        try {
            sSurface.clearChildSurfacePackage();
        } catch (Throwable ignored) {
        }
        ViewGroup parent = (ViewGroup) sSurface.getParent();
        if (parent != null) parent.removeView(sSurface);
        sSurface = null;
        Xp.log(TAG + "stopped");
    }

    static String state() {
        StringBuilder sb = new StringBuilder();
        sb.append("running=").append(sSurface != null)
                .append(" surfaceReady=").append(sSurfaceReady)
                .append(" connected=").append(sServer != null)
                .append(" bindSent=").append(sBindSent)
                .append(" replies=").append(sReplies);
        if (sSurface != null) {
            sb.append(" size=").append(sSurface.getWidth()).append('x').append(sSurface.getHeight())
                    .append(" attached=").append(sSurface.isAttachedToWindow());
        }
        sb.append('\n').append(Xp.tail(TAG, 40));
        return sb.toString();
    }

    // ---------------------------------------------------------------- protocol

    private static void maybeSendBind() {
        if (sBindSent || sServer == null || !sSurfaceReady || sSurface == null) return;
        IBinder token = sSurface.getHostToken();
        if (token == null) {
            Xp.log(TAG + "no host token yet");
            return;
        }
        Bundle data = new Bundle();
        data.putBinder("hostToken", token);
        data.putBundle("extra", displayExtra());
        sBindSent = send(MSG_BIND, data);
        Xp.log(TAG + "11 sent=" + sBindSent + " " + sSurface.getWidth() + "x" + sSurface.getHeight());
    }

    private static Bundle resizeData() {
        Bundle data = new Bundle();
        data.putInt("event", 1);
        data.putBundle("extra", displayExtra());
        return data;
    }

    private static Bundle stateData(int state) {
        Bundle data = new Bundle();
        data.putInt("state", state);
        data.putBundle("extra", cardExtra());
        return data;
    }

    private static Bundle cardExtra() {
        Bundle card = new Bundle();
        card.putString("cardKey", sCardKey);
        Bundle extra = new Bundle();
        extra.putBundle("livealert.immersive.card", card);
        return extra;
    }

    /**
     * What the plugin's getDisplayBundle builds: the SurfaceView's measured size, the orientation,
     * and infoBounds - the band 高德 keeps its route information inside. ColorOS measured
     * Rect(56, 500, 1024, 1518) on a 1080x2160 surface; the same proportions here.
     */
    private static Bundle displayExtra() {
        int w = sSurface.getWidth();
        int h = sSurface.getHeight();
        Bundle display = new Bundle();
        display.putInt("width", w);
        display.putInt("height", h);
        display.putInt("orientation", sSurface.getResources().getConfiguration().orientation);
        int side = Math.round(w * 0.052f);
        display.putParcelable("infoBounds",
                new Rect(side, Math.round(h * 0.231f), w - side, Math.round(h * 0.703f)));
        Bundle extra = cardExtra();
        extra.putBundle("livealert.immersive.display", display);
        return extra;
    }

    private static boolean send(int what, Bundle data) {
        Messenger server = sServer;
        if (server == null) return false;
        Message m = Message.obtain(null, what);
        m.setData(data);
        m.replyTo = sReplyTo;
        try {
            server.send(m);
            return true;
        } catch (Throwable t) {
            Xp.log(TAG + "send " + what + " failed: " + t);
            return false;
        }
    }

    /**
     * SystemUI runs as the system uid, and a plain bindService from there is the "without a
     * qualified user" case; the plugin binds as the current user, so do the same when we can.
     */
    private static boolean bind(Context ctx, Intent intent, ServiceConnection conn) {
        try {
            return (boolean) Context.class.getMethod("bindServiceAsUser", Intent.class,
                            ServiceConnection.class, int.class, UserHandle.class)
                    .invoke(ctx, intent, conn, Context.BIND_AUTO_CREATE, android.os.Process.myUserHandle());
        } catch (Throwable t) {
            Xp.log(TAG + "bindServiceAsUser unavailable (" + t + "), plain bind");
            return ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE);
        }
    }

    /** Called from the probe op on any thread. */
    static void post(Runnable r) {
        sMain.post(r);
    }
}
