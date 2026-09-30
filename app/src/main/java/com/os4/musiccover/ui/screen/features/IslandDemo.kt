package com.os4.musiccover.ui.screen.features

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.os4.musiccover.ui.util.isInDarkTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.pow

/**
 * The lock screen islands, shown rather than described: a phone drawn in outline, and on it
 * what the row of islands really does - switching under a swipe, a notification growing in and
 * opening into its card, the media card folding into the pill.
 *
 * Drawn the way HyperOS draws its 趣味拟物 haptics cards (see SkeuoKit): the phone as a line,
 * the screen as grey wireframe, a blue dot for the finger that presses with a halo and lifts with
 * a ring, and confetti where something lands. The motion is ours: every spring is the one the
 * module runs on the lock screen (MiniPlayerRuntime: CHANGE, APPEAR, HIDDEN, SHOW, the card
 * morph's), so what plays here is what the phone does, at its own pace.
 */
@Composable
fun IslandDemo(modifier: Modifier = Modifier) {
    DemoPager(DEMO_PAGES, modifier) { page, playing, done -> DemoPage(page, playing, done) }
}

private val DEMO_PAGES = listOf(
    DemoText("左右滑动切换", "同时有多个岛时，在胶囊上左右滑动切换。右边的小岛是下一个，滑过去它就长成大岛。"),
    DemoText("展开与收回", "新通知从左往右展开成岛。点按岛展开成通知卡片，在卡片上下滑收回岛里。"),
    DemoText("媒体卡片与胶囊", "在媒体卡片上下滑，收成底部的胶囊，锁屏更干净；上滑胶囊，展开回媒体卡片。"),
)

@Composable
private fun DemoPage(page: Int, playing: Boolean, onDone: () -> Unit) {
    val pal = skeuoPalette(isInDarkTheme())
    val measurer = rememberTextMeasurer()
    val clockSp = with(LocalDensity.current) { CLOCK_UNITS.toSp() }
    val scene = remember(page) { Scene() }
    LaunchedEffect(playing) {
        scene.reset(page)
        if (!playing) return@LaunchedEffect
        when (page) {
            0 -> scene.playSwitch()
            1 -> scene.playNotification()
            else -> scene.playMediaCard()
        }
        onDone()
    }
    Canvas(Modifier.fillMaxWidth().height(236.dp).clipToBounds()) {
        drawScene(scene, page, pal, measurer, clockSp)
    }
}

// ---- the phone, in SkeuoKit's units: 100 wide, as tall as the phone's screen is for its width

private const val PW = PHONE_W
private const val PH = PHONE_H
private const val RY = 196f
private const val ISLAND_H = 13f
private const val CLOCK_UNITS = 15f
private val DISC_X = floatArrayOf(12.5f, 87.5f)

/** A box in the phone's units. */
private data class B(val x: Float, val y: Float, val w: Float, val h: Float) {
    val cx get() = x + w / 2f
    val cy get() = y + h / 2f
    val right get() = x + w
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun lerpB(a: B, b: B, t: Float) = B(lerp(a.x, b.x, t), lerp(a.y, b.y, t), lerp(a.w, b.w, t), lerp(a.h, b.h, t))
private fun circle(cx: Float, cy: Float, d: Float) = B(cx - d / 2f, cy - d / 2f, d, d)
private fun smooth(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** The pill alone in the row, and beside a small island: the row's group stays centred. */
private val PILL = B(23f, RY - ISLAND_H / 2f, 54f, ISLAND_H)
private val PILL_SMALL = B(23f, RY - ISLAND_H / 2f, 38.5f, ISLAND_H)
private val SMALL = circle(PILL_SMALL.right + 2.5f + ISLAND_H / 2f, RY, ISLAND_H)
private val NOTE_CARD = B(7f, 150f, 86f, 26f)
private val MEDIA_CARD = B(7f, 124f, 86f, 54f)

/** Which island: only its mark tells them apart - the one colour on the screen is the finger's. */
private enum class Kind { MUSIC, TIMER, MESSAGE }

// ---- motion: the module's own springs, response (s) and damping as MiniPlayerRuntime has them

private fun jelly(response: Float, damping: Float): AnimationSpec<Float> =
    spring(dampingRatio = damping, stiffness = (2.0 * PI / response).pow(2).toFloat(), visibilityThreshold = 0.0005f)

private val CHANGE = jelly(0.4f, 0.82f)
private val APPEAR = jelly(0.5f, 0.7f)
private val HIDDEN = jelly(0.2f, 0.99f)
private val SHOW = jelly(0.35f, 0.95f)
private val MORPH = jelly(0.38f, 0.86f)
private val GESTURE = CubicBezierEasing(0.3f, 0f, 0.2f, 1f)

private class Scene {
    /** Where the page is looking: the whole phone, or close in on what is happening. */
    val cam = DemoCamera()
    val finger = Finger()
    /** The finger's pull on the pill, -1..1: it narrows and leans as islandDrag has it. */
    val drag = Animatable(0f)
    /** Page one: which switch is running (0 none yet), and each part's spring. */
    var stage by mutableIntStateOf(0)
    val sw = Animatable(0f)
    val ghost = Animatable(0f)
    val emerge = Animatable(0f)
    val contentIn = Animatable(0f)
    val hide = Animatable(0f)
    /** Pages two and three: the pill's presence (0 gone, 1 there), its press, the morph (1 card). */
    val present = Animatable(0f)
    var leaving by mutableIntStateOf(0)
    val press = Animatable(0f)
    val morph = Animatable(0f)
    /** Page two: a new island's arrival, one ring out of it. Page three: the pill landing. */
    val ping = Animatable(1f)
    val burst = Burst(seed = 11)

    suspend fun reset(page: Int) {
        cam.reset()
        finger.reset(); drag.snapTo(0f)
        stage = 0
        for (a in listOf(sw, ghost, emerge, contentIn, hide)) a.snapTo(0f)
        present.snapTo(if (page == 1) 0f else 1f)
        leaving = 0
        press.snapTo(0f)
        morph.snapTo(if (page == 2) 1f else 0f)
        ping.snapTo(1f)
        burst.t.snapTo(1f)
    }

    /** Page one: two islands and a third behind them, switched left and back right. */
    suspend fun playSwitch() {
        delay(500)
        cam.focus(50f, 178f, 2.6f, 700)
        delay(150)
        finger.arrive(52f, RY, fromDx = 26f, fromDy = -30f)
        finger.press()
        finger.slide(30f, RY, 380) { drag.animateTo(1f, tween(380, easing = GESTURE)) }
        // SWAP_NEXT: the small island grows into the pill on CHANGE, the old one shrinks into the
        // middle on SHOW, the next slides out from under the pill's end on APPEAR.
        stage = 1
        coroutineScope {
            launch { finger.lift() }
            launch { drag.animateTo(0f, CHANGE) }
            launch { sw.animateTo(1f, CHANGE) }
            launch { ghost.animateTo(1f, SHOW) }
            launch { emerge.animateTo(1f, APPEAR) }
        }
        delay(500)
        finger.arrive(36f, RY, fromDx = -24f, fromDy = -30f)
        finger.press()
        finger.slide(58f, RY, 380) { drag.animateTo(-1f, tween(380, easing = GESTURE)) }
        // SWAP_PREV: back out of the middle on APPEAR, the content 100ms late on HIDDEN; the big
        // one to the small place on CHANGE; the small one into hiding where it is.
        stage = 2
        sw.snapTo(0f)
        coroutineScope {
            launch { finger.lift() }
            launch { drag.animateTo(0f, CHANGE) }
            launch { sw.animateTo(1f, APPEAR) }
            launch { delay(100); contentIn.animateTo(1f, HIDDEN) }
            launch { hide.animateTo(1f, HIDDEN) }
        }
        delay(900)
        cam.wide(700)
        delay(900)
    }

    /** Page two: a notification comes up, opens into its card, is pulled home, and goes. */
    suspend fun playNotification() {
        delay(300)
        cam.focus(50f, 180f, 2.3f, 600)
        coroutineScope {
            launch { present.animateTo(1f, CHANGE) }
            launch { delay(120); ping.snapTo(0f); ping.animateTo(1f, tween(700)) }
        }
        delay(300)
        finger.arrive(50f, RY)
        finger.press(110)
        press.animateTo(1f, tween(120))
        coroutineScope {
            launch { press.animateTo(0f, CHANGE) }
            launch { finger.lift() }
            launch { morph.animateTo(1f, MORPH) }
            launch { cam.focus(50f, 172f, 2f, 520) }
        }
        delay(700)
        finger.arrive(50f, NOTE_CARD.cy, fromDx = 30f, fromDy = -20f)
        finger.press()
        finger.slide(50f, NOTE_CARD.cy + 18f, 340) { morph.animateTo(0.72f, tween(340, easing = GESTURE)) }
        coroutineScope {
            launch { finger.lift(ripple = false) }
            launch { morph.animateTo(0f, MORPH) }
        }
        delay(700)
        leaving = 1
        present.animateTo(0f, CHANGE)
        delay(300)
        cam.wide(700)
        delay(300)
    }

    /** Page three: the media card pulled down into the pill, and the pill pulled back up. */
    suspend fun playMediaCard() {
        delay(200)
        cam.focus(50f, 162f, 1.9f, 600)
        finger.arrive(50f, MEDIA_CARD.cy)
        finger.press()
        finger.slide(50f, MEDIA_CARD.cy + 20f, 360) { morph.animateTo(0.76f, tween(360, easing = GESTURE)) }
        coroutineScope {
            launch { finger.lift(ripple = false) }
            launch { morph.animateTo(0f, MORPH) }
            launch { cam.focus(50f, 182f, 2.3f, 520) }
            // The lock screen just got cleaner: that is the moment worth a burst.
            launch { delay(220); burst.fire() }
        }
        delay(700)
        finger.arrive(50f, RY, fromDx = 30f, fromDy = 10f)
        finger.press()
        finger.slide(50f, RY - 18f, 340) { morph.animateTo(0.24f, tween(340, easing = GESTURE)) }
        coroutineScope {
            launch { finger.lift(ripple = false) }
            launch { morph.animateTo(1f, MORPH) }
            launch { cam.focus(50f, 162f, 1.9f, 520) }
        }
        delay(500)
        cam.wide(700)
        delay(300)
    }
}

// ---- drawing

private class Camera(val s: Float, val o: Offset) {
    fun map(x: Float, y: Float) = Offset(o.x + x * s, o.y + y * s)
}

private fun DrawScope.drawScene(sc: Scene, page: Int, pal: SkeuoPalette, measurer: TextMeasurer, clockSp: TextUnit) {
    val (camS, camO) = sc.cam.view(size.width, size.height)
    val cam = Camera(camS, camO)
    withTransform({
        translate(cam.o.x, cam.o.y)
        scale(cam.s, cam.s, Offset.Zero)
    }) {
        drawPhoneScreen(pal)
        val screen = Path().apply { addRoundRect(RoundRect(0f, 0f, PW, PH, CornerRadius(PHONE_CORNER))) }
        clipPath(screen) {
            // The lock screen as wireframe: the clock in the outline's grey, a bar for the date.
            val clock = measurer.measure("19:30", TextStyle(color = pal.frame, fontSize = clockSp,
                fontWeight = FontWeight.Bold))
            drawText(clock, topLeft = Offset(PW / 2f - clock.size.width / 2f, 18f))
            drawRoundRect(pal.pill, Offset(PW / 2f - 13f, 18f + clock.size.height + 2f), Size(26f, 2.6f),
                CornerRadius(1.3f))
            for (x in DISC_X) {
                drawCircle(pal.pill, ISLAND_H / 2f, Offset(x, RY))
                drawCircle(pal.line, ISLAND_H * 0.16f, Offset(x, RY), style = Stroke(0.9f))
            }
            when (page) {
                0 -> drawSwitch(sc, pal)
                1 -> drawNotification(sc, pal)
                else -> drawMedia(sc, pal)
            }
            val pg = sc.ping.value
            if (pg < 0.999f) {
                drawRoundRect(pal.accent.copy(alpha = 0.5f * (1f - pg)),
                    Offset(PILL.x - 5f * pg, PILL.y - 5f * pg), Size(PILL.w + 10f * pg, PILL.h + 10f * pg),
                    CornerRadius(PILL.h / 2f + 5f * pg), style = Stroke(1.2f * (1f - pg) + 0.3f))
            }
            sc.burst.draw(this, PILL.cx, RY, 46f, 16f, 1.1f)
        }
        drawPhoneFrame(pal)
    }
    drawFinger(sc.finger, pal) { x, y -> cam.map(x, y) }
}

/** An island: its pill, its picture, and its words where there is room for them. */
private fun DrawScope.drawIsland(pal: SkeuoPalette, kind: Kind, box: B, glass: Float = 1f, content: Float = 1f,
                                 scale: Float = 1f, playButton: Boolean = kind == Kind.MUSIC,
                                 icon: Boolean = true) {
    if (glass <= 0.001f && content <= 0.001f) return
    val b = if (scale == 1f) box else B(box.cx - box.w * scale / 2f, box.cy - box.h * scale / 2f, box.w * scale, box.h * scale)
    val r = b.h / 2f
    drawRoundRect(pal.pill, Offset(b.x, b.y), Size(b.w, b.h), CornerRadius(r), alpha = glass)
    // 1 a bare circle (the small island), 0 the pill with room for its words.
    val round = 1f - ((b.w - b.h) / (b.h * 0.8f)).coerceIn(0f, 1f)
    val inset = b.h * 0.17f
    val side = b.h - inset * 2f
    val ix = lerp(b.x + inset, b.cx - side / 2f, round)
    val a = content.coerceIn(0f, 1f)
    val clip = Path().apply { addRoundRect(RoundRect(b.x, b.y, b.right, b.y + b.h, CornerRadius(r))) }
    clipPath(clip) {
        if (icon) {
            val ia = a.coerceAtLeast(glass * 0.9f)
            // Round, as the pill's own artwork is (MiniPlayerView clips it to a circle).
            drawCircle(pal.line, side / 2f, Offset(ix + side / 2f, b.cy), alpha = ia)
            drawGlyph(pal, kind, ix + side / 2f, b.cy, side, ia)
        }
        val words = a * (1f - round)
        if (words > 0.01f) {
            val tx = ix + side + inset * 1.4f
            val room = b.right - tx - (if (playButton) b.h * 0.9f else inset * 2f)
            if (room > 1f) {
                drawRoundRect(pal.line, Offset(tx, b.cy - 2.6f), Size(room * 0.62f, 1.9f), CornerRadius(0.95f),
                    alpha = words)
                drawRoundRect(pal.line, Offset(tx, b.cy + 0.9f), Size(room * 0.42f, 1.6f), CornerRadius(0.8f),
                    alpha = 0.6f * words)
            }
            if (playButton) {
                val px = b.right - b.h * 0.55f
                val tri = Path().apply {
                    moveTo(px - 1.3f, b.cy - 1.9f); lineTo(px + 1.9f, b.cy); lineTo(px - 1.3f, b.cy + 1.9f); close()
                }
                drawPath(tri, pal.frame, alpha = words)
            }
        }
    }
}

/** The picture's own mark, knocked out of its square: a note, a clock face, a speech bubble. */
private fun DrawScope.drawGlyph(pal: SkeuoPalette, kind: Kind, cx: Float, cy: Float, side: Float, alpha: Float) {
    val c = pal.screen.copy(alpha = alpha)
    val u = side / 10f
    when (kind) {
        Kind.MUSIC -> {
            drawCircle(c, 1.4f * u, Offset(cx - 1f * u, cy + 2f * u))
            drawLine(c, Offset(cx + 0.3f * u, cy + 2f * u), Offset(cx + 0.3f * u, cy - 3f * u), 0.9f * u)
            drawLine(c, Offset(cx + 0.3f * u, cy - 3f * u), Offset(cx + 2.4f * u, cy - 2.2f * u), 0.9f * u, StrokeCap.Round)
        }
        Kind.TIMER -> {
            drawCircle(c, 3.1f * u, Offset(cx, cy + 0.3f * u), style = Stroke(0.9f * u))
            drawLine(c, Offset(cx, cy + 0.3f * u), Offset(cx, cy - 1.6f * u), 0.9f * u, StrokeCap.Round)
            drawLine(c, Offset(cx, cy + 0.3f * u), Offset(cx + 1.3f * u, cy + 1.1f * u), 0.9f * u, StrokeCap.Round)
        }
        Kind.MESSAGE -> {
            drawRoundRect(c, Offset(cx - 3f * u, cy - 2.4f * u), Size(6f * u, 4.4f * u), CornerRadius(1.4f * u))
            val tail = Path().apply {
                moveTo(cx - 1.6f * u, cy + 1.8f * u); lineTo(cx - 2.2f * u, cy + 3.4f * u); lineTo(cx - 0.2f * u, cy + 1.9f * u); close()
            }
            drawPath(tail, c)
        }
    }
}

/** The finger's pull on the island it is on: narrower by up to 7%, leaning the way it goes. */
private fun pulled(b: B, d: Float): B {
    val w = b.w * (1f - 0.07f * kotlin.math.abs(d))
    return B(b.cx - w / 2f - 1.6f * d, b.y, w, b.h)
}

private fun DrawScope.drawSwitch(sc: Scene, pal: SkeuoPalette) {
    val d = sc.drag.value
    val mid = circle(PILL_SMALL.cx, RY, ISLAND_H * 0.6f)
    when (sc.stage) {
        0 -> {
            drawIsland(pal, Kind.TIMER, SMALL)
            drawIsland(pal, Kind.MUSIC, pulled(PILL_SMALL, d))
        }
        1 -> {
            val g = sc.ghost.value
            drawIsland(pal, Kind.MUSIC, lerpB(PILL_SMALL, mid, g), glass = 1f - g, content = 1f - smooth(0f, 0.5f, g))
            val e = sc.emerge.value
            val side = lerp(ISLAND_H * 0.6f, ISLAND_H, e)
            drawIsland(pal, Kind.MESSAGE, circle(lerp(PILL_SMALL.right - ISLAND_H / 2f, SMALL.cx, e), RY, side),
                glass = smooth(0f, 0.12f, e), content = smooth(0.15f, 0.7f, e))
            val s = sc.sw.value
            drawIsland(pal, Kind.TIMER, pulled(lerpB(SMALL, PILL_SMALL, s), d), content = smooth(0.35f, 0.9f, s))
        }
        else -> {
            val h = sc.hide.value
            drawIsland(pal, Kind.MESSAGE, SMALL, glass = 1f - h, content = 1f - h, scale = lerp(1f, 0.6f, h))
            val s = sc.sw.value
            drawIsland(pal, Kind.TIMER, lerpB(PILL_SMALL, SMALL, s))
            drawIsland(pal, Kind.MUSIC, pulled(lerpB(mid, PILL_SMALL, s), d), glass = smooth(0f, 0.3f, s),
                content = sc.contentIn.value)
        }
    }
}

/** An island opening into its card: the frame goes to the card's, the picture to the card's. */
private fun DrawScope.drawOpening(pal: SkeuoPalette, kind: Kind, pill: B, card: B, cardRadius: Float, m: Float,
                                  glass: Float, pillContent: Float, art: B, drawCard: DrawScope.(B, Float) -> Unit) {
    val box = lerpB(pill, card, m)
    val r = lerp(pill.h / 2f, cardRadius, smooth(0f, 0.6f, m))
    drawRoundRect(pal.pill, Offset(box.x, box.y), Size(box.w, box.h), CornerRadius(r), alpha = glass)
    val clip = Path().apply { addRoundRect(RoundRect(box.x, box.y, box.right, box.y + box.h, CornerRadius(r))) }
    clipPath(clip) {
        // The pill's words out over the first half, the card's in over the second.
        val out = pillContent * (1f - smooth(0f, 0.45f, m))
        if (out > 0.01f) drawIsland(pal, kind, box, glass = 0f, content = out, icon = false)
        val inn = smooth(0.55f, 1f, m)
        if (inn > 0.01f) drawCard(box, inn)
        // The picture travels between its two places, whole, all the way.
        val inset = pill.h * 0.17f
        val icon = pill.h - inset * 2f
        val from = B(box.x + inset, box.cy - icon / 2f, icon, icon)
        val a = lerpB(from, B(box.x + (art.x - card.x) * (box.w / card.w), box.y + (art.y - card.y) * (box.h / card.h),
            lerp(icon, art.w, m), lerp(icon, art.h, m)), smooth(0.2f, 1f, m))
        val alpha = glass.coerceAtLeast(pillContent)
        // A circle in the pill, the card's rounded square once it is there.
        drawRoundRect(pal.line, Offset(a.x, a.y), Size(a.w, a.h),
            CornerRadius(lerp(a.w / 2f, a.w * 0.26f, smooth(0.2f, 1f, m))), alpha = alpha)
        drawGlyph(pal, kind, a.cx, a.cy, a.w, alpha)
    }
}

private fun DrawScope.drawNotification(sc: Scene, pal: SkeuoPalette) {
    val p = sc.present.value
    if (p <= 0f && sc.leaving == 0 && sc.morph.value <= 0f) return
    // Out of a circle at its left end, rightward; going, back into it, the circle fading last.
    val pill = B(PILL.x, PILL.y, lerp(ISLAND_H, PILL.w, p).coerceAtLeast(ISLAND_H), ISLAND_H)
    val glass = if (sc.leaving == 1) smooth(0f, 0.15f, p) else 1f
    val pressed = 1f - 0.05f * sc.press.value
    val shown = if (pressed == 1f) pill else B(pill.cx - pill.w * pressed / 2f, pill.cy - pill.h * pressed / 2f, pill.w * pressed, pill.h * pressed)
    val art = B(NOTE_CARD.x + 3.5f, NOTE_CARD.y + 3.5f, 9f, 9f)
    drawOpening(pal, Kind.MESSAGE, shown, NOTE_CARD, 6f, sc.morph.value, glass, smooth(0.35f, 0.9f, p), art) { box, a ->
        val k = box.w / NOTE_CARD.w
        val x = box.x + 16f * k
        drawRoundRect(pal.line, Offset(x, box.y + 4.4f), Size(24f * k, 2.2f), CornerRadius(1.1f), alpha = a)
        drawRoundRect(pal.line, Offset(x, box.y + 9.8f), Size(54f * k, 1.8f), CornerRadius(0.9f), alpha = 0.7f * a)
        drawRoundRect(pal.line, Offset(x, box.y + 14.6f), Size(38f * k, 1.8f), CornerRadius(0.9f), alpha = 0.7f * a)
        drawRoundRect(pal.line, Offset(box.right - 12f * k, box.y + 4.6f), Size(8f * k, 1.6f), CornerRadius(0.8f),
            alpha = 0.6f * a)
    }
}

private fun DrawScope.drawMedia(sc: Scene, pal: SkeuoPalette) {
    val art = B(MEDIA_CARD.x + 4.5f, MEDIA_CARD.y + 4.5f, 17f, 17f)
    drawOpening(pal, Kind.MUSIC, PILL, MEDIA_CARD, 7f, sc.morph.value, 1f, 1f, art) { box, a ->
        val k = box.w / MEDIA_CARD.w
        val v = box.h / MEDIA_CARD.h
        val x = box.x + 26f * k
        drawRoundRect(pal.line, Offset(x, box.y + 8f * v), Size(30f * k, 2.4f), CornerRadius(1.2f), alpha = a)
        drawRoundRect(pal.line, Offset(x, box.y + 14f * v), Size(20f * k, 1.9f), CornerRadius(0.95f), alpha = 0.7f * a)
        // The seek bar - the one blue on the card, it is what is playing - and the controls under it.
        val barY = box.y + 32f * v
        drawRoundRect(pal.line, Offset(box.x + 5f * k, barY), Size(76f * k, 1.4f), CornerRadius(0.7f), alpha = 0.6f * a)
        drawRoundRect(pal.accent, Offset(box.x + 5f * k, barY), Size(32f * k, 1.4f), CornerRadius(0.7f), alpha = a)
        val cy = box.y + 44f * v
        for ((i, cx) in listOf(30f, 50f, 70f).withIndex()) {
            val px = box.x + cx * k
            val s = if (i == 1) 2.6f else 1.8f
            val tri = Path().apply {
                if (i == 0) { moveTo(px + s, cy - s); lineTo(px - s, cy); lineTo(px + s, cy + s) }
                else { moveTo(px - s * 0.8f, cy - s); lineTo(px + s * 1.1f, cy); lineTo(px - s * 0.8f, cy + s) }
                close()
            }
            drawPath(tri, pal.frame, alpha = a)
        }
    }
}
