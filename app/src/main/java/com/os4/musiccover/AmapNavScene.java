package com.os4.musiccover;

import android.content.Context;
import android.content.Intent;

/**
 * 高德's walking and cycling navigation map, the first immersive page.
 *
 * 高德 17.00.0.2005 on this phone is byte-identical to the ColorOS build apart from its channel
 * id, service included, and the service's side of the protocol has no vendor test in it. What it
 * needs before it can draw is 高德's own navigation page calling
 * NativesModuleImmerseNavi.init() - it does here too (2026-09-29: walking navigation, init with
 * sceneType 4 / pageType 3, the page back in ~190ms). AmapImmerse, in 高德's process, watches that
 * call and the page's destroy and arms and disarms this scene over the SystemUI probe
 * ({@code op immersive --es id amap-nav --es do arm|disarm}).
 *
 * Opened from 高德's focus island - protocol 1, notification id 1236, re-posted every second
 * during the navigation (see the amap-nav-island-focus-notification notes).
 */
final class AmapNavScene extends LiveAlertScene {

    static final String ID = "amap-nav";
    static final String PKG = "com.autonavi.minimap";
    /** AmapImmerse's receiver in 高德's main process. */
    static final String AMAP_PROBE = "com.os4.musiccover.AMAPPROBE";

    static final AmapNavScene INSTANCE = new AmapNavScene();

    private AmapNavScene() {
        super(ID, PKG, "com.autonavi.minimap.immersenavi.AMapImmerseNaviService", "536879184");
    }

    /**
     * Asks 高德 whether a navigation page is up, for a SystemUI that started in the middle of one:
     * the init that would have armed this happened before this process existed. AmapImmerse
     * answers with the same arm it sends on init. No answer is the answer - 高德 is not running,
     * or has no page up.
     */
    void ask(Context ctx) {
        try {
            ctx.sendBroadcast(new Intent(AMAP_PROBE).setPackage(PKG).putExtra("ask", true));
        } catch (Throwable t) {
            Xp.log("MCImmersive: " + ID + ": ask failed: " + t);
        }
    }
}
