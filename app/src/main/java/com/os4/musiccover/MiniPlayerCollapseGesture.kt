package com.os4.musiccover

import kotlin.math.abs

/** A list scroll owns its whole gesture, even when it reaches the top before the finger lifts. */
internal class MiniPlayerCollapseGesture {
    var armed = false
        private set

    fun start(eligible: Boolean) { armed = eligible }

    fun move(dx: Float, dy: Float, slop: Float, atTop: Boolean): Boolean {
        if (!armed) return false
        if (!atTop || dy < -slop || abs(dx) > slop && abs(dx) >= abs(dy)) {
            armed = false
            return false
        }
        if (dy > slop && dy > abs(dx) * 1.2f) {
            armed = false
            return true
        }
        return false
    }

    fun reset() { armed = false }
}
