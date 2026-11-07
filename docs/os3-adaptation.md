# HyperOS 3 adaptation (16.03.251211.r)

What was checked against the device's own packages, what was changed here, and what this
build cannot do. The packages are the source of the symbol lists below: SystemUI
(`系统界面_16.03.251211.r`), its plugin (`系统界面组件_17.1.4.26.0`), the wallpaper app
(`壁纸_5.6.3.1`), the AOD/keyguard editor (`息屏与锁屏编辑_DEV-2326`), and the assistant
(`智能助理_25.30.50`). Test device: Xiaomi 24129RT7CC (rodin), HyperOS 3.0.304.0, Android 16.

## Still there, and used as before

| Target | Note |
| --- | --- |
| `com.miui.clock.utils.DeviceConfig` | all five advanced-material fields |
| `com.android.keyguard.injector.DozeServiceHostInjector.mLockscreenIsDefaultTheme` | keyguard soft glass |
| `com.miui.utils.MiuiThemeUtils.sDefaultSysUiTheme` | theme flag |
| plugin `miui.systemui.util.ThemeUtils` | `defaultPluginTheme`, `updateDefaultPluginTheme` |
| plugin `miui.systemui.util.MiBlurCompat` | `getBackgroundBlurOpenedInDefaultTheme`, `getBlurCompat` |
| SystemUI `com.miui.systemui.util.MiBlurCompat` | `getBackgroundBlurOpened`, the blur setters |
| `com.miui.miwallpaper.*` in the wallpaper app | renderers, `FastPlayerImpl`, `VideoDepthManager`, `UniversalWallpaper$UniversalEngine` |
| `com.miui.keyguard.editor.*` in the AOD editor | `HierarchyImageAvoidController`, `CrossListDataModel`, `TemplateHistoryDao_Impl` |

## Gone from every package, and what was done about it

| Target | Consequence | Change |
| --- | --- | --- |
| `com.miui.systemui.util.MiGlassCompat` | the notification row's material was two halves and the dressing waited for both, so the islands never dressed | the glass half is optional; blend plus the `MiBlurCompat` extras is the look |
| `NotificationUtil.applyContainerViewBlur(Context,View,int,int,boolean)` | only `(Context,View,boolean)` exists here; the miss sent every dressing to the plain fill | the overload is picked by its first two parameters, and every OEM step fails alone |
| `KeyguardClockNotifInteractor`, `KeyguardClockContainer.notifStateChange` | nothing recorded the squeeze's y, so cover mode's clock had no pose | `ClockCollapse.naturalY` reads the y off the clock target's translation where no emission ever arrives |
| `com.miui.clock.allInOne.TimeView`, `setGlassColor`, `setBrightness` | the OS4 clock-glass hooks have no class to attach to | the cover clock poses `MiuiTextGlassView` through the generic clock search instead; glass colour and brightness control are not available |
| `com.miui.clock.utils.avoid.DepthAvoidEvaluator` | the depth threshold restriction cannot be lifted | left failed and reported in `op tweaks` |
| `MiuiKeyguardWallPaperManager.updateKeyguardWallpaperStateAnim(..., Runnable)` | its sixth parameter is the injector's own callback class here | matched by shape; the first five are unchanged and are all that is read |
| `ClockBean.isColonShow` | the colon option has no hook | left failed and reported |

## The music lock screen

The cover itself works: the album art is pushed to the lock wallpaper's back layer, and the
depth wallpaper's cut-out stays in front of it, which is what the wallpaper compositor does.
What was missing was our own clock taking the lock screen over, and that had one cause - the
y the pose is built from. It is now read from the clock target (see above).

It stays off by default: `SettingSpec.COVER_FEATURE` is `false`, so a fresh install gets the
rest of the module and a clock that behaves. To try it, turn 封面 on in the app and restart the
SystemUI scope; the switch is unchanged.

## Diagnostics that ship with this fork

On the first keyguard attach of every SystemUI process, twelve seconds in, three reports are
written to `/sdcard/Download/`: `mc_hookmap.txt` (which classes this SystemUI declares, and the
clock/notification-shaped methods on each), `mc_clock.txt` (`Diagnostics.clockReport`), and
`mc_mini.txt` (`MiniPlayerRuntime.describe`). They were added because the module's own INFO lines
do not survive to logcat on this device and there is no adb on the phone itself. Remove the
`writeHookMapOnce` call in `Main` when they are no longer wanted.
