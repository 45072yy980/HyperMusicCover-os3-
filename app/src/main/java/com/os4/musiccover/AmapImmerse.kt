package com.os4.musiccover

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 高德's half of the lock screen map: whether its navigation page is ready to draw one.
 *
 * NavImmerse, in SystemUI, binds 高德's AMapImmerseNaviService and asks for the map. The service
 * can only answer once 高德's own AJX navigation page has called `NativesModuleImmerseNavi.init`
 * with its page config - the service builds its map view out of that config and that page's
 * AJX context, and with neither it logs "initMapView: empty initConfig" and draws nothing. On
 * ColorOS the page makes that call during walking and cycling navigation. The Java side of 高德
 * never reads any ColorOS state to decide it, so the decision is in the page's script, and the
 * script is identical on both phones; what can differ is what the script is told about the phone
 * (高德 carries isOppo / isOppoDevice / ro.build.version.opporom checks).
 *
 * So this only watches, and says what it saw through its own probe:
 *   module  - how many NativesModuleImmerseNavi instances the pages built (the module being
 *             created at all means a page asked for it)
 *   init    - how many times a page called init(), and the config it passed
 *   destroy - the page letting go
 *
 * `adb shell am broadcast -a com.os4.musiccover.AMAPPROBE` answers in the main process only.
 * The class names here are 高德's own and unobfuscated: the AJX bridge finds modules by name, so
 * they cannot be minified away.
 */
internal object AmapImmerse {

    @JvmField val PKG = NavImmerse.PKG

    private const val TAG = "MCAmap: "
    private const val ACTION = "com.os4.musiccover.AMAPPROBE"
    private const val MODULE = "com.autonavi.minimap.immersenavi.module.NativesModuleImmerseNavi"

    private val registered = AtomicBoolean(false)
    private val modules = AtomicInteger()
    private val inits = AtomicInteger()
    @Volatile private var lastConfig: String? = null
    @Volatile private var lastInitAt = 0L

    @JvmStatic
    fun handle(cl: ClassLoader) {
        try {
            val instr = Xp.findClass("android.app.Instrumentation", cl)
            Xp.hookAll(instr, "callApplicationOnCreate") { chain ->
                val out = chain.proceed()
                try {
                    val app = chain.args[0] as Application
                    if (Application.getProcessName() == PKG) register(app)
                } catch (t: Throwable) {
                    Xp.log(TAG + "probe not registered: " + t)
                }
                out
            }
        } catch (t: Throwable) {
            Xp.log(TAG + "application hook failed: " + t)
        }
        try {
            val module = Xp.findClass(MODULE, cl)
            Xp.hookAllConstructors(module) { chain ->
                val out = chain.proceed()
                Xp.log(TAG + "module built #" + modules.incrementAndGet())
                out
            }
            Xp.hookAll(module, "init") { chain ->
                lastConfig = chain.args.getOrNull(0) as String?
                lastInitAt = SystemClock.uptimeMillis()
                Xp.log(TAG + "init #" + inits.incrementAndGet() + " config=" + lastConfig)
                chain.proceed()
            }
            Xp.hookAll(module, "destroy") { chain ->
                Xp.log(TAG + "destroy")
                chain.proceed()
            }
        } catch (t: Throwable) {
            Xp.log(TAG + "immerse module hooks failed: " + t)
        }
    }

    private fun register(ctx: Context) {
        if (!registered.compareAndSet(false, true)) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val sb = StringBuilder()
                sb.append("modules=").append(modules.get())
                    .append(" inits=").append(inits.get())
                if (lastInitAt != 0L) {
                    sb.append(" lastInit=").append(SystemClock.uptimeMillis() - lastInitAt)
                        .append("ms ago")
                }
                sb.append("\nconfig=").append(lastConfig)
                sb.append('\n').append(Xp.tail(TAG, 40))
                resultData = sb.toString()
            }
        }
        ctx.registerReceiver(r, IntentFilter(ACTION), Context.RECEIVER_EXPORTED)
        Xp.log(TAG + "probe registered")
    }
}
