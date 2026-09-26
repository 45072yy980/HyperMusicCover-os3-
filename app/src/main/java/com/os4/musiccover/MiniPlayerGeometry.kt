package com.os4.musiccover

import kotlin.math.max
import kotlin.math.min

/** Keeps the pill inside the shortcut host even if an OEM layout moves a shortcut off centre. */
internal object MiniPlayerGeometry {
    /** The room between the pill and each shortcut disc at rest; closing it presses on a disc. */
    const val DISC_GAP_DP = 8f

    /** However close the buttons, the pill keeps this width and presses on the discs instead. */
    const val MIN_PILL_DP = 140f

    data class RowRoom(val centerX: Float, val width: Int)

    data class PinnedIslandLayout(val pillCenterX: Float, val pillWidth: Int, val smallCenterX: Float)

    /** Keep the big island centred; the small one occupies the absent shortcut's own slot. */
    fun singleShortcutLayout(hostWidth: Int, margin: Int, maxPillWidth: Int, islandSize: Int,
                             gap: Int, shortcutOnLeft: Boolean, shortcutCenter: Float,
                             shortcutInnerEdge: Float, missingCenter: Float?, hasSmall: Boolean): PinnedIslandLayout {
        val center = hostWidth / 2f
        val radius = islandSize / 2f
        val safe = min(hostWidth / 2f, margin + radius)
        val small = (missingCenter ?: (hostWidth - shortcutCenter)).coerceIn(safe, hostWidth - safe)
        var left = margin.toFloat()
        var right = (hostWidth - margin).toFloat()
        if (shortcutOnLeft) left = max(left, shortcutInnerEdge + gap)
        else right = min(right, shortcutInnerEdge - gap)
        if (hasSmall) {
            if (shortcutOnLeft) right = min(right, small - radius - gap)
            else left = max(left, small + radius + gap)
        }
        val width = min(maxPillWidth, (2f * min(center - left, right - center)).toInt()).coerceAtLeast(1)
        return PinnedIslandLayout(center, width, small)
    }

    /** The whole island group fits between screen margins and the remaining shortcut touch area. */
    fun adaptiveRoom(hostWidth: Int, margin: Int, leftInnerEdge: Float?, rightInnerEdge: Float?,
                     gap: Int): RowRoom? {
        if (hostWidth <= 0) return null
        val edge = margin.coerceIn(0, hostWidth / 2).toFloat()
        val start = max(edge, leftInnerEdge?.plus(gap.coerceAtLeast(0)) ?: edge)
        val end = min(hostWidth - edge, rightInnerEdge?.minus(gap.coerceAtLeast(0)) ?: (hostWidth - edge))
        if (end - start < 1f) return null
        return RowRoom((start + end) / 2f, (end - start).toInt())
    }

    fun heightDp(radiusDp: Float): Float = (radiusDp.coerceIn(10f, 60f) * 2f).coerceAtLeast(48f)

    /** Bottom placement is independent of the OEM bottom area's possibly full-screen bounds. */
    fun bottomCenterY(hostHeight: Int, bottomInset: Int, margin: Int, pillHeight: Int): Float {
        if (hostHeight <= 0) return 0f
        val half = pillHeight.coerceIn(0, hostHeight) / 2f
        return (hostHeight - bottomInset.coerceAtLeast(0) - margin.coerceAtLeast(0) - half)
            .coerceIn(half, hostHeight - half)
    }

    /** A notification may extend below the viewport while its upper portion is visible. */
    fun rowIntersectsViewport(rowTop: Float, rowHeight: Int, viewportTop: Float, viewportBottom: Float): Boolean =
        rowHeight > 0 && viewportBottom > viewportTop &&
            rowTop < viewportBottom && rowTop + rowHeight > viewportTop

    fun widthPx(requestedPx: Int, hostWidth: Int, centerX: Float, marginPx: Int): Int {
        if (hostWidth <= 0) return 0
        val margin = marginPx.coerceIn(0, (hostWidth - 1) / 2)
        val center = centerX.coerceIn(margin.toFloat(), (hostWidth - margin).toFloat())
        val symmetricRoom = (2f * min(center, hostWidth - center) - margin).toInt()
        return min(requestedPx, min(hostWidth - 2 * margin, symmetricRoom)).coerceAtLeast(1)
    }

    /**
     * The widest the pill can be and still clear both shortcut discs by [gapPx]: each disc is
     * a circle of [discPx] on its button's centre; a button that is not there takes nothing.
     * Never below [minPx] - past that the discs are simply pressed on from the start.
     */
    fun clearOfDiscsPx(widthPx: Int, centerX: Float, leftCx: Float?, rightCx: Float?,
                       discPx: Float, gapPx: Float, minPx: Int): Int {
        var half = widthPx / 2f
        if (leftCx != null) half = min(half, centerX - (leftCx + discPx / 2f) - gapPx)
        if (rightCx != null) half = min(half, (rightCx - discPx / 2f) - centerX - gapPx)
        return max(min(minPx, widthPx), (half * 2f).toInt()).coerceAtMost(widthPx)
    }

    /**
     * The pill's width with a small island of [islandPx] beside it, [gapPx] apart: the pair takes
     * the pill's own [widthPx] and gives the pill no less than [minPx] - but never reaches past
     * [roomPx], the most the row has between the two discs (clearOfDiscsPx with no minimum).
     * The first version let the minimum win, and the pair grew into the camera's disc: its
     * touch area took the small island's taps (filmed 2026-09-25). Only when even that room
     * is too small for a pill as wide as it is tall does the pair press on the discs.
     */
    fun pillBesideIslandPx(widthPx: Int, roomPx: Int, islandPx: Int, gapPx: Int, minPx: Int): Int {
        val wanted = max(minPx, widthPx - gapPx - islandPx)
        val fits = roomPx - gapPx - islandPx
        return max(min(wanted, fits), min(wanted, islandPx)).coerceAtLeast(1)
    }
}
