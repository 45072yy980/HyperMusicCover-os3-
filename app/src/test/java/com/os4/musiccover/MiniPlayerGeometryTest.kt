package com.os4.musiccover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerGeometryTest {
    @Test fun singleShortcutKeepsThePillCentredAndPutsTheSmallIslandInTheVacantSlot() {
        val leftOnly = MiniPlayerGeometry.singleShortcutLayout(1200, 36, 1080, 162, 24,
            true, 117f, 198f, null, true)
        assertEquals(600f, leftOnly.pillCenterX, 0f)
        assertEquals(756, leftOnly.pillWidth)
        assertEquals(1083f, leftOnly.smallCenterX, 0f)
        val rightOnly = MiniPlayerGeometry.singleShortcutLayout(1200, 36, 1080, 162, 24,
            false, 1083f, 1002f, null, true)
        assertEquals(600f, rightOnly.pillCenterX, 0f)
        assertEquals(756, rightOnly.pillWidth)
        assertEquals(117f, rightOnly.smallCenterX, 0f)
        val neither = MiniPlayerGeometry.adaptiveRoom(400, 12, null, null, 8)!!
        assertEquals(200f, neither.centerX, 0f)
        assertEquals(376, neither.width)
    }

    @Test fun returningSmallIslandDoesNotShiftThePillAndRespectsTouchMargins() {
        for (hasSmall in listOf(false, true)) {
            val layout = MiniPlayerGeometry.singleShortcutLayout(1200, 36, 1080, 162, 24,
                true, 117f, 250f, 1050f, hasSmall)
            assertEquals(600f, layout.pillCenterX, 0f)
            assertTrue(layout.pillCenterX - layout.pillWidth / 2f >= 274f)
            if (hasSmall) assertTrue(layout.smallCenterX - 81f -
                (layout.pillCenterX + layout.pillWidth / 2f) >= 24f)
        }
    }

    @Test fun ordinaryNotificationBelowTheOldIslandPositionIsStillVisible() {
        assertTrue(MiniPlayerGeometry.rowIntersectsViewport(2016f, 994, 0f, 2608f))
        assertTrue(MiniPlayerGeometry.rowIntersectsViewport(1700f, 557, 0f, 2536f))
        assertTrue(!MiniPlayerGeometry.rowIntersectsViewport(2608f, 994, 0f, 2608f))
        assertTrue(!MiniPlayerGeometry.rowIntersectsViewport(-994f, 994, 0f, 2608f))
        assertTrue(!MiniPlayerGeometry.rowIntersectsViewport(2016f, 0, 0f, 2608f))
    }

    @Test fun notificationViewportUsesTheSameScreenCoordinatesAsTheRow() {
        assertTrue(!MiniPlayerGeometry.rowIntersectsViewport(10f, 50, 100f, 900f))
        assertTrue(MiniPlayerGeometry.rowIntersectsViewport(80f, 50, 100f, 900f))
        assertTrue(!MiniPlayerGeometry.rowIntersectsViewport(900f, 50, 100f, 900f))
    }

    @Test fun missingShortcutsAnchorToScreenBottomOnTheReportedDevice() {
        // 1200x2608 device, 54dp island and 12dp margin at density 3.
        val center = MiniPlayerGeometry.bottomCenterY(2608, 0, 36, 162)
        assertEquals(2491f, center, 0f)
        assertEquals(2572f, center + 81f, 0f)
        assertEquals(2419f, MiniPlayerGeometry.bottomCenterY(2608, 72, 36, 162), 0f)
    }

    @Test fun bottomPlacementHandlesAnUnmeasuredOrUndersizedHost() {
        assertEquals(0f, MiniPlayerGeometry.bottomCenterY(0, 72, 36, 162), 0f)
        assertEquals(50f, MiniPlayerGeometry.bottomCenterY(100, 72, 36, 162), 0f)
    }

    @Test fun originalHeightSettingUsesDiameterWithMinimum() {
        assertEquals(72f, MiniPlayerGeometry.heightDp(36f))
        assertEquals(48f, MiniPlayerGeometry.heightDp(10f))
    }

    @Test fun widthFitsBetweenShortcutsAndHostEdges() {
        assertEquals(230, MiniPlayerGeometry.widthPx(230, 360, 180f, 12))
        val narrow = MiniPlayerGeometry.widthPx(230, 360, 50f, 12)
        assertTrue(narrow <= 88)
        assertTrue(narrow > 0)
    }

    @Test fun pillClearsBothDiscsByTheGap() {
        // Buttons at 57 and 343 on a 400 wide row, discs 72 across, 8 of gap: 99 each side.
        assertEquals(198, MiniPlayerGeometry.clearOfDiscsPx(240, 200f, 57f, 343f, 72f, 8f, 140))
        // Already narrower: untouched.
        assertEquals(160, MiniPlayerGeometry.clearOfDiscsPx(160, 200f, 57f, 343f, 72f, 8f, 140))
        // No room at all: the floor, and the discs are pressed on from the start.
        assertEquals(140, MiniPlayerGeometry.clearOfDiscsPx(240, 200f, 150f, 250f, 72f, 8f, 140))
        // A missing button takes nothing.
        assertEquals(240, MiniPlayerGeometry.clearOfDiscsPx(240, 200f, null, null, 72f, 8f, 140))
    }

    @Test fun pillBesideAnIslandStaysClearOfTheDiscs() {
        // The device filmed: a 477 pill, 162 island, 24 gap, 420 floor, 664 of room - the floor
        // wins and the pair is 606 wide, inside the room.
        assertEquals(420, MiniPlayerGeometry.pillBesideIslandPx(477, 664, 162, 24, 420))
        // Room for less than the floor: the room wins, the pair ends at the room's edge.
        assertEquals(314, MiniPlayerGeometry.pillBesideIslandPx(477, 500, 162, 24, 420))
        // A wide pill gives the island its room out of its own width.
        assertEquals(514, MiniPlayerGeometry.pillBesideIslandPx(700, 900, 162, 24, 420))
        // No room at all: never narrower than a circle; the discs are pressed on instead.
        assertEquals(162, MiniPlayerGeometry.pillBesideIslandPx(477, 250, 162, 24, 420))
    }

}
