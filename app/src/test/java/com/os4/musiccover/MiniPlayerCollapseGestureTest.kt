package com.os4.musiccover

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerCollapseGestureTest {
    @Test fun scrollingBackToTheTopCannotCollapseUntilTheNextDown() {
        val gesture = MiniPlayerCollapseGesture()
        gesture.start(false)
        assertFalse(gesture.move(0f, 100f, 10f, false))
        assertFalse(gesture.move(0f, 300f, 10f, true))
        gesture.start(true)
        assertTrue(gesture.move(0f, 30f, 10f, true))
    }

    @Test fun upwardScrollCannotTurnIntoCollapseOnDirectionReversal() {
        val gesture = MiniPlayerCollapseGesture()
        gesture.start(true)
        assertFalse(gesture.move(0f, -30f, 10f, true))
        assertFalse(gesture.move(0f, 80f, 10f, true))
    }

    @Test fun horizontalDismissalKeepsTheGesture() {
        val gesture = MiniPlayerCollapseGesture()
        gesture.start(true)
        assertFalse(gesture.move(40f, 10f, 10f, true))
        assertFalse(gesture.move(40f, 100f, 10f, true))
    }

    @Test fun scrollingAfterDownDisarmsCollapse() {
        val gesture = MiniPlayerCollapseGesture()
        gesture.start(true)
        assertFalse(gesture.move(0f, 30f, 10f, false))
        assertFalse(gesture.move(0f, 80f, 10f, true))
    }

    @Test fun smallMotionThenPullAtTopFiresOnlyOnce() {
        val gesture = MiniPlayerCollapseGesture()
        gesture.start(true)
        assertFalse(gesture.move(2f, -3f, 10f, true))
        assertTrue(gesture.move(2f, 30f, 10f, true))
        assertFalse(gesture.move(2f, 60f, 10f, true))
    }
}
