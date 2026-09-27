package com.os4.musiccover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MiniPlayerSlotMemoryTest {
    @Test fun rolePromotionAndPipelineReorderingDoNotChangeSideHomes() {
        val seats = MiniPlayerSlotMemory()
        seats.reconcile(listOf("media", "notes", "focus"), "media", true)
        seats.reconcile(listOf("focus", "media", "notes"), "media", false)
        assertEquals(MiniPlayerSlotMemory.Slot.LEFT, seats.home("notes"))
        assertEquals(MiniPlayerSlotMemory.Slot.RIGHT, seats.home("focus"))
    }

    @Test fun expandedKeysRetainTheirHomeWhileAnotherIslandTemporarilyTakesTheCentre() {
        val seats = MiniPlayerSlotMemory()
        seats.reconcile(listOf("media", "notes", "focus"), "media", true)
        seats.reconcile(listOf("notes", "focus", "media"), "notes", true)
        assertEquals(MiniPlayerSlotMemory.Slot.CENTRE, seats.home("media"))
        assertEquals(MiniPlayerSlotMemory.Slot.LEFT, seats.home("notes"))
        assertEquals(MiniPlayerSlotMemory.Slot.RIGHT, seats.home("focus"))
    }

    @Test fun explicitPageChangesCentreWithoutFlippingTheOtherSide() {
        val seats = MiniPlayerSlotMemory()
        val keys = listOf("media", "notes", "focus", "fourth")
        seats.reconcile(keys, "media", true)
        seats.page("notes", keys, true)
        assertEquals(MiniPlayerSlotMemory.Slot.CENTRE, seats.home("notes"))
        assertEquals(MiniPlayerSlotMemory.Slot.LEFT, seats.home("media"))
        assertEquals(MiniPlayerSlotMemory.Slot.RIGHT, seats.home("focus"))
        assertNull(seats.home("fourth"))
    }

    @Test fun removalFreesOnlyTheRemovedKeysHome() {
        val seats = MiniPlayerSlotMemory()
        seats.reconcile(listOf("media", "notes", "focus"), "media", true)
        seats.reconcile(listOf("media", "focus", "new"), "media", true)
        assertNull(seats.home("notes"))
        assertEquals(MiniPlayerSlotMemory.Slot.RIGHT, seats.home("focus"))
        assertEquals(MiniPlayerSlotMemory.Slot.LEFT, seats.home("new"))
    }

    @Test fun adaptiveWidthUsesTheEmptySideAndKeepsNativeOuterMargins() {
        val leftFree = MiniPlayerGeometry.sideSlotRoom(1200, 117f, 1083f, 162, 24, false, true, true)
        assertEquals(36f, leftFree.centerX - leftFree.width / 2f, 0f)
        assertEquals(978f, leftFree.centerX + leftFree.width / 2f, 0f)
        val rightFree = MiniPlayerGeometry.sideSlotRoom(1200, 117f, 1083f, 162, 24, true, false, true)
        assertEquals(222f, rightFree.centerX - rightFree.width / 2f, 0f)
        assertEquals(1164f, rightFree.centerX + rightFree.width / 2f, 0f)
    }

    @Test fun twoOccupiedSidesKeepTheLongIslandCentred() {
        val room = MiniPlayerGeometry.sideSlotRoom(1200, 117f, 1083f, 162, 24, true, true, true)
        assertEquals(600f, room.centerX, 0f)
        assertEquals(756, room.width)
    }
}
