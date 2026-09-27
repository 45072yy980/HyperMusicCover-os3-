package com.os4.musiccover

import org.junit.Assert.*
import org.junit.Test

class MiniPlayerExchangeGestureTest {
    @Test fun cancellationAlwaysReturnsHome() {
        assertFalse(MiniPlayerExchangeGesture.commit(0.95f, 6f, true))
    }

    @Test fun reversingThePullReturnsHome() {
        assertFalse(MiniPlayerExchangeGesture.commit(0.65f, -2f, false))
    }

    @Test fun shortPullBeforeTheRowArrivesReturnsHome() {
        assertFalse(MiniPlayerExchangeGesture.commit(0.15f, 0f, false))
    }

    @Test fun deliberateOpeningAndUpwardFlingCommit() {
        assertTrue(MiniPlayerExchangeGesture.commit(0.7f, 0f, false))
        assertTrue(MiniPlayerExchangeGesture.commit(0.3f, 2f, false))
    }

    @Test fun cancelledExchangeRestoresBothDisplayedSlotsAndReturnHomes() {
        val slots = MiniPlayerSlotMemory()
        val keys = listOf("music", "timer", "notifications")
        slots.reconcile(keys, "music", true)
        slots.layout(keys, "music", true, "timer", "notifications")
        val before = slots.snapshot()
        slots.page("timer", keys, true)
        slots.layout(keys.filter { it != "music" }, "timer", true, "notifications")
        slots.restore(before)
        assertEquals(MiniPlayerSlotMemory.Slot.CENTRE, slots.home("music"))
        assertEquals(MiniPlayerSlotMemory.Slot.CENTRE, slots.displayedAt("music"))
        assertEquals(MiniPlayerSlotMemory.Slot.LEFT, slots.displayedAt("timer"))
        assertEquals(MiniPlayerSlotMemory.Slot.RIGHT, slots.displayedAt("notifications"))
    }
}
