package com.os4.musiccover;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The lock screen's host for full-screen immersive pages - what every {@link ImmersiveScene}
 * shares, so that a new kind of page is a scene and nothing else.
 *
 * The flow, the way ColorOS 16's lock screen island does it and the way the music's island opens
 * the cover here:
 *
 *   ready    a scene says it has a page (高德 navigating). The host puts its slot into the lock
 *            screen window and, on the first lock screen shown, has the scene prepare its page
 *            there, hidden - so the tap that opens it finds it already drawn.
 *   open     the scene's focus island is tapped open (LockIslands.release): the island turns
 *            into its notification row, the page goes up behind the lock screen, the clock goes
 *            small and the wallpaper's cut-out goes (Main.onImmersive). Pulled back into its
 *            island (LockIslands.recapture), all of that is undone. It stays open across an
 *            unlock, as the cover does, until it is pulled back or the scene stops being ready.
 *
 * Opening and closing on the lit lock screen crossfade the page with what is under it, over the
 * cover's own crossfade (Main.fadeMsFor the clock's response, eased out the same way), so the
 * page arrives with the clock. Closed, the clock starts home at once and the page fades out with
 * it; turned round half way, the fade turns from where it is. Everything else - an unlock, the
 * doze, the shade over an app - is a cut, as it always was: those go with the lock screen's own
 * motion, and a page fading there would be one left behind.
 *
 * The page is blurred above and below the band it keeps its information in (EdgeBlurView,
 * ImmersiveScene.sharpBand), which is where the clock and the cards sit over it.
 *
 * Handing over to the cover and back, which is what an exchange between the music's island and
 * the page's island is (reported 2026-09-29: the wallpaper showed through between the two):
 *   - page closing onto the cover: the cover is composed and pushed to the wallpaper process
 *     and only then starts in, 60-120ms after the tap; a page that fades at once has half gone
 *     by then and shows the plain wallpaper. So the page holds until the wallpaper says the
 *     cover is on its way (Main's op wpart, coverShown) - COVER_WAIT_MS at most - and then goes
 *     on a curve that keeps it until the cover is mostly in.
 *   - page opening over the cover: the cover leaving the wallpaper waits until the page covers
 *     it (coverOffHoldMs, read by CoverPush); a tap back in before then drops that leave, and
 *     the cover never went.
 *
 * One scene is open at a time. The cover and a page are one backdrop between them, and the later
 * one is the one shown: cover mode starting while a page is open makes the page yield - it fades
 * out onto the cover as a close does, though its island stays open - and cover mode ending gives
 * it back. That is what an interrupted exchange needs: turned round mid-morph, the music's island
 * goes up into the cover and back without the page's row ever being taken back in, and the
 * cover's card sat over the map instead of its blurred cover (filmed 2026-09-29). The clock is
 * held while either wants it (Main.clockHeld).
 *
 * Where the page is seen: the lit lock screen, and the FULL-SCREEN doze - the lock screen dimmed -
 * under a black veil, because the doze dims the keyguard's views and a page under the window is
 * not one of them. Not the plain doze (a clock on black), and not the shade pulled over an
 * unlocked phone, which is the same window. The slot sits at the bottom of the shade window's
 * root, under every keyguard view; a SurfaceView in it at its default z-order is composited below
 * the window altogether, which is ColorOS's arrangement (its SurfaceView is at relative z -2).
 *
 * The doze: some ten seconds in, the AOD puts the display in DOZE_SUSPEND, where the panel repeats
 * its last frame and new ones never reach it. A DRAW_WAKE_LOCK lifts it back to DOZE while it is
 * held (see LockLyrics.drawStill, which does this per lyric line). A page drawn in another process
 * never says when it changes, so for a scene that asks, the display is let up on a beat.
 *
 * Probe: {@code op immersive [--es id <scene>] [--es do state|open|close|arm|disarm]}.
 */
final class ImmersiveHost {

    private ImmersiveHost() {
    }

    private static final String TAG = "MCImmersive: ";

    /** Every kind of page there is. A new one is one line here. */
    private static final List<ImmersiveScene> SCENES = new ArrayList<>();

    static {
        SCENES.add(AmapNavScene.INSTANCE);
    }

    /**
     * How much of the page the full-screen doze covers. The doze dims the lock screen's own views
     * and the wallpaper; a page under the window would stay at full brightness. A first guess,
     * to be matched to the doze by eye.
     */
    private static final float DOZE_VEIL = 0.6f;
    private static final long VEIL_MS = 300L;

    /** The doze's beat: how often the display is let up for the page, and for how long. */
    private static final long DOZE_TICK_MS = 1000L;
    private static final long DOZE_LOCK_MS = 300L;
    /** PowerManager.DRAW_WAKE_LOCK, which the SDK hides. SystemUI holds DEVICE_POWER. */
    private static final int DRAW_WAKE_LOCK = 0x80;

    private static final Handler sMain = new Handler(Looper.getMainLooper());

    /**
     * Under every keyguard view: the scenes' pages, and the veil over them. Always VISIBLE itself:
     * a SurfaceView goes by its own visibility, not its parent's, so each page is shown and hidden
     * by its scene (onShown) and the slot only holds them.
     */
    private static FrameLayout sSlot;
    /** Over the pages, under the veil: the blur above and below the shown page's sharp band. */
    private static EdgeBlurView sEdge;
    private static View sVeil;
    private static float sVeilTo;
    private static ViewTreeObserver sVto;

    /** The scenes whose page is in the slot. */
    private static final Set<ImmersiveScene> sPrepared = new HashSet<>();
    /** The scene whose island is open, whether or not it has a page yet. */
    private static ImmersiveScene sOpen;
    /** The open scene has given way to the cover, which came after it. See the class comment. */
    private static boolean sYield;
    /** The lock screen is held for a page: open, with a page. See Main.clockHeld(). */
    private static boolean sHolding;
    /** The page is on screen. */
    private static boolean sShown;

    /** Closed on the lit lock screen, and fading out: still shown until the fade is over. */
    private static ImmersiveScene sLeaving;
    /** The fade-out has not started: waiting to see whether a cover is coming, then for it. */
    private static boolean sLeaveHeld;
    /** The fade-out follows a cover fading in underneath: the later curve. */
    private static boolean sLeaveOntoCover;
    /** When cover mode last started in, until the wallpaper said the cover is showing; 0 = not. */
    private static long sCoverPendingAt;
    /**
     * The cover is on the wallpaper: the wallpaper said it is showing (op wpart), and no leave
     * has gone out since. A leave held for a page and then dropped (CoverPush) never went, so
     * through a run of quick exchanges the cover stays on the wallpaper the whole time - and a
     * page yielding to it then has nothing to wait for (reported 2026-09-29: waiting for a wpart
     * the cover did not need, then going on the late curve, the map was still at 0.8 when the
     * next tap brought it back, and the cover barely showed).
     */
    private static volatile boolean sCoverOnWall;
    /** This leave waited for the cover to start in: it goes on the late curve. */
    private static boolean sLeaveWaited;

    /** The longest a closing page waits for the cover under it. */
    private static final long COVER_WAIT_MS = 350L;
    /**
     * How long a close waits before deciding whether a cover is coming: the tap that exchanges
     * the page for the music closes the page first, and cover mode starts a few ms after, from
     * another message.
     */
    private static final long COVER_DECIDE_MS = 32L;
    /** The next time a page appears it fades in: it was opened, not woken to. */
    private static boolean sFadeInNext;
    /** The shown page's opacity, and where it is going. */
    private static float sFade = 1f;
    private static float sFadeTo = 1f;
    private static ValueAnimator sFadeAnim;

    /** The screen was lit on the last look, and whether the doze since is the full-screen one. */
    private static boolean sWasLit = true;
    private static boolean sFullDoze;

    private static PowerManager.WakeLock sDrawLock;
    private static boolean sTicking;
    /** For the probe: beats that let the display up, and ones that found it off. */
    private static int sDozeLifts, sDozeSkips;

    // ---------------------------------------------------------------- what the rest asks

    /** The lock screen is given up to a page: the clock is small and the cut-out gone for it. */
    static boolean holdsClock() {
        return sHolding;
    }

    /** The scene a tap on this focus island opens; null for an island that has none. */
    static ImmersiveScene sceneFor(String pkg, boolean focus) {
        for (ImmersiveScene s : SCENES) {
            if (s.serves(pkg, focus)) return s;
        }
        return null;
    }

    // ---------------------------------------------------------------- what the rest tells

    /** From LockIslands: the scene's island was tapped open. */
    static void open(ImmersiveScene scene) {
        if (sOpen == scene && !sYield) return;
        sOpen = scene;
        // Opened after the cover: the page is the later one now.
        sYield = false;
        // Opened again while its close is still fading: it turns round from where it is.
        if (sLeaving == scene) sLeaving = null;
        sFadeInNext = litNow();
        Xp.log(TAG + "open " + scene.id() + " (ready=" + scene.ready()
                + " page=" + scene.hasContent() + ")");
        apply();
    }

    /** From LockIslands: the scene's island was pulled back in. */
    static void close(ImmersiveScene scene) {
        if (sOpen != scene) return;
        boolean wasYielding = sYield;
        sOpen = null;
        sYield = false;
        // Already out of sight under the cover, or on its way there: nothing more to fade.
        if (wasYielding) {
            Xp.log(TAG + "close " + scene.id() + " (had yielded to the cover)");
            apply();
            return;
        }
        sFadeInNext = false;
        // On screen and lit: it fades out with the clock going home. Anywhere else it just goes.
        if (sShown && litNow()) {
            sLeaving = scene;
            sLeaveHeld = true;
            sLeaveOntoCover = false;
            sLeaveWaited = false;
            sMain.removeCallbacks(LEAVE_CHECK);
            sMain.postDelayed(LEAVE_CHECK, COVER_DECIDE_MS);
        }
        Xp.log(TAG + "close " + scene.id() + (sLeaving == scene ? ", fading out" : ""));
        apply();
    }

    /** From a scene: it has become ready, or stopped being. */
    static void readyChanged(ImmersiveScene scene) {
        boolean ready = scene.ready();
        Xp.log(TAG + scene.id() + (ready ? " ready" : " not ready"));
        if (ready) {
            ensureSlot();
            // A lock screen standing still draws no frames, and the pre-draw is where a scene is
            // prepared.
            if (sSlot != null) sSlot.invalidate();
        } else {
            if (sPrepared.remove(scene)) scene.release();
            if (sOpen == scene) {
                sOpen = null;
                try {
                    LockIslands.INSTANCE.forgetScene(scene);
                } catch (Throwable t) {
                    Xp.log(TAG + "island not forgotten: " + t);
                }
            }
            if (!anyReady()) dropSlot();
        }
        apply();
    }

    /** From a scene: its page arrived, or went. */
    static void contentChanged(ImmersiveScene scene) {
        Xp.log(TAG + scene.id() + (scene.hasContent() ? " has a page" : " lost its page"));
        apply();
    }

    /**
     * The keyguard's clock container attached: a rebuilt keyguard, or the first one. A slot that
     * went with an old window, or that was waited for because there was no window when a scene
     * became ready, is put in now.
     */
    static void onContainerAttached() {
        if (!anyReady()) return;
        if (sSlot != null && sSlot.isAttachedToWindow()) return;
        ensureSlot();
        apply();
    }

    /**
     * Cover mode has started in: the cover is being composed for the wallpaper. An open page
     * yields to it, fading out onto it the way a close does.
     */
    static void coverEntering() {
        // Already there: nothing to wait for.
        sCoverPendingAt = sCoverOnWall ? 0L : SystemClock.uptimeMillis();
        ImmersiveScene open = sOpen;
        if (open == null || sYield) return;
        sYield = true;
        sFadeInNext = false;
        if (sShown && litNow()) {
            sLeaving = open;
            sLeaveHeld = true;
            sLeaveOntoCover = false;
            sLeaveWaited = false;
            // The cover is known to be coming - this is it coming - so no wait to decide.
            sMain.removeCallbacks(LEAVE_CHECK);
            sMain.post(LEAVE_CHECK);
        }
        Xp.log(TAG + open.id() + " yields to the cover");
        apply();
    }

    /** Cover mode has ended: a page that yielded to it, and is still open, comes back. */
    static void coverLeft() {
        if (!sYield) return;
        sYield = false;
        sFadeInNext = litNow();
        if (sOpen != null) Xp.log(TAG + sOpen.id() + " back from under the cover");
        apply();
    }

    /** The wallpaper has started showing the cover. Any thread. */
    static void coverShown() {
        sCoverOnWall = true;
        sMain.post(() -> {
            sCoverPendingAt = 0L;
            if (sLeaveHeld) LEAVE_CHECK.run();
        });
    }

    /** The cover's leave has gone out to the wallpaper. Any thread (CoverPush's worker). */
    static void coverOffSent() {
        sCoverOnWall = false;
    }

    /**
     * How long the cover's leave should wait: while a page is fading in over it, the rest of that
     * fade. Asked by CoverPush on the way out of cover mode, on the main thread.
     */
    static long coverOffHoldMs() {
        // A page that yielded to this cover comes back when it goes (coverLeft), and CoverPush
        // asks before cover mode has ended: the whole fade in is still ahead of it.
        if (sYield && sOpen != null && sOpen.ready() && sOpen.hasContent() && litNow()) {
            return Main.fadeMsFor(Main.sClockResponse) + 32L;
        }
        ValueAnimator a = sFadeAnim;
        if (a == null || sFadeTo != 1f || !sShown) return 0L;
        long left = a.getDuration() - a.getCurrentPlayTime();
        return Math.max(0L, left) + 32L;
    }

    /**
     * A closed page decides when to go: now, when no cover is coming; when the wallpaper says the
     * cover is showing; or when COVER_WAIT_MS has passed without it saying so.
     */
    private static final Runnable LEAVE_CHECK = new Runnable() {
        @Override
        public void run() {
            sMain.removeCallbacks(this);
            if (!sLeaveHeld) return;
            long since = sCoverPendingAt == 0L ? -1L : SystemClock.uptimeMillis() - sCoverPendingAt;
            boolean coverComing = Main.coverModeOn();
            if (coverComing && since >= 0L && since < COVER_WAIT_MS) {
                sLeaveWaited = true;
                sMain.postDelayed(this, COVER_WAIT_MS - since);
                return;
            }
            sLeaveHeld = false;
            // The late curve only over a cover that is still fading in; one that never left is
            // simply uncovered, on the ordinary curve.
            sLeaveOntoCover = coverComing && sLeaveWaited;
            Xp.log(TAG + "leave goes " + (coverComing
                    ? (since >= 0L ? "without the cover (" + since + "ms)"
                        : sLeaveWaited ? "onto the cover as it comes in" : "onto the cover already there")
                    : "on its own"));
            apply();
        }
    };

    /** SystemUI has started: scenes whose app may already be in the state ask it. */
    static void onStart(Context ctx) {
        AmapNavScene.INSTANCE.ask(ctx);
    }

    // ---------------------------------------------------------------- the slot

    private static boolean anyReady() {
        for (ImmersiveScene s : SCENES) {
            if (s.ready()) return true;
        }
        return false;
    }

    private static void ensureSlot() {
        if (sSlot != null && sSlot.isAttachedToWindow()) return;
        if (sSlot != null) dropSlot();
        View c = Main.sContainer;
        View root = c == null ? null : c.getRootView();
        if (!(root instanceof ViewGroup)) {
            Xp.log(TAG + "no lock screen window yet; the slot waits for the next ready");
            return;
        }
        FrameLayout slot = new FrameLayout(root.getContext());
        // With ids: a view without one in a ConstraintLayout crashes SystemUI when the keyguard
        // swaps its blueprint (f674da1's disc). This root is not one, but that is not ours to rely on.
        slot.setId(View.generateViewId());
        EdgeBlurView edge = new EdgeBlurView(root.getContext());
        edge.setId(View.generateViewId());
        edge.setVisibility(View.INVISIBLE);
        slot.addView(edge, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View veil = new View(root.getContext());
        veil.setId(View.generateViewId());
        veil.setBackgroundColor(Color.BLACK);
        veil.setAlpha(0f);
        sVeilTo = 0f;
        slot.addView(veil, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ((ViewGroup) root).addView(slot, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        sSlot = slot;
        sEdge = edge;
        sVeil = veil;
        sShown = false;
        sVto = root.getViewTreeObserver();
        sVto.addOnPreDrawListener(PRE_DRAW);
        Xp.log(TAG + "slot in the lock screen window");
        root.invalidate();
    }

    private static void dropSlot() {
        // A copy: release() tells the host its page went, and the host looks at this set.
        List<ImmersiveScene> prepared = new ArrayList<>(sPrepared);
        sPrepared.clear();
        for (ImmersiveScene s : prepared) s.release();
        if (sVto != null && sVto.isAlive()) sVto.removeOnPreDrawListener(PRE_DRAW);
        sVto = null;
        if (sSlot != null) {
            ViewGroup parent = (ViewGroup) sSlot.getParent();
            if (parent != null) parent.removeView(sSlot);
            Xp.log(TAG + "slot removed");
        }
        sSlot = null;
        sEdge = null;
        sVeil = null;
        sShown = false;
        beat(false);
    }

    /**
     * Every frame the shade window draws: prepare the ready scenes on a lock screen, and show or
     * hide the page. Returning false when that changes re-runs the traversal, so the frame drawn
     * is the one with the page already in its new state - the shade pulled over an app never
     * shows a frame of it.
     */
    private static final ViewTreeObserver.OnPreDrawListener PRE_DRAW =
            new ViewTreeObserver.OnPreDrawListener() {
        @Override
        public boolean onPreDraw() {
            FrameLayout slot = sSlot;
            if (slot == null) return true;
            if (onLockScreen()) {
                for (ImmersiveScene s : SCENES) {
                    if (s.ready() && !sPrepared.contains(s)) {
                        sPrepared.add(s);
                        try {
                            s.prepare(slot);
                        } catch (Throwable t) {
                            Xp.log(TAG + s.id() + " prepare failed: " + t);
                        }
                    }
                }
            }
            return !apply();
        }
    };

    // ---------------------------------------------------------------- the one decision

    /**
     * Puts the lock screen, the page, the veil and the beat where the state says. Returns whether
     * what is on screen changed.
     */
    private static boolean apply() {
        ImmersiveScene open = sOpen;
        boolean holding = open != null && !sYield && open.ready() && open.hasContent();
        // A page fading out stays until its fade is over - as long as it still has a page and the
        // lock screen is still lit; an unlock or a doze in the middle of it is a cut.
        if (sLeaving != null && (holding || !sLeaving.ready() || !sLeaving.hasContent()
                || !litNow())) {
            sLeaving = null;
        }
        if (sLeaving == null && sLeaveHeld) {
            sLeaveHeld = false;
            sMain.removeCallbacks(LEAVE_CHECK);
        }
        // A close still deciding whether a cover is coming keeps the lock screen too: given back
        // at once, the clock started home and the cut-out was let back for the few ms before the
        // cover took them again - and the cut-out can land a frame in between.
        boolean keep = holding || sLeaveHeld;
        if (keep != sHolding) {
            sHolding = keep;
            try {
                Main.onImmersive(keep);
            } catch (Throwable t) {
                Xp.log(TAG + "lock screen " + (keep ? "hold" : "hand-back") + " failed: " + t);
            }
        }
        if (sSlot == null) return false;
        ImmersiveScene page = holding ? open : sLeaving;
        boolean shown = page != null && mayShow();
        boolean dozing = shown && !screenOn();
        veil(dozing ? DOZE_VEIL : 0f, shown);
        beat(dozing && page.needsDozeBeat());
        fade(page, shown);
        EdgeBlurView edge = sEdge;
        if (edge != null) {
            if (shown) edge.setBand(page.sharpBand());
            int ev = shown ? View.VISIBLE : View.INVISIBLE;
            if (edge.getVisibility() != ev) edge.setVisibility(ev);
        }
        boolean changed = false;
        // Only that page: a prepared page that is not the one on screen stays hidden.
        for (ImmersiveScene s : sPrepared) {
            try {
                s.onShown(shown && s == page, dozing);
            } catch (Throwable t) {
                Xp.log(TAG + s.id() + " show failed: " + t);
            }
        }
        if (shown != sShown) {
            sShown = shown;
            Xp.log(TAG + (shown ? "shown " + page.id() + (dozing ? " (doze)" : "") : "hidden"));
            changed = true;
        }
        return changed;
    }

    private static boolean litNow() {
        return onLockScreen() && screenOn();
    }

    /**
     * Where the shown page's opacity goes: to nothing for one fading out, to full otherwise -
     * faded from nothing when it is appearing because it was opened, cut to full when it is
     * appearing for any other reason (a wake, a relock), and turned round from where it is when
     * the direction changes half way.
     */
    private static void fade(ImmersiveScene page, boolean shown) {
        if (!shown) {
            stopFade();
            sFade = sFadeTo = 1f;
            return;
        }
        boolean leaving = page == sLeaving;
        if (!sShown) {
            // Appearing.
            stopFade();
            sFade = sFadeInNext && !leaving ? 0f : 1f;
            sFadeTo = sFade;
            sFadeInNext = false;
            setPageFade(page, sFade);
        }
        // Spent either way: a page turned round half way was not appearing, and must not carry
        // the flag to the next wake.
        sFadeInNext = false;
        // Held: stays where it is until LEAVE_CHECK lets it go.
        if (leaving && sLeaveHeld) return;
        float to = leaving ? 0f : 1f;
        if (to == sFadeTo) return;
        sFadeTo = to;
        startFade(page, to, leaving && sLeaveOntoCover);
    }

    /** @param late the curve that keeps the page until the cover under it is mostly in */
    private static void startFade(final ImmersiveScene page, final float to, boolean late) {
        stopFade();
        final float from = sFade;
        long ms = Main.fadeMsFor(Main.sClockResponse);
        // The share of the full fade this one still has to go, so a turn half way is not slower.
        ms = Math.max(1L, Math.round(ms * Math.abs(to - from)));
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(ms);
        // The cover crossfade's ease-out (WallpaperProbe): a spring is all front-loaded, and the
        // page keeps company with one. Going onto a cover that fades in on that same curve, its
        // square: the plain wallpaper between the two then peaks at 15% instead of 25%.
        if (late) {
            a.setInterpolator(t -> {
                float e = 1f - (1f - t) * (1f - t) * (1f - t);
                return e * e;
            });
        } else {
            a.setInterpolator(t -> 1f - (1f - t) * (1f - t) * (1f - t));
        }
        a.addUpdateListener(an -> {
            if (sFadeAnim != an) return;
            sFade = from + (to - from) * (float) an.getAnimatedValue();
            setPageFade(page, sFade);
        });
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator an) {
                if (sFadeAnim != an) return;
                sFadeAnim = null;
                sFade = to;
                setPageFade(page, to);
                if (to == 0f && sLeaving == page) {
                    sLeaving = null;
                    apply();
                }
            }
        });
        sFadeAnim = a;
        a.start();
        Xp.log(TAG + "fade " + page.id() + " " + from + " -> " + to + " over " + ms + "ms");
    }

    private static void stopFade() {
        ValueAnimator a = sFadeAnim;
        sFadeAnim = null;
        if (a != null) a.cancel();
    }

    private static void setPageFade(ImmersiveScene page, float alpha) {
        try {
            page.setFade(alpha);
        } catch (Throwable t) {
            Xp.log(TAG + page.id() + " fade failed: " + t);
        }
        if (sEdge != null) sEdge.setFade(alpha);
    }

    private static boolean onLockScreen() {
        try {
            return Main.immersiveOnLockScreen();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean screenOn() {
        try {
            return Main.screenOn();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * Whether a page may be on screen now. Asks which doze this is once per sleep, on the first
     * look after the screen went off: the question reflects into the interfaces manager, and this
     * runs every frame.
     */
    private static boolean mayShow() {
        if (!onLockScreen()) return false;
        if (screenOn()) {
            sWasLit = true;
            return true;
        }
        if (sWasLit) {
            sWasLit = false;
            try {
                sFullDoze = Main.fullAodOn();
            } catch (Throwable t) {
                sFullDoze = false;
            }
            Xp.log(TAG + "doze: " + (sFullDoze ? "full-screen, the page stays" : "plain, no page"));
        }
        return sFullDoze;
    }

    /**
     * The doze's dimming. Faded only between two frames that both show the page - into the doze
     * and out of it on a wake, as the OEM fades the lock screen. Put straight to its value when the
     * page appears or goes with it: shown into a doze, the page is never at full brightness for a
     * frame; hidden - an unlock straight from the doze - no black is left fading over the lock
     * screen with nothing under it.
     */
    private static void veil(float to, boolean shownNow) {
        View veil = sVeil;
        if (veil == null || to == sVeilTo) return;
        sVeilTo = to;
        veil.animate().cancel();
        if (sShown && shownNow) veil.animate().alpha(to).setDuration(VEIL_MS).start();
        else veil.setAlpha(to);
    }

    private static void beat(boolean on) {
        if (on == sTicking) return;
        sTicking = on;
        sMain.removeCallbacks(DOZE_TICK);
        if (on) sMain.postDelayed(DOZE_TICK, DOZE_TICK_MS);
        Xp.log(TAG + "doze beat " + (on ? "on" : "off"));
    }

    /**
     * One beat of the doze. Only a display that is dozing is let up: one the proximity sensor has
     * turned OFF would be turned back on by the lock, which is the pocket the AOD went dark for.
     * The frames stop the beat when they see the screen lit; this checks too, because in
     * DOZE_SUSPEND there are no frames.
     */
    private static final Runnable DOZE_TICK = new Runnable() {
        @Override
        public void run() {
            FrameLayout slot = sSlot;
            if (slot == null || !sShown || screenOn()) {
                sTicking = false;
                return;
            }
            Display d = slot.getDisplay();
            int st = d == null ? -1 : d.getState();
            if (st == Display.STATE_DOZE || st == Display.STATE_DOZE_SUSPEND) {
                try {
                    if (sDrawLock == null) {
                        PowerManager pm = slot.getContext().getSystemService(PowerManager.class);
                        sDrawLock = pm.newWakeLock(DRAW_WAKE_LOCK, "MusicCover:immersiveDraw");
                        sDrawLock.setReferenceCounted(false);
                    }
                    sDrawLock.acquire(DOZE_LOCK_MS);
                    sDozeLifts++;
                } catch (Throwable t) {
                    Xp.log(TAG + "draw wake lock failed: " + t);
                }
            } else {
                sDozeSkips++;
            }
            sMain.postDelayed(this, DOZE_TICK_MS);
        }
    };

    // ---------------------------------------------------------------- probe

    /** {@code op immersive}: any thread; the work is posted, the answer is the state now. */
    static String command(String id, String what) {
        final ImmersiveScene scene = byId(id);
        if (what != null && scene != null) {
            final String w = what;
            sMain.post(() -> {
                switch (w) {
                    case "open": open(scene); break;
                    case "close": close(scene); break;
                    case "arm":
                    case "disarm":
                        if (scene instanceof LiveAlertScene) {
                            ((LiveAlertScene) scene).setArmed("arm".equals(w));
                        }
                        break;
                    default: break;
                }
            });
        }
        return state();
    }

    private static ImmersiveScene byId(String id) {
        for (ImmersiveScene s : SCENES) {
            if (id == null || s.id().equals(id)) return s;
        }
        return null;
    }

    static String state() {
        StringBuilder sb = new StringBuilder();
        sb.append("open=").append(sOpen == null ? "-" : sOpen.id())
                .append(" yield=").append(sYield)
                .append(" coverOnWall=").append(sCoverOnWall)
                .append(" holding=").append(sHolding)
                .append(" shown=").append(sShown)
                .append(" leaving=").append(sLeaving == null ? "-" : sLeaving.id())
                .append(" fade=").append(sFade).append("->").append(sFadeTo)
                .append(" slot=").append(sSlot != null)
                .append(" fullDoze=").append(sFullDoze)
                .append(" veil=").append(sVeil == null ? "-" : String.valueOf(sVeil.getAlpha()))
                .append(" edge=").append(sEdge == null ? "-"
                        : sEdge.unavailable() ? "unavailable" : sEdge.getVisibility() == View.VISIBLE ? "on" : "off")
                .append(" beat=").append(sTicking)
                .append(" lifts=").append(sDozeLifts)
                .append(" skips=").append(sDozeSkips);
        if (sSlot != null && sSlot.getDisplay() != null) {
            sb.append(" display=").append(sSlot.getDisplay().getState());
        }
        sb.append(" clock=").append(ClockCollapse.phase());
        for (ImmersiveScene s : SCENES) {
            sb.append("\n  ").append(s.id()).append(": ready=").append(s.ready())
                    .append(" prepared=").append(sPrepared.contains(s))
                    .append(" page=").append(s.hasContent())
                    .append(' ').append(s.describe());
        }
        sb.append('\n').append(Xp.tail("MCImmersive", 40));
        return sb.toString();
    }
}
