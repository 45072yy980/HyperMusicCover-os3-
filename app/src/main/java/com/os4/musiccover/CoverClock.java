package com.os4.musiccover;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Cover mode's clock, drawn by us.
 *
 * The module's own cover clock is a pose written onto the OEM's views: one scale and one
 * translation, mapped from the y the OEM squeezed the clock by and from the interactor's
 * heights. HyperOS 3 has none of those - the squeeze is per style, with no y in the call, and
 * KeyguardClockNotifInteractor does not exist - so the pose came out at -81913 and the clock
 * vanished (measured 2026-11-07, from the clock report this module writes).
 *
 * Rather than pose a clock we cannot read, cover mode draws its own: the OEM clock is hidden and
 * a time and a date stand where it stood. Everything here is trivial by design - two TextViews
 * and a handler that rewrites the minute text - because the rest of the cover is what this
 * feature is for, not the clock.
 *
 * Hidden state is given back per hide(): the OEM view's alpha is restored from the value read
 * before the first hide, on every exit path (unlock, AOD, cover off).
 */
final class CoverClock {
    private CoverClock() {}

    private static View sRoot;
    private static FrameLayout sBox;
    private static TextView sTime;
    private static TextView sDate;
    private static View[] sOem = new View[0];
    private static float[] sOemAlpha = new float[0];
    private static boolean sShown;

    private static final SimpleDateFormat TIME_24 = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private static final SimpleDateFormat TIME_12 = new SimpleDateFormat("h:mm", Locale.getDefault());

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            if (!sShown) return;
            try {
                // Again every tick: a style change builds a new clock, and the new one has its
                // own alpha. Re-hiding is two field reads when it is the same view.
                hideOem();
                write();
            } catch (Throwable t) {
                Xp.w(Main.TAG + "cover clock tick: " + t);
            }
            Gen.handler().postDelayed(this, 15_000L);
        }
    };

    /** Cover mode's clock has to be on screen; called from every entry into the cover look. */
    static void show() {
        try {
            View c = Main.sContainer;
            if (c == null || !Main.screenOn() || !wanted()) return;
            if (!attach(c.getRootView())) return;
            write();
            sBox.setVisibility(View.VISIBLE);
            sShown = true;
            hideOem();
            Gen.handler().removeCallbacks(TICK);
            Gen.handler().postDelayed(TICK, 15_000L);
        } catch (Throwable t) {
            Xp.w(Main.TAG + "cover clock show failed: " + t);
        }
    }

    /** Every way out of the cover look: unlock, cover off, the AOD taking over. */
    static void hide() {
        sShown = false;
        Gen.handler().removeCallbacks(TICK);
        try {
            if (sBox != null) sBox.setVisibility(View.GONE);
        } catch (Throwable ignored) {
        }
        for (int i = 0; i < sOem.length; i++) {
            try {
                if (sOem[i] != null && !Float.isNaN(sOemAlpha[i])) sOem[i].setAlpha(sOemAlpha[i]);
            } catch (Throwable ignored) {
            }
        }
        sOem = new View[0];
        sOemAlpha = new float[0];
    }

    /** Whether the cover clock is ours to draw on this build; false keeps the OEM clock alone. */
    static boolean wanted() {
        return Settings.COVER_FEATURE.on() && Main.sCoverMode && Main.keyguardShowing();
    }

    static String describe() {
        View c = sBox;
        return "coverClock shown=" + sShown + " root=" + (sRoot != null)
                + " box=" + (c == null ? "null" : c.getVisibility() + "/" + c.getWidth() + "x" + c.getHeight())
                + " oemHidden=" + sOem.length
                + " h=" + Main.r1(Settings.CLOCK_HEIGHT.get()) + "dp";
    }

    // ------------------------------------------------------------------ view

    private static boolean attach(View root) {
        if (!(root instanceof ViewGroup)) return false;
        if (sRoot == root && sBox != null && sBox.getParent() == root) return true;
        if (sBox != null && sBox.getParent() instanceof ViewGroup) {
            ((ViewGroup) sBox.getParent()).removeView(sBox);
        }
        if (sBox == null) build();
        sRoot = root;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        ((ViewGroup) root).addView(sBox, lp);
        return true;
    }

    private static void build() {
        float density = Main.sContainer == null ? 3.25f
                : Main.sContainer.getResources().getDisplayMetrics().density;
        sBox = new FrameLayout(Main.sAppCtx);
        sBox.setClipChildren(false);
        sBox.setClipToPadding(false);
        sBox.setVisibility(View.GONE);
        sBox.setClickable(false);
        sBox.setFocusable(false);

        LinearLayout column = new LinearLayout(Main.sAppCtx);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setClickable(false);
        column.setFocusable(false);

        sTime = new TextView(Main.sAppCtx);
        sTime.setTextColor(Color.WHITE);
        sTime.setGravity(Gravity.CENTER_HORIZONTAL);
        sTime.setTypeface(Typeface.create("sans-serif-thin", Typeface.NORMAL));
        sTime.setIncludeFontPadding(false);
        sTime.setShadowLayer(density * 2f, 0f, density * 1f, 0x66000000);
        sTime.setTextSize(TypedValue.COMPLEX_UNIT_DIP, Math.max(24f, Settings.CLOCK_HEIGHT.get() * 2f));

        sDate = new TextView(Main.sAppCtx);
        sDate.setTextColor(0xE6FFFFFF);
        sDate.setGravity(Gravity.CENTER_HORIZONTAL);
        sDate.setIncludeFontPadding(false);
        sDate.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f);
        sDate.setPadding(0, Math.round(density * 6f), 0, 0);

        column.addView(sTime);
        column.addView(sDate);
        sBox.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.TOP));

        // Where the cover leaves the date: the same anchor the posed clock used, measured off the
        // date line, with the module's own gap under it. Falls back to a fifth of the screen when
        // no date view is up yet.
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) column.getLayoutParams();
        float top = -1f;
        try {
            View date = ClockViews.visibleDate();
            if (date != null) top = ClockCollapse.coverDateY(date);
        } catch (Throwable ignored) {
        }
        if (Float.isNaN(top) || top < 0f) top = Main.sScreenH * 0.2f;
        lp.topMargin = Math.round(top + density * 4f);
    }

    private static void write() {
        long now = System.currentTimeMillis();
        boolean h24 = DateFormat.is24HourFormat(Main.sAppCtx);
        String t = (h24 ? TIME_24 : TIME_12).format(new Date(now));
        String d = new SimpleDateFormat("M/d E", Locale.getDefault()).format(new Date(now));
        if (sTime != null && !t.equals(sTime.getText().toString())) sTime.setText(t);
        if (sDate != null && !d.equals(sDate.getText().toString())) sDate.setText(d);
    }

    /**
     * Puts the OEM clock away once ours is on screen, remembering what it was.
     *
     * Both keyguard clock trees: the style's own container and the foreground one, which is
     * where the date line lives on this build - hiding only the first left the OEM's date
     * standing beside ours.
     */
    private static void hideOem() {
        View[] roots = Main.clockRoots();
        View[] views = new View[roots.length];
        int n = 0;
        for (View root : roots) {
            View target = null;
            try {
                target = Main.clockTarget(root);
            } catch (Throwable ignored) {
            }
            if (target != null) views[n++] = target;
        }
        boolean same = n == sOem.length;
        for (int i = 0; same && i < n; i++) same = views[i] == sOem[i];
        if (!same) {
            sOem = new View[n];
            sOemAlpha = new float[n];
            System.arraycopy(views, 0, sOem, 0, n);
            for (int i = 0; i < n; i++) sOemAlpha[i] = sOem[i].getAlpha();
        }
        for (View v : sOem) {
            if (v.getAlpha() != 0f) v.setAlpha(0f);
        }
    }
}
