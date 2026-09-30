package com.os4.musiccover.ui.screen.features

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/*
 * The drawing kit for the app's demonstrations, after HyperOS's own 趣味拟物 cards (Settings,
 * 声音与触感 -> 触感: SettingsRroCommonOverlay's res/raw/v01, v02, v11, v16). Those are rendered
 * videos, so the look was measured off their frames (490x732, 60fps):
 *
 * - a phone drawn as its outline alone, a lavender-grey stroke about 3% of its width, corners at
 *   18%, two side keys on the right; the screen is the card's own colour;
 * - what is on the screen is wireframe: soft grey pills, darker grey bars for words, a small
 *   square where an icon goes. One colour is not grey, and it is the finger's;
 * - the finger is a solid blue dot, 12% of the phone's width. It comes in from off the phone on
 *   a curve and slows as it lands; pressed, a pale disc of the same blue swells round it; let go,
 *   the dot fades where it is and a thin ring runs out and thins away;
 * - what a touch does is shown with something to feel: a band of confetti bursting sideways, a
 *   check drawn in one stroke, the chosen row going one step darker.
 *
 * Everything here draws in the caller's units except the finger, which is a fixed size on screen
 * the way it is in the videos - a camera closing in must not turn it into a ball.
 */

/** One page of a demonstration: what it is called and what it shows. */
internal class DemoText(val title: String, val body: String)

/**
 * The frame both demonstrations play in: pages side by side, each on to the next when it has
 * played, the last back to the first; its title and a line under the picture; dots for where it
 * is. `picture` draws a page's animation and calls `done` when it has played through - it is
 * handed whether it is the page on screen, and starts over each time it becomes it.
 */
@Composable
internal fun DemoPager(
    pages: List<DemoText>,
    modifier: Modifier = Modifier,
    picture: @Composable (page: Int, playing: Boolean, done: () -> Unit) -> Unit,
) {
    val n = pages.size
    val pager = rememberPagerState { n }
    val scope = rememberCoroutineScope()
    Column(modifier) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth(), beyondViewportPageCount = 1) { page ->
            val playing = pager.settledPage == page && !pager.isScrollInProgress
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                picture(page, playing) { scope.launch { pager.animateScrollToPage((page + 1) % n) } }
                Spacer(Modifier.height(10.dp))
                Text(pages[page].title, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    color = MiuixTheme.colorScheme.onSurface)
                // Every page's line is laid out on every page and only this page's is shown, so all
                // of them are as tall as the longest. The pager is as tall as the pages it has up,
                // and with one line a line longer than the rest, the page furthest from it came up
                // a line short (user, 2026-09-30).
                Box(Modifier.padding(horizontal = 24.dp).padding(top = 6.dp).heightIn(min = 38.dp),
                    contentAlignment = Alignment.TopCenter) {
                    pages.forEachIndexed { i, t ->
                        val shown = i == page
                        Text(t.body, fontSize = 13.sp, textAlign = TextAlign.Center,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.alpha(if (shown) 1f else 0f)
                                .then(if (shown) Modifier else Modifier.clearAndSetSemantics { }))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.Center) {
            val on = MiuixTheme.colorScheme.onSurface
            repeat(n) { i ->
                Box(Modifier.padding(horizontal = 3.dp).size(6.dp)
                    .background(on.copy(alpha = if (i == pager.currentPage) 0.75f else 0.2f), CircleShape))
            }
        }
    }
}

/** The phone, in the units every demonstration draws it in: 100 wide, a 20:9 screen. */
internal const val PHONE_W = 100f
internal const val PHONE_H = 217f
internal const val PHONE_CORNER = 18f
internal const val PHONE_STROKE = 3.2f

internal class SkeuoPalette(
    val frame: Color,
    val pill: Color,
    val pillOn: Color,
    val line: Color,
    val accent: Color,
    val screen: Color,
)

@Composable
internal fun skeuoPalette(dark: Boolean): SkeuoPalette {
    val screen = MiuixTheme.colorScheme.surfaceContainer
    return if (dark) {
        SkeuoPalette(Color(0xFF666666), Color(0xFF333333), Color(0xFF424242), Color(0xFF565656),
            Color(0xFF3281FF), screen)
    } else {
        SkeuoPalette(Color(0xFFBDC1D8), Color(0xFFE9EAF1), Color(0xFFDDDFEA), Color(0xFFC9CCDD),
            Color(0xFF5D9AFE), screen)
    }
}

/** The outline and its two keys, drawn over whatever the screen holds. */
internal fun DrawScope.drawPhoneFrame(pal: SkeuoPalette) {
    val keyW = 2.2f
    val x = PHONE_W + PHONE_STROKE / 2f - keyW / 2f
    for ((top, bottom) in listOf(37.5f to 62.5f, 70f to 84.5f)) {
        drawRoundRect(pal.frame, Offset(x, top), Size(keyW + 0.4f, bottom - top), CornerRadius(1.1f))
    }
    drawRoundRect(pal.frame, Offset.Zero, Size(PHONE_W, PHONE_H), CornerRadius(PHONE_CORNER),
        style = Stroke(PHONE_STROKE))
}

/** The screen's fill, under the content: the card's colour, as in the videos. */
internal fun DrawScope.drawPhoneScreen(pal: SkeuoPalette) {
    drawRoundRect(pal.screen, Offset.Zero, Size(PHONE_W, PHONE_H), CornerRadius(PHONE_CORNER))
}

/** A wireframe row: its pill, a square where the icon goes, and a bar where the words go. */
internal fun DrawScope.drawRowPill(pal: SkeuoPalette, x: Float, y: Float, w: Float, h: Float,
                                   on: Float = 0f, icon: Boolean = true, bar: Float = 0.62f,
                                   alpha: Float = 1f) {
    if (alpha <= 0.003f) return
    val fill = androidx.compose.ui.graphics.lerp(pal.pill, pal.pillOn, on.coerceIn(0f, 1f))
    drawRoundRect(fill, Offset(x, y), Size(w, h), CornerRadius(h * 0.3f), alpha = alpha)
    val cy = y + h / 2f
    var tx = x + h * 0.32f
    if (icon) {
        val s = h * 0.3f
        drawRoundRect(pal.line, Offset(tx, cy - s / 2f), Size(s, s), CornerRadius(s * 0.22f), alpha = alpha)
        tx += s + h * 0.22f
    }
    val bh = h * 0.1f
    drawRoundRect(pal.line, Offset(tx, cy - bh / 2f), Size((x + w - tx) * bar, bh), CornerRadius(bh / 2f),
        alpha = alpha)
}

/** A check drawn in one stroke, `t` of the way along it. */
internal fun DrawScope.drawCheck(color: Color, cx: Float, cy: Float, s: Float, t: Float, width: Float) {
    if (t <= 0.001f) return
    val a = Offset(cx - s * 0.5f, cy)
    val b = Offset(cx - s * 0.12f, cy + s * 0.38f)
    val c = Offset(cx + s * 0.55f, cy - s * 0.4f)
    val first = 0.35f
    val path = Path().apply {
        moveTo(a.x, a.y)
        if (t < first) {
            val k = t / first
            lineTo(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k)
        } else {
            lineTo(b.x, b.y)
            val k = (t - first) / (1f - first)
            lineTo(b.x + (c.x - b.x) * k, b.y + (c.y - b.y) * k)
        }
    }
    drawPath(path, color, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

// ---- the finger

private val ARRIVE_X = CubicBezierEasing(0.15f, 0.7f, 0.3f, 1f)
private val ARRIVE_Y = CubicBezierEasing(0.45f, 0f, 0.25f, 1f)
private val SLIDE = CubicBezierEasing(0.3f, 0f, 0.2f, 1f)

/**
 * The blue dot, in the caller's units. It arrives, presses, may slide while pressed, and lifts;
 * each is a suspend call, so a scene reads as the gesture it is.
 */
internal class Finger {
    val x = Animatable(0f)
    val y = Animatable(0f)
    /** The dot's own opacity. */
    val shown = Animatable(0f)
    /** The pressed disc round it, 0 up to 1 fully down. */
    val halo = Animatable(0f)
    /** The ring a lift sends out, 0 to 1 over its run, from where the lift was. */
    val ring = Animatable(1f)
    var ringX = 0f
    var ringY = 0f

    suspend fun reset() {
        shown.snapTo(0f); halo.snapTo(0f); ring.snapTo(1f)
    }

    /** In from off the phone, along a curve - x settles before y - slowing as it lands. */
    suspend fun arrive(tx: Float, ty: Float, fromDx: Float = 34f, fromDy: Float = -30f, ms: Int = 560) =
        coroutineScope {
            x.snapTo(tx + fromDx); y.snapTo(ty + fromDy)
            launch { shown.animateTo(1f, tween(ms / 3)) }
            launch { x.animateTo(tx, tween(ms, easing = ARRIVE_X)) }
            launch { y.animateTo(ty, tween(ms, easing = ARRIVE_Y)) }
        }

    suspend fun press(ms: Int = 150) = halo.animateTo(1f, tween(ms))

    /** Moves while pressed; `alongside` runs with it, for whatever the drag is pulling. */
    suspend fun slide(tx: Float, ty: Float, ms: Int, alongside: suspend () -> Unit = {}) = coroutineScope {
        launch { x.animateTo(tx, tween(ms, easing = SLIDE)) }
        launch { y.animateTo(ty, tween(ms, easing = SLIDE)) }
        launch { alongside() }
    }

    /** Up: the disc and the dot fade where they are, and a ring runs out from under them. */
    suspend fun lift(ripple: Boolean = true) = coroutineScope {
        if (ripple) {
            ringX = x.value; ringY = y.value
            ring.snapTo(0f)
            launch { ring.animateTo(1f, tween(620, easing = CubicBezierEasing(0.2f, 0.6f, 0.3f, 1f))) }
        }
        launch { halo.animateTo(0f, tween(320)) }
        launch { shown.animateTo(0f, tween(300)) }
    }
}

/** The finger at `map`'s idea of where its units are on screen. */
internal fun DrawScope.drawFinger(f: Finger, pal: SkeuoPalette, map: (Float, Float) -> Offset) {
    val r = 6.dp.toPx()
    val rp = f.ring.value
    if (rp < 0.999f) {
        val at = map(f.ringX, f.ringY)
        val fade = 1f - rp
        drawCircle(pal.accent.copy(alpha = 0.45f * fade), r * (1.3f + 2.4f * rp), at,
            style = Stroke((0.9.dp.toPx() + r * 0.35f * fade)))
    }
    val a = f.shown.value
    val h = f.halo.value
    if (a <= 0.003f && h <= 0.003f) return
    val at = map(f.x.value, f.y.value)
    if (h > 0.003f) drawCircle(pal.accent.copy(alpha = 0.2f * h), r * (1f + 0.95f * h), at)
    if (a > 0.003f) drawCircle(pal.accent.copy(alpha = a), r * (1f - 0.1f * h), at)
}

// ---- the confetti

private val CONFETTI = listOf(
    Color(0xFFF2317A), Color(0xFF3CC8F0), Color(0xFF3B6CF6), Color(0xFF8A4DE8),
    Color(0xFFC8E03C), Color(0xFFFF9A3C), Color(0xFFE8413C), Color(0xFF26C6A0),
)

/**
 * A burst like the one v01 ends on: dots flung mostly sideways out of a band, drifting and
 * shrinking away. Seeded, so the same scene bursts the same way every loop.
 */
internal class Burst(seed: Int, count: Int = 40) {
    val t = Animatable(1f)

    private class Bit(val angle: Float, val speed: Float, val size: Float, val color: Color, val lift: Float)

    private val bits = Random(seed).let { rnd ->
        List(count) {
            Bit(rnd.nextFloat() * 6.2832f, 0.35f + rnd.nextFloat() * 0.65f, 0.5f + rnd.nextFloat() * 0.8f,
                CONFETTI[rnd.nextInt(CONFETTI.size)], rnd.nextFloat())
        }
    }

    suspend fun fire(ms: Int = 1100) {
        t.snapTo(0f)
        t.animateTo(1f, tween(ms, easing = CubicBezierEasing(0.1f, 0.7f, 0.3f, 1f)))
    }

    /** Out of (cx, cy), `reachX` to the sides and `reachY` up and down, dots `dot` across. */
    fun draw(scope: DrawScope, cx: Float, cy: Float, reachX: Float, reachY: Float, dot: Float) {
        val p = t.value
        if (p >= 0.999f) return
        val fade = 1f - ((p - 0.55f) / 0.45f).coerceIn(0f, 1f)
        for (b in bits) {
            val d = b.speed * p
            val x = cx + cos(b.angle) * d * reachX
            val y = cy + sin(b.angle) * d * reachY - b.lift * reachY * 0.5f * p
            scope.drawCircle(b.color.copy(alpha = fade), dot * b.size * (1f - 0.35f * p), Offset(x, y))
        }
    }
}

// ---- the camera

private val CAMERA_EASE = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

/**
 * Where a demonstration is looking: a point on the phone, in its units, held at the middle of the
 * picture, and how far in from the whole phone. Moved to the moment that matters and back out,
 * the way a narrated video would - the finger stays its own size throughout (see drawFinger).
 */
internal class DemoCamera {
    val cx = Animatable(PHONE_W / 2f)
    val cy = Animatable(PHONE_H / 2f)
    /** 1 the whole phone; 2 twice as close. */
    val k = Animatable(1f)

    suspend fun reset() {
        cx.snapTo(PHONE_W / 2f); cy.snapTo(PHONE_H / 2f); k.snapTo(1f)
    }

    suspend fun focus(x: Float, y: Float, zoom: Float, ms: Int = 650) = coroutineScope {
        launch { cx.animateTo(x, tween(ms, easing = CAMERA_EASE)) }
        launch { cy.animateTo(y, tween(ms, easing = CAMERA_EASE)) }
        launch { k.animateTo(zoom, tween(ms, easing = CAMERA_EASE)) }
    }

    suspend fun wide(ms: Int = 650) = focus(PHONE_W / 2f, PHONE_H / 2f, 1f, ms)

    /**
     * Scale (pixels per unit) and the offset of the phone's origin, for a picture this size.
     *
     * The focus is held at the middle unless that would take the phone's bottom edge - its stroke
     * too - lower than it sits with the whole phone in view; then the phone is lifted until it
     * does. The point, the zoom and the way there are each eased on their own, so half way into
     * a close-up of the island row the zoom was well on and the point still high, and the edge
     * dropped out of the picture for the length of the move (user, 2026-09-30).
     */
    fun view(width: Float, height: Float, fill: Float = 0.9f): Pair<Float, Offset> {
        val s = height * fill / PHONE_H * k.value
        var oy = height / 2f - cy.value * s
        val edge = oy + (PHONE_H + PHONE_STROKE / 2f) * s
        val floor = height / 2f + (PHONE_H / 2f + PHONE_STROKE / 2f) * (height * fill / PHONE_H)
        if (edge > floor) oy -= edge - floor
        return s to Offset(width / 2f - cx.value * s, oy)
    }
}
