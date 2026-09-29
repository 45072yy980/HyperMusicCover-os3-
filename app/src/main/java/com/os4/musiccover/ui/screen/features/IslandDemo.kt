package com.os4.musiccover.ui.screen.features

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.os4.musiccover.ui.util.isInDarkTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow

/**
 * The lock screen islands, shown rather than described: a phone drawn in outline, and on it
 * what the row of islands really does - switching under a swipe, a notification growing in and
 * opening into its card, the media card folding into the pill. After the demonstration cards on
 * HyperOS's own 锁屏通知 settings page; the motion is ours. Every spring is the one the module
 * runs on the lock screen (MiniPlayerRuntime: CHANGE, APPEAR, HIDDEN, SHOW, the card morph's),
 * so what plays here is what the phone does, at its own pace.
 */
@Composable
fun IslandDemo(modifier: Modifier = Modifier) {
    // Three pages and no more, each on to the next when it has played; the last back to the first.
    val n = DEMO_PAGES.size
    val pager = rememberPagerState { n }
    val scope = rememberCoroutineScope()
    Column(modifier) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth(), beyondViewportPageCount = 1) { page ->
            val playing = pager.settledPage == page && !pager.isScrollInProgress
            DemoPage(page, playing) {
                scope.launch { pager.animateScrollToPage((page + 1) % n) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.Center) {
            val on = MiuixTheme.colorScheme.onSurface
            repeat(DEMO_PAGES.size) { i ->
                Box(Modifier.padding(horizontal = 3.dp).size(6.dp)
                    .background(on.copy(alpha = if (i == pager.currentPage) 0.75f else 0.2f), CircleShape))
            }
        }
    }
}

private class DemoText(val title: String, val body: String)

private val DEMO_PAGES = listOf(
    DemoText("左右滑动切换", "同时有多个岛时，在胶囊上左右滑动切换。右边的小岛是下一个，滑过去它就长成大岛。"),
    DemoText("展开与收回", "新通知从左往右展开成岛。点按岛展开成通知卡片，在卡片上下滑收回岛里。"),
    DemoText("媒体卡片与胶囊", "在媒体卡片上下滑，收成底部的胶囊，锁屏更干净；上滑胶囊，展开回媒体卡片。"),
)

@Composable
private fun DemoPage(page: Int, playing: Boolean, onDone: () -> Unit) {
    val dark = isInDarkTheme()
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
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth().height(236.dp).clipToBounds()) {
            drawScene(scene, page, dark, measurer, clockSp)
        }
        Spacer(Modifier.height(10.dp))
        Text(DEMO_PAGES[page].title, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onSurface)
        Text(DEMO_PAGES[page].body, fontSize = 13.sp, textAlign = TextAlign.Center,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(horizontal = 24.dp).padding(top = 6.dp).heightIn(min = 38.dp))
    }
}

// ---- the phone, in its own units: 100 wide, as tall as the phone's screen is for its width

private const val PW = 100f
private const val PH = 217f
private const val CORNER = 13f
private const val FRAME = 2.4f
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
private val NOTE_CARD = B(5f, 150f, 90f, 26f)
private val MEDIA_CARD = B(5f, 124f, 90f, 54f)

private enum class Kind(val a: Color, val b: Color) {
    MUSIC(Color(0xFFFF7FA3), Color(0xFF8C6BFF)),
    TIMER(Color(0xFFFFA23A), Color(0xFFFF7A1A)),
    MESSAGE(Color(0xFF3DD68C), Color(0xFF1DB871)),
}

// ---- motion: the module's own springs, response (s) and damping as MiniPlayerRuntime has them

private fun jelly(response: Float, damping: Float): AnimationSpec<Float> =
    spring(dampingRatio = damping, stiffness = (2.0 * PI / response).pow(2).toFloat(), visibilityThreshold = 0.0005f)

private val CHANGE = jelly(0.4f, 0.82f)
private val APPEAR = jelly(0.5f, 0.7f)
private val HIDDEN = jelly(0.2f, 0.99f)
private val SHOW = jelly(0.35f, 0.95f)
private val MORPH = jelly(0.38f, 0.86f)
private val GESTURE = CubicBezierEasing(0.3f, 0f, 0.2f, 1f)
private val CAMERA = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

private class Scene {
    /** 0 the whole phone, 1 close in on the row. */
    val cam = Animatable(0f)
    val tx = Animatable(0f)
    val ty = Animatable(0f)
    val touch = Animatable(0f)
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

    suspend fun reset(page: Int) {
        cam.snapTo(0f)
        touch.snapTo(0f); drag.snapTo(0f)
        stage = 0
        for (a in listOf(sw, ghost, emerge, contentIn, hide)) a.snapTo(0f)
        present.snapTo(if (page == 1) 0f else 1f)
        leaving = 0
        press.snapTo(0f)
        morph.snapTo(if (page == 2) 1f else 0f)
    }

    private suspend fun down(x: Float, y: Float) {
        tx.snapTo(x); ty.snapTo(y)
        touch.animateTo(1f, tween(140))
    }

    private suspend fun move(x: Float, y: Float, ms: Int, alongside: suspend () -> Unit = {}) = coroutineScope {
        launch { tx.animateTo(x, tween(ms, easing = GESTURE)) }
        launch { ty.animateTo(y, tween(ms, easing = GESTURE)) }
        launch { alongside() }
    }

    private suspend fun up() = touch.animateTo(0f, tween(200))

    /** Page one: two islands and a third behind them, switched left and back right. */
    suspend fun playSwitch() {
        delay(500)
        cam.animateTo(1f, tween(700, easing = CAMERA))
        delay(250)
        down(52f, RY)
        move(30f, RY, 380) { drag.animateTo(1f, tween(380, easing = GESTURE)) }
        // SWAP_NEXT: the small island grows into the pill on CHANGE, the old one shrinks into the
        // middle on SHOW, the next slides out from under the pill's end on APPEAR.
        stage = 1
        coroutineScope {
            launch { up() }
            launch { drag.animateTo(0f, CHANGE) }
            launch { sw.animateTo(1f, CHANGE) }
            launch { ghost.animateTo(1f, SHOW) }
            launch { emerge.animateTo(1f, APPEAR) }
        }
        delay(700)
        down(36f, RY)
        move(58f, RY, 380) { drag.animateTo(-1f, tween(380, easing = GESTURE)) }
        // SWAP_PREV: back out of the middle on APPEAR, the content 100ms late on HIDDEN; the big
        // one to the small place on CHANGE; the small one into hiding where it is.
        stage = 2
        sw.snapTo(0f)
        coroutineScope {
            launch { up() }
            launch { drag.animateTo(0f, CHANGE) }
            launch { sw.animateTo(1f, APPEAR) }
            launch { delay(100); contentIn.animateTo(1f, HIDDEN) }
            launch { hide.animateTo(1f, HIDDEN) }
        }
        delay(900)
        cam.animateTo(0f, tween(700, easing = CAMERA))
        delay(900)
    }

    /** Page two: a notification comes up, opens into its card, is pulled home, and goes. */
    suspend fun playNotification() {
        delay(600)
        present.animateTo(1f, CHANGE)
        delay(700)
        down(50f, RY)
        press.animateTo(1f, tween(120))
        coroutineScope {
            launch { press.animateTo(0f, CHANGE) }
            launch { up() }
            launch { morph.animateTo(1f, MORPH) }
        }
        delay(900)
        down(50f, NOTE_CARD.cy)
        move(50f, NOTE_CARD.cy + 18f, 340) { morph.animateTo(0.72f, tween(340, easing = GESTURE)) }
        coroutineScope {
            launch { up() }
            launch { morph.animateTo(0f, MORPH) }
        }
        delay(900)
        leaving = 1
        present.animateTo(0f, CHANGE)
        delay(900)
    }

    /** Page three: the media card pulled down into the pill, and the pill pulled back up. */
    suspend fun playMediaCard() {
        delay(700)
        down(50f, MEDIA_CARD.cy)
        move(50f, MEDIA_CARD.cy + 20f, 360) { morph.animateTo(0.76f, tween(360, easing = GESTURE)) }
        coroutineScope {
            launch { up() }
            launch { morph.animateTo(0f, MORPH) }
        }
        delay(1100)
        down(50f, RY)
        move(50f, RY - 18f, 340) { morph.animateTo(0.24f, tween(340, easing = GESTURE)) }
        coroutineScope {
            launch { up() }
            launch { morph.animateTo(1f, MORPH) }
        }
        delay(1000)
    }
}

// ---- drawing

private class Camera(val s: Float, val o: Offset) {
    fun map(x: Float, y: Float) = Offset(o.x + x * s, o.y + y * s)
}

private fun DrawScope.camera(c: Float): Camera {
    val s0 = size.height * 0.9f / PH
    val o0 = Offset(size.width / 2f - PW / 2f * s0, size.height * 0.05f)
    // Close in, the phone's bottom edge - its outline too - kept inside, with room under it.
    val s1 = min(size.width * 0.64f / PW, size.height * 0.9f / (PH - RY + 60f))
    val o1 = Offset(size.width / 2f - PW / 2f * s1, size.height * 0.95f - (PH + FRAME / 2f) * s1)
    return Camera(lerp(s0, s1, c), Offset(lerp(o0.x, o1.x, c), lerp(o0.y, o1.y, c)))
}

private fun DrawScope.drawScene(sc: Scene, page: Int, dark: Boolean, measurer: TextMeasurer, clockSp: TextUnit) {
    val cam = camera(sc.cam.value)
    withTransform({
        translate(cam.o.x, cam.o.y)
        scale(cam.s, cam.s, Offset.Zero)
    }) {
        val screen = Path().apply { addRoundRect(RoundRect(0f, 0f, PW, PH, CornerRadius(CORNER))) }
        clipPath(screen) {
            drawRect(Brush.verticalGradient(listOf(Color(0xFF8BC1F2), Color(0xFF6ED5D5)), 0f, PH),
                Offset.Zero, Size(PW, PH))
            val clock = measurer.measure("19:30", TextStyle(color = Color.White, fontSize = clockSp,
                fontWeight = FontWeight.Bold))
            drawText(clock, topLeft = Offset(PW / 2f - clock.size.width / 2f, 16f))
            for (x in DISC_X) drawCircle(Color.White.copy(alpha = 0.42f), ISLAND_H / 2f, Offset(x, RY))
            when (page) {
                0 -> drawSwitch(sc)
                1 -> drawNotification(sc)
                else -> drawMedia(sc)
            }
        }
        drawRoundRect(if (dark) Color(0xFF8E8E93) else Color(0xFF3A3A3C), Offset.Zero, Size(PW, PH),
            CornerRadius(CORNER), style = Stroke(FRAME))
    }
    drawTouch(sc, cam)
}

/** An island: its glass, its picture, and its words where there is room for them. */
private fun DrawScope.drawIsland(kind: Kind, box: B, glass: Float = 1f, content: Float = 1f,
                                 scale: Float = 1f, playButton: Boolean = kind == Kind.MUSIC,
                                 icon: Boolean = true) {
    if (glass <= 0.001f && content <= 0.001f) return
    val b = if (scale == 1f) box else B(box.cx - box.w * scale / 2f, box.cy - box.h * scale / 2f, box.w * scale, box.h * scale)
    val r = b.h / 2f
    drawRoundRect(Color.White.copy(alpha = 0.56f * glass), Offset(b.x, b.y), Size(b.w, b.h), CornerRadius(r))
    // 1 a bare circle (the small island), 0 the pill with room for its words.
    val round = 1f - ((b.w - b.h) / (b.h * 0.8f)).coerceIn(0f, 1f)
    val inset = b.h * 0.13f
    val side = b.h - inset * 2f
    val ix = lerp(b.x + inset, b.cx - side / 2f, round)
    val a = content.coerceIn(0f, 1f)
    val clip = Path().apply { addRoundRect(RoundRect(b.x, b.y, b.right, b.y + b.h, CornerRadius(r))) }
    clipPath(clip) {
        if (icon) {
            drawRoundRect(Brush.linearGradient(listOf(kind.a, kind.b), Offset(ix, b.y), Offset(ix + side, b.y + b.h)),
                Offset(ix, b.y + inset), Size(side, side), CornerRadius(lerp(side * 0.26f, side / 2f, round)),
                alpha = a.coerceAtLeast(glass * 0.9f))
            drawGlyph(kind, ix + side / 2f, b.cy, side, a.coerceAtLeast(glass * 0.9f))
        }
        val words = a * (1f - round)
        if (words > 0.01f) {
            val tx = ix + side + inset * 1.4f
            val room = b.right - tx - (if (playButton) b.h * 0.9f else inset * 2f)
            if (room > 1f) {
                drawRoundRect(Color(0xFF41464D).copy(alpha = 0.55f * words), Offset(tx, b.cy - 2.6f),
                    Size(room * 0.62f, 1.9f), CornerRadius(0.95f))
                drawRoundRect(Color(0xFF41464D).copy(alpha = 0.3f * words), Offset(tx, b.cy + 0.9f),
                    Size(room * 0.42f, 1.6f), CornerRadius(0.8f))
            }
            if (playButton) {
                val px = b.right - b.h * 0.55f
                val tri = Path().apply {
                    moveTo(px - 1.3f, b.cy - 1.9f); lineTo(px + 1.9f, b.cy); lineTo(px - 1.3f, b.cy + 1.9f); close()
                }
                drawPath(tri, Color(0xFF2E3238).copy(alpha = 0.75f * words))
            }
        }
    }
}

/** The picture's own mark: a note, a clock face, a speech bubble. */
private fun DrawScope.drawGlyph(kind: Kind, cx: Float, cy: Float, side: Float, alpha: Float) {
    val c = Color.White.copy(alpha = 0.95f * alpha)
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

private fun DrawScope.drawSwitch(sc: Scene) {
    val d = sc.drag.value
    val mid = circle(PILL_SMALL.cx, RY, ISLAND_H * 0.6f)
    when (sc.stage) {
        0 -> {
            drawIsland(Kind.TIMER, SMALL)
            drawIsland(Kind.MUSIC, pulled(PILL_SMALL, d))
        }
        1 -> {
            val g = sc.ghost.value
            drawIsland(Kind.MUSIC, lerpB(PILL_SMALL, mid, g), glass = 1f - g, content = 1f - smooth(0f, 0.5f, g))
            val e = sc.emerge.value
            val side = lerp(ISLAND_H * 0.6f, ISLAND_H, e)
            drawIsland(Kind.MESSAGE, circle(lerp(PILL_SMALL.right - ISLAND_H / 2f, SMALL.cx, e), RY, side),
                glass = smooth(0f, 0.12f, e), content = smooth(0.15f, 0.7f, e))
            val s = sc.sw.value
            drawIsland(Kind.TIMER, pulled(lerpB(SMALL, PILL_SMALL, s), d), content = smooth(0.35f, 0.9f, s))
        }
        else -> {
            val h = sc.hide.value
            drawIsland(Kind.MESSAGE, SMALL, glass = 1f - h, content = 1f - h, scale = lerp(1f, 0.6f, h))
            val s = sc.sw.value
            drawIsland(Kind.TIMER, lerpB(PILL_SMALL, SMALL, s))
            drawIsland(Kind.MUSIC, pulled(lerpB(mid, PILL_SMALL, s), d), glass = smooth(0f, 0.3f, s),
                content = sc.contentIn.value)
        }
    }
}

/** An island opening into its card: the frame goes to the card's, the picture to the card's. */
private fun DrawScope.drawOpening(kind: Kind, pill: B, card: B, cardRadius: Float, m: Float, glass: Float,
                                  pillContent: Float, art: B, drawCard: DrawScope.(B, Float) -> Unit) {
    val box = lerpB(pill, card, m)
    val r = lerp(pill.h / 2f, cardRadius, smooth(0f, 0.6f, m))
    drawRoundRect(Color.White.copy(alpha = 0.56f * glass), Offset(box.x, box.y), Size(box.w, box.h), CornerRadius(r))
    val clip = Path().apply { addRoundRect(RoundRect(box.x, box.y, box.right, box.y + box.h, CornerRadius(r))) }
    clipPath(clip) {
        // The pill's words out over the first half, the card's in over the second.
        val out = pillContent * (1f - smooth(0f, 0.45f, m))
        if (out > 0.01f) drawIsland(kind, box, glass = 0f, content = out, icon = false)
        val inn = smooth(0.55f, 1f, m)
        if (inn > 0.01f) drawCard(box, inn)
        // The picture travels between its two places, whole, all the way.
        val inset = pill.h * 0.13f
        val icon = pill.h - inset * 2f
        val from = B(box.x + inset, box.cy - icon / 2f, icon, icon)
        val a = lerpB(from, B(box.x + (art.x - card.x) * (box.w / card.w), box.y + (art.y - card.y) * (box.h / card.h),
            lerp(icon, art.w, m), lerp(icon, art.h, m)), smooth(0.2f, 1f, m))
        val alpha = glass.coerceAtLeast(pillContent)
        drawRoundRect(Brush.linearGradient(listOf(kind.a, kind.b), Offset(a.x, a.y), Offset(a.right, a.y + a.h)),
            Offset(a.x, a.y), Size(a.w, a.h), CornerRadius(a.w * 0.26f), alpha = alpha)
        drawGlyph(kind, a.cx, a.cy, a.w, alpha)
    }
}

private fun DrawScope.drawNotification(sc: Scene) {
    val p = sc.present.value
    if (p <= 0f && sc.leaving == 0 && sc.morph.value <= 0f) return
    // Out of a circle at its left end, rightward; going, back into it, the circle fading last.
    val pill = B(PILL.x, PILL.y, lerp(ISLAND_H, PILL.w, p).coerceAtLeast(ISLAND_H), ISLAND_H)
    val glass = if (sc.leaving == 1) smooth(0f, 0.15f, p) else 1f
    val pressed = 1f - 0.05f * sc.press.value
    val shown = if (pressed == 1f) pill else B(pill.cx - pill.w * pressed / 2f, pill.cy - pill.h * pressed / 2f, pill.w * pressed, pill.h * pressed)
    val art = B(NOTE_CARD.x + 3f, NOTE_CARD.y + 3f, 9f, 9f)
    drawOpening(Kind.MESSAGE, shown, NOTE_CARD, 6f, sc.morph.value, glass, smooth(0.35f, 0.9f, p), art) { box, a ->
        val k = box.w / NOTE_CARD.w
        val x = box.x + 15f * k
        drawRoundRect(Color(0xFF41464D).copy(alpha = 0.6f * a), Offset(x, box.y + 4.2f), Size(24f * k, 2.2f), CornerRadius(1.1f))
        drawRoundRect(Color(0xFF41464D).copy(alpha = 0.34f * a), Offset(x, box.y + 9.6f), Size(56f * k, 1.8f), CornerRadius(0.9f))
        drawRoundRect(Color(0xFF41464D).copy(alpha = 0.34f * a), Offset(x, box.y + 14.4f), Size(40f * k, 1.8f), CornerRadius(0.9f))
        drawRoundRect(Color(0xFF41464D).copy(alpha = 0.25f * a), Offset(box.right - 12f * k, box.y + 4.4f), Size(8f * k, 1.6f), CornerRadius(0.8f))
    }
}

private fun DrawScope.drawMedia(sc: Scene) {
    val art = B(MEDIA_CARD.x + 4.5f, MEDIA_CARD.y + 4.5f, 17f, 17f)
    drawOpening(Kind.MUSIC, PILL, MEDIA_CARD, 7f, sc.morph.value, 1f, 1f, art) { box, a ->
        val k = box.w / MEDIA_CARD.w
        val v = box.h / MEDIA_CARD.h
        val x = box.x + 26f * k
        val ink = Color(0xFF41464D)
        drawRoundRect(ink.copy(alpha = 0.6f * a), Offset(x, box.y + 8f * v), Size(30f * k, 2.4f), CornerRadius(1.2f))
        drawRoundRect(ink.copy(alpha = 0.34f * a), Offset(x, box.y + 14f * v), Size(20f * k, 1.9f), CornerRadius(0.95f))
        // The seek bar, and the three controls under it.
        val barY = box.y + 32f * v
        drawRoundRect(ink.copy(alpha = 0.18f * a), Offset(box.x + 5f * k, barY), Size(80f * k, 1.4f), CornerRadius(0.7f))
        drawRoundRect(ink.copy(alpha = 0.5f * a), Offset(box.x + 5f * k, barY), Size(34f * k, 1.4f), CornerRadius(0.7f))
        val cy = box.y + 44f * v
        for ((i, cx) in listOf(30f, 50f, 70f).withIndex()) {
            val px = box.x + cx * k
            val s = if (i == 1) 2.6f else 1.8f
            val tri = Path().apply {
                if (i == 0) { moveTo(px + s, cy - s); lineTo(px - s, cy); lineTo(px + s, cy + s) }
                else { moveTo(px - s * 0.8f, cy - s); lineTo(px + s * 1.1f, cy); lineTo(px - s * 0.8f, cy + s) }
                close()
            }
            drawPath(tri, ink.copy(alpha = 0.7f * a))
        }
    }
}

/** The finger: a white dot ringed in blue, a blue trail behind it as fast as it moves. */
private fun DrawScope.drawTouch(sc: Scene, cam: Camera) {
    val a = sc.touch.value
    if (a <= 0.01f) return
    val at = cam.map(sc.tx.value, sc.ty.value)
    val r = 5.5.dp.toPx()
    val vx = sc.tx.velocity * cam.s
    val vy = sc.ty.velocity * cam.s
    val speed = hypot(vx, vy)
    if (speed > 1f) {
        val len = min(speed * 0.09f, 70.dp.toPx())
        val tail = Offset(at.x - vx / speed * len, at.y - vy / speed * len)
        drawLine(Brush.linearGradient(listOf(Color(0x003D86FF), Color(0xCC3D86FF)), tail, at), tail, at,
            r * 2f, StrokeCap.Round, alpha = a)
    }
    drawCircle(Color.White, r * (1f - 0.08f * (1f - a)), at, alpha = a)
    drawCircle(Color(0xFF3D86FF), r, at, alpha = a, style = Stroke(1.6.dp.toPx()))
}
