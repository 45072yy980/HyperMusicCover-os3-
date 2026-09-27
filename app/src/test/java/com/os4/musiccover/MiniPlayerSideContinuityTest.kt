package com.os4.musiccover

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerSideContinuityTest {
    @Test fun promotingAnExtraViewDoesNotMakeItsExistingNotificationNew() {
        val state = MiniPlayerSideContinuity<Any>()
        val left = Any()
        val right = Any()
        state.record(left, "notifications", true)
        state.record(right, "focus", true)
        assertTrue(state.keeps(left, "notifications"))
        state.record(right, "focus", false)
        assertTrue(state.keeps(left, "notifications"))
        assertFalse(state.keeps(right, "focus"))
    }

    @Test fun replacementContentAndNewViewsStillNeedTheirOwnEntry() {
        val state = MiniPlayerSideContinuity<Any>()
        val view = Any()
        state.record(view, "first", true)
        assertFalse(state.keeps(view, "second"))
        assertFalse(state.keeps(Any(), "first"))
    }

    @Test fun oldAnimationCannotWriteToAnotherViewOrAnotherIsland() {
        val left = Any()
        val right = Any()
        assertTrue(MiniPlayerSideContinuity.owns(left, "notes", left, "notes"))
        assertFalse(MiniPlayerSideContinuity.owns(right, "focus", left, "notes"))
        assertFalse(MiniPlayerSideContinuity.owns(left, "focus", left, "notes"))
    }
}
