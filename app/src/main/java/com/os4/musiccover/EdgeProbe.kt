// SPDX-License-Identifier: Apache-2.0
package com.os4.musiccover

import android.graphics.Outline
import android.graphics.Rect
import android.view.View

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
}
