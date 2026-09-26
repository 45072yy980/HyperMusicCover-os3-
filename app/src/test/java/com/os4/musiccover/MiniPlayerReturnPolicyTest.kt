package com.os4.musiccover

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerReturnPolicyTest {
    @Test fun notificationTemporarilyPromotedWhileMediaWasOutReturnsBesideMusic() {
        assertFalse(MiniPlayerReturnPolicy.returnsToPill(false, false, true, true, false))
        assertFalse(MiniPlayerReturnPolicy.returnsToPill(false, false, true, false, true))
    }

    @Test fun notificationAloneStillReturnsAsThePill() {
        assertTrue(MiniPlayerReturnPolicy.returnsToPill(true, false, false, false, true))
        assertTrue(MiniPlayerReturnPolicy.returnsToPill(false, false, false, true, false))
    }

    @Test fun mediaPreservesItsOriginalSmallOrBigSeat() {
        assertTrue(MiniPlayerReturnPolicy.returnsToPill(false, true, false, false, false))
        assertFalse(MiniPlayerReturnPolicy.returnsToPill(false, true, false, false, true))
    }
}
