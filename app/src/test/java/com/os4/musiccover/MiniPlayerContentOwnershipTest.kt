package com.os4.musiccover

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerContentOwnershipTest {
    @Test fun anOldNotificationImageCannotBeRevealedForAnIncomingFocusIsland() {
        val content = MiniPlayerContentOwnership<Any>()
        val target = Any()
        content.bind(target, "notifications")
        assertFalse(content.ready(target, "focus"))
        content.bind(target, "focus")
        assertTrue(content.ready(target, "focus"))
        assertFalse(content.ready(target, "notifications"))
    }

    @Test fun identityIsSpecificToThePhysicalDestinationView() {
        val content = MiniPlayerContentOwnership<Any>()
        val flight = Any()
        val target = Any()
        content.bind(flight, "focus")
        assertFalse(content.ready(target, "focus"))
        content.bind(target, "focus")
        content.clear(target)
        assertFalse(content.ready(target, "focus"))
    }
}
