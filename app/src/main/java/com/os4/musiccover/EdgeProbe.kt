// SPDX-License-Identifier: Apache-2.0
package com.os4.musiccover

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import android.view.View
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * `op edge`: the pill's and the discs' clip chains, for the glass rim (#7, #21 A2).
 *
 * Each island and disc is two clips: its container (the blur container) and its material
 * element (the card's recipe, glass and all), and the glass draws its rim along the element's
 * own outline. The rim came out thin toward the diagonals and jagged there because the element
 * had been given the card's 24dp corner (72px) inside a frame cut round at 81px - the frame's
 * clip took the rim off. This is what showed it: each view's outline as it answers now, up the
 * chain, with the element's Mi blur state. See MaterialElement for the fix.
 */
internal object EdgeProbe {
    /** One view and its ancestors up to [stop], clips and all; its Mi state if [mi]. */
    fun describe(name: String, v: View, stop: View?, mi: Boolean): String {
        val sb = StringBuilder(name).append(':')
        val xy = IntArray(2)
        val outline = Outline()
        val rect = Rect()
        var cur: View? = v
        var depth = 0
        while (cur != null && depth < 14) {
            cur.getLocationOnScreen(xy)
            val id = runCatching { cur.resources.getResourceEntryName(cur.id) }.getOrNull()
                ?: cur.javaClass.simpleName
            sb.append("\n  ").append(id).append(" at=").append(xy[0]).append(',').append(xy[1])
                .append(' ').append(cur.width).append('x').append(cur.height)
            if (cur.clipToOutline) {
                outline.setEmpty()
                cur.outlineProvider?.getOutline(cur, outline)
                sb.append(" clipOutline=")
                if (outline.getRect(rect)) sb.append(rect.toShortString()).append("r").append("%.1f".format(outline.radius))
                else sb.append("path")
            }
            (cur as? android.view.ViewGroup)?.let { if (!it.clipChildren) sb.append(" noClipChildren") }
            cur.clipBounds?.let { sb.append(" clipBounds=").append(it.toShortString()) }
            if (cur.layerType != View.LAYER_TYPE_NONE) sb.append(" layer=").append(cur.layerType)
            if (cur.alpha != 1f || cur.transitionAlpha != 1f)
                sb.append(" a=").append("%.2f".format(cur.alpha)).append('/').append("%.2f".format(cur.transitionAlpha))
            if (cur.scaleX != 1f || cur.scaleY != 1f)
                sb.append(" s=").append("%.3f".format(cur.scaleX)).append('x').append("%.3f".format(cur.scaleY))
            if (cur.translationX != 0f || cur.translationY != 0f)
                sb.append(" t=").append(cur.translationX.toInt()).append(',').append(cur.translationY.toInt())
            if (cur.animationMatrix?.isIdentity == false) sb.append(" animMatrix")
            if (!cur.hasOverlappingRendering()) sb.append(" noOverlap")
            if (cur === stop) break
            cur = cur.parent as? View
            depth++
        }
        // The glass's shape size against the view's: a stale one leaves the rim to the clip.
        EdgeWatch.sdfOf(v)?.let { sb.append("\n  sdf=").append(it).append(" view=").append(v.width).append('x').append(v.height) }
        if (mi) sb.append("\n  mi{").append(miState(v)).append('}')
        return sb.toString()
    }

    /** Every no-argument getter View has for the Mi blur and glass, as it answers for [v]. */
    private fun miState(v: View): String = View::class.java.methods
        .filter { it.parameterCount == 0 && (it.name.startsWith("getMi") || it.name.startsWith("isMi")) }
        .sortedBy { it.name }
        .joinToString(" ") { m ->
            val value = runCatching { m.invoke(v) }.getOrElse { "!" }
            val shown = when (value) {
                is FloatArray -> value.joinToString(",", "[", "]") { "%.2f".format(it) }
                is IntArray -> value.joinToString(",", "[", "]")
                else -> value.toString()
            }
            "${m.name}=$shown"
        }

    // ---- the rim as it is in SystemUI's own buffer

    private class Shape(val name: String, val local: RectF, val radius: Float, val toWindow: Matrix,
                        val window: RectF)

    /**
     * How smooth each view's outline is in the pixels SystemUI itself drew, corner by corner.
     *
     * One PixelCopy of the window's surface around the views: what HWUI rendered, before
     * SurfaceFlinger composes it with the wallpaper and the window blur. Compared with a
     * screenshot of the same moment it says which side the jaggies come from - rough here, our
     * tree or the glass drew them; smooth here but rough on the screenshot, the composition did.
     *
     * Per corner quadrant, two numbers. Each pixel within [BAND] px of the outline gets a
     * coverage: its alpha over the alpha [PROBE] px inside along the normal, where the window is
     * clear [PROBE] px outside (the lock screen's case), or else its colour projected onto the
     * line between those two colours. `soft` is the partly covered pixels per pixel of edge
     * length: ~1 for an antialiased edge (measured on the phone the jaggies were fixed on,
     * 2026-09-30), 0 for a hard cut - the same crop thresholded reads 0.00. `at` is where the
     * edge actually is from the outline, in px, from the covered area: negative is inside. The
     * glass draws its edge up to ~1px inside the outline, so a positive `at` is a material
     * reaching past the clip, which is what cuts a rim hard. `aIn` is the alpha inside: near 0,
     * the material is not in this buffer at all but composed in later.
     */
    fun measure(views: List<Pair<String, View>>, save: java.io.File? = null, done: (String) -> Unit) {
        val shown = views.filter { (_, v) -> v.isAttachedToWindow && v.isShown && v.width > 0 && v.height > 0 }
        if (shown.isEmpty()) return done("rim: nothing shown")
        val root = shown[0].second.rootView
        val surface = runCatching {
            Xp.getObjectField(Xp.callMethod(root, "getViewRootImpl"), "mSurface") as Surface
        }.getOrNull()
        if (surface == null || !surface.isValid) return done("rim: no surface")
        val skipped = StringBuilder()
        val shapes = shown.mapNotNull { (name, v) ->
            if (v.rootView !== root) {
                skipped.append("\n  ").append(name).append(": another window")
                return@mapNotNull null
            }
            val o = Outline()
            v.outlineProvider?.getOutline(v, o)
            val r = Rect()
            if (!o.getRect(r) || r.isEmpty) {
                skipped.append("\n  ").append(name).append(": no round-rect outline")
                return@mapNotNull null
            }
            val m = toWindow(v, root)
            val w = RectF(r).also(m::mapRect)
            Shape(name, RectF(r), o.radius, m, w)
        }
        if (shapes.isEmpty()) return done("rim:$skipped")
        val union = RectF(shapes[0].window)
        shapes.forEach { union.union(it.window) }
        val src = Rect((union.left - 6).toInt(), (union.top - 6).toInt(),
            (union.right + 7).toInt(), (union.bottom + 7).toInt())
        if (!src.intersect(0, 0, root.width, root.height)) return done("rim: off the window")
        val bmp = Bitmap.createBitmap(src.width(), src.height(), Bitmap.Config.ARGB_8888)
        runCatching {
            PixelCopy.request(surface, src, bmp, { result ->
                val text = if (result != PixelCopy.SUCCESS) "rim: PixelCopy failed ($result)"
                else runCatching {
                    // The crop and each view's matrix, for checking the numbers offline.
                    val saved = save?.let { f ->
                        runCatching {
                            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            " saved " + f.path
                        }.getOrElse { " not saved: $it" }
                    } ?: ""
                    "rim (window buffer " + src.toShortString() + saved + "):" +
                        shapes.joinToString("") { s ->
                            "\n  " + rim(s, bmp, src) + if (save == null) "" else
                                "\n    local=" + s.local.toShortString() + " radius=" + s.radius + " m=" +
                                    FloatArray(9).also(s.toWindow::getValues).joinToString(",") { "%.4f".format(it) }
                        } + skipped
                }.getOrElse { "rim: $it" }
                bmp.recycle()
                done(text)
            }, Handler(Looper.getMainLooper()))
        }.onFailure {
            bmp.recycle()
            done("rim: $it")
        }
    }

    /**
     * View-local to window coordinates as HWUI draws the view: its position, its animation
     * matrix, then its own transform, up every parent. transformMatrixToGlobal leaves the
     * animation matrix out, and the pill rides the shortcut buttons on exactly that
     * (followShortcuts).
     */
    private fun toWindow(v: View, root: View): Matrix {
        val m = Matrix()
        var cur: View = v
        while (true) {
            if (!cur.matrix.isIdentity) m.postConcat(cur.matrix)
            cur.animationMatrix?.let { m.postConcat(it) }
            m.postTranslate(cur.left.toFloat(), cur.top.toFloat())
            if (cur === root) break
            val p = cur.parent as? View ?: break
            m.postTranslate(-p.scrollX.toFloat(), -p.scrollY.toFloat())
            cur = p
        }
        return m
    }

    private const val BAND = 3f
    private const val PROBE = 4f

    private fun rim(s: Shape, bmp: Bitmap, src: Rect): String {
        val inv = Matrix()
        if (!s.toWindow.invert(inv)) return "${s.name}: singular"
        val scale = s.toWindow.mapRadius(1f)
        val cx = s.local.centerX()
        val cy = s.local.centerY()
        val hx = s.local.width() / 2
        val hy = s.local.height() / 2
        val r = min(s.radius, min(hx, hy))
        fun sdf(x: Float, y: Float): Float {
            val qx = abs(x - cx) - hx + r
            val qy = abs(y - cy) - hy + r
            val ox = max(qx, 0f)
            val oy = max(qy, 0f)
            return sqrt(ox * ox + oy * oy) + min(max(qx, qy), 0f) - r
        }
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        fun colour(wx: Float, wy: Float, out: FloatArray): Boolean {
            val x = floor(wx - src.left).toInt()
            val y = floor(wy - src.top).toInt()
            if (x < 0 || y < 0 || x >= w || y >= h) return false
            val c = px[y * w + x]
            val a = (c ushr 24) / 255f
            out[0] = a
            out[1] = ((c shr 16) and 0xff) / 255f * a
            out[2] = ((c shr 8) and 0xff) / 255f * a
            out[3] = (c and 0xff) / 255f * a
            return true
        }
        // TL, TR, BL, BR: counted, partly covered, coverage summed, alpha inside summed.
        val n = IntArray(4)
        val part = IntArray(4)
        val covered = FloatArray(4)
        val inside = FloatArray(4)
        var byAlpha = 0
        val pt = FloatArray(2)
        val cIn = FloatArray(4)
        val cOut = FloatArray(4)
        val c = FloatArray(4)
        val e = 0.25f / scale
        val step = PROBE / scale
        val x0 = max(src.left, (s.window.left - BAND - 1).toInt())
        val x1 = min(src.right - 1, (s.window.right + BAND + 1).toInt())
        val y0 = max(src.top, (s.window.top - BAND - 1).toInt())
        val y1 = min(src.bottom - 1, (s.window.bottom + BAND + 1).toInt())
        for (wy in y0..y1) for (wx in x0..x1) {
            pt[0] = wx + 0.5f
            pt[1] = wy + 0.5f
            inv.mapPoints(pt)
            val lx = pt[0]
            val ly = pt[1]
            val d = sdf(lx, ly) * scale
            if (abs(d) > BAND) continue
            var gx = sdf(lx + e, ly) - sdf(lx - e, ly)
            var gy = sdf(lx, ly + e) - sdf(lx, ly - e)
            val gl = sqrt(gx * gx + gy * gy)
            if (gl < 1e-6f) continue
            gx /= gl
            gy /= gl
            // The point on the outline, then PROBE px either side of it along the normal.
            val bx = lx - gx * d / scale
            val by = ly - gy * d / scale
            pt[0] = bx - gx * step
            pt[1] = by - gy * step
            s.toWindow.mapPoints(pt)
            if (!colour(pt[0], pt[1], cIn)) continue
            pt[0] = bx + gx * step
            pt[1] = by + gy * step
            s.toWindow.mapPoints(pt)
            if (!colour(pt[0], pt[1], cOut)) continue
            if (!colour(wx + 0.5f, wy + 0.5f, c)) continue
            val cov = if (cOut[0] < 0.05f && cIn[0] > 0.5f) {
                byAlpha++
                (c[0] / cIn[0]).coerceIn(0f, 1f)
            } else {
                var len2 = 0f
                var dot = 0f
                for (k in 0..3) {
                    val dv = cIn[k] - cOut[k]
                    len2 += dv * dv
                    dot += (c[k] - cOut[k]) * dv
                }
                if (len2 < 0.01f) continue
                (dot / len2).coerceIn(0f, 1f)
            }
            val q = (if (lx < cx) 0 else 1) + (if (ly < cy) 0 else 2)
            n[q]++
            covered[q] += cov
            if (cov > 0.12f && cov < 0.88f) part[q]++
            inside[q] += cIn[0]
        }
        // A quadrant's edge length in px: its two straight runs and a quarter of the arc.
        val quarter = ((hx - r) + (hy - r) + (Math.PI * r / 2).toFloat()) * scale
        val names = arrayOf("TL", "TR", "BL", "BR")
        val sb = StringBuilder(s.name).append(' ')
            .append("%.1f,%.1f".format(s.window.left, s.window.top)).append(' ')
            .append("%.1fx%.1f".format(s.window.width(), s.window.height()))
            .append(" r=").append("%.1f".format(r * scale))
            .append(if (byAlpha > 0) " by=alpha" else " by=colour")
        for (q in 0..3) {
            sb.append(' ').append(names[q]).append('{')
            if (n[q] == 0) sb.append("n=0") else sb.append("soft=").append("%.2f".format(part[q] / quarter))
                .append(" at=").append("%+.2f".format(covered[q] / n[q] * 2 * BAND - BAND))
                .append(" aIn=").append("%.2f".format(inside[q] / n[q]))
            sb.append('}')
        }
        return sb.toString()
    }
}
