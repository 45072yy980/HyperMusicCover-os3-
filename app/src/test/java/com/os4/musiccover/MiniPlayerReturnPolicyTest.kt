package com.os4.musiccover

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerReturnPolicyTest {
    @Test fun anExpandedCentreIsReplacedByTheHighestPriorityRemainingFocusIsland() {
        val rank = mapOf("timer" to 2, "stopwatch" to 1, "notifications" to 100)
        org.junit.Assert.assertEquals("timer", MiniPlayerReturnPolicy.chooseCentre(
            listOf("notifications", "stopwatch", "timer"), "notifications") { current, next ->
            rank.getValue(next) > rank.getValue(current)
        })
    }

    @Test fun ordinaryNotificationsCanFillAnOtherwiseEmptyCentre() {
        org.junit.Assert.assertEquals("notifications", MiniPlayerReturnPolicy.chooseCentre(
            listOf("notifications"), "notifications") { _, _ -> false })
        org.junit.Assert.assertNull(MiniPlayerReturnPolicy.chooseCentre(
            emptyList(), "notifications") { _, _ -> false })
    }
    @Test fun newestReturnedMediaOrFocusTakesTheCentre() {
        assertTrue(MiniPlayerReturnPolicy.returnsToPill(false, false))
        assertTrue(MiniPlayerReturnPolicy.returnsToPill(true, false))
    }

    @Test fun ordinaryNotificationStackDoesNotDisplaceTheCentralIsland() {
        assertFalse(MiniPlayerReturnPolicy.returnsToPill(false, true))
    }

    @Test fun notificationStackAloneCanStillBeShown() {
        assertTrue(MiniPlayerReturnPolicy.returnsToPill(true, true))
    }
}
