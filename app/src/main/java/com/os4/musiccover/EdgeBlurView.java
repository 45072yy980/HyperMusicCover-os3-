package com.os4.musiccover;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.View;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Blurs an immersive page above and below its sharp band, so the clock and the cards over it
 * read against a soft picture instead of a map's lines and labels.
 *
 * The page is under the shade window, and the blur is the platform's cross-window one:
 * ViewRootImpl.createBackgroundBlurDrawable() gives a drawable that, drawn in a view, has
 * SurfaceFlinger blur whatever is behind the window inside its bounds - the page, here, as the
 * clock's glass and the cards' blur sample the wallpaper there. Checked on this phone
 * (2026-09-29): ro.surface_flinger.supports_background_blur=1, WindowManager mBlurEnabled=true.
 *
 * A blur region has hard edges, so the edge that meets the sharp band is cut into STEPS strips
 * whose alpha steps down to nothing. SurfaceFlinger blurs once per radius and draws each region
 * from that one blurred picture, so the strips cost little more than one region.
 *
 * All sizes are fractions of the view's height, or dp: nothing here depends on the display.
 */
final class EdgeBlurView extends View {

    /** Strips in each soft edge; the edge is the whole band now, so more of them. */
    private static final int STEPS = 14;
    /**
     * How far the soft edge reaches into the blurred side, as a fraction of the height. At least
     * the whole band: the blur is heaviest at the screen's edge and has gone by the sharp band,
     * with no solid stretch. A solid band from the edge to the clock read as too much blur, and
     * too heavy (user, 2026-09-29).
     */
    private static final float EDGE = 1f;
    /**
     * The blur's radius by how near the strip is to the screen's edge: heavier at the edge, light
     * by the band (user, 2026-09-29: "靠近屏幕边缘的模糊改重一点"). Three tiers, not one radius per
     * strip: SurfaceFlinger blurs once per distinct radius, so each tier is one more blur pass.
     */
    private static final float[] RADIUS_DP = {32f, 22f, 14f};

    /** Top blurred band: solid, then its soft edge; the bottom band mirrors it. */
    private final List<Drawable> mStrips = new ArrayList<>();
    private final List<Float> mAlphas = new ArrayList<>();
    private float mTop = Float.NaN, mBottom = Float.NaN;
    private float mFade = 1f;
    private boolean mUnavailable;
    private Method mSetRadius;

    EdgeBlurView(Context ctx) {
        super(ctx);
        setWillNotDraw(false);
    }

    /** The sharp band, as fractions of the height; null takes the blur away. */
    void setBand(float[] band) {
        float top = band == null ? Float.NaN : band[0];
        float bottom = band == null ? Float.NaN : band[1];
        if (same(top, mTop) && same(bottom, mBottom)) return;
        mTop = top;
        mBottom = bottom;
        if (band == null) {
            // Emptied and drawn once more, rather than hidden: hidden, the strips' regions stayed
            // registered with SurfaceFlinger - 28 of them, radius up to 96px at full alpha across
            // the top and bottom, still there with the countdown's page up (2026-09-30).
            for (Drawable d : mStrips) {
                d.setBounds(0, 0, 0, 0);
                d.setAlpha(0);
            }
            mCleared = false;
        } else {
            layoutStrips();
        }
        invalidate();
    }

    /** Whether it is blurring anything: a page's band is set. */
    boolean hasBand() {
        return !Float.isNaN(mTop);
    }

    /** The emptied strips have been drawn once, which is what tells SurfaceFlinger. */
    private boolean mCleared = true;

    /** The page's own opacity: the blur fades with it, or the wallpaper's edges would blur first. */
    void setFade(float fade) {
        if (fade == mFade) return;
        mFade = fade;
        applyAlphas();
        invalidate();
    }

    boolean unavailable() {
        return mUnavailable;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        build();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mStrips.clear();
        mAlphas.clear();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        layoutStrips();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (Float.isNaN(mTop)) {
            if (!mCleared) {
                for (Drawable d : mStrips) d.draw(canvas);
                mCleared = true;
            }
            return;
        }
        if (mFade <= 0f) return;
        for (Drawable d : mStrips) d.draw(canvas);
    }

    /** STEPS strips for each edge plus a solid band on each side: 2 * (STEPS + 1). */
    private void build() {
        mStrips.clear();
        mAlphas.clear();
        try {
            Object vri = View.class.getMethod("getViewRootImpl").invoke(this);
            Method create = vri.getClass().getMethod("createBackgroundBlurDrawable");
            for (int i = 0; i < 2 * (STEPS + 1); i++) {
                Drawable d = (Drawable) create.invoke(vri);
                if (mSetRadius == null) mSetRadius = d.getClass().getMethod("setBlurRadius", int.class);
                mStrips.add(d);
                mAlphas.add(1f);
            }
            mUnavailable = false;
        } catch (Throwable t) {
            mStrips.clear();
            mAlphas.clear();
            mUnavailable = true;
            Xp.log("MCImmersive: edge blur unavailable: " + t);
        }
        layoutStrips();
    }

    /**
     * Top: [0, top - edge] solid, then STEPS strips to top, fading out. Bottom: STEPS strips from
     * bottom, fading in, then [bottom + edge, height] solid. The fall is a smoothstep, so the
     * blur leaves the band without a visible first step.
     */
    private void layoutStrips() {
        int w = getWidth();
        int h = getHeight();
        if (mStrips.isEmpty() || w <= 0 || h <= 0 || Float.isNaN(mTop)) return;
        float edge = EDGE * h;
        float top = mTop * h;
        float bottom = mBottom * h;
        float topSolid = Math.max(0f, top - edge);
        float bottomSolid = Math.min(h, bottom + edge);
        int k = 0;
        k = place(k, 0f, topSolid, 1f, 0, w);
        for (int i = 0; i < STEPS; i++) {
            float a = topSolid + (top - topSolid) * i / STEPS;
            float b = topSolid + (top - topSolid) * (i + 1) / STEPS;
            // i = 0 is the strip at the screen's edge.
            k = place(k, a, b, 1f - smooth((i + 0.5f) / STEPS), i, w);
        }
        for (int i = 0; i < STEPS; i++) {
            float a = bottom + (bottomSolid - bottom) * i / STEPS;
            float b = bottom + (bottomSolid - bottom) * (i + 1) / STEPS;
            // Here the strip at the screen's edge is the last one.
            k = place(k, a, b, smooth((i + 0.5f) / STEPS), STEPS - 1 - i, w);
        }
        place(k, bottomSolid, h, 1f, 0, w);
        applyAlphas();
    }

    /** @param fromEdge the strip's place counted from the screen's edge, 0 = at it */
    private int place(int k, float top, float bottom, float alpha, int fromEdge, int w) {
        Drawable d = mStrips.get(k);
        d.setBounds(0, Math.round(top), w, Math.round(bottom));
        mAlphas.set(k, alpha);
        int tier = Math.min(RADIUS_DP.length - 1, fromEdge * RADIUS_DP.length / STEPS);
        try {
            mSetRadius.invoke(d, Math.round(RADIUS_DP[tier] * getResources().getDisplayMetrics().density));
        } catch (Throwable t) {
            Xp.log("MCImmersive: edge blur radius not set: " + t);
        }
        return k + 1;
    }

    private void applyAlphas() {
        for (int i = 0; i < mStrips.size(); i++) {
            mStrips.get(i).setAlpha(Math.round(255f * mAlphas.get(i) * mFade));
        }
    }

    private static float smooth(float t) {
        return t * t * (3f - 2f * t);
    }

    private static boolean same(float a, float b) {
        return a == b || Float.isNaN(a) && Float.isNaN(b);
    }
}
