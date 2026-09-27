package com.os4.musiccover

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerReturnPolicyTest {
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
