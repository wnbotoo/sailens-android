package com.sailens.shell.guidance.screen

import com.sailens.guidance.model.common.EventPriority
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A description the user asked for is cut off only by something urgent. Everything else waits for
 * it and is offered again afterwards.
 */
class GuidanceWaitsForDescriptionTest {

    @Test
    fun `below high a prompt waits while a description holds the floor`() {
        assertEquals(true, guidanceWaitsForDescription(EventPriority.LOW, descriptionHoldsTheFloor = true))
        assertEquals(true, guidanceWaitsForDescription(EventPriority.MEDIUM, descriptionHoldsTheFloor = true))
    }

    @Test
    fun `high and critical prompts still preempt the description`() {
        assertEquals(false, guidanceWaitsForDescription(EventPriority.HIGH, descriptionHoldsTheFloor = true))
        assertEquals(false, guidanceWaitsForDescription(EventPriority.CRITICAL, descriptionHoldsTheFloor = true))
    }

    @Test
    fun `with no description nothing waits`() {
        EventPriority.entries.forEach { priority ->
            assertEquals(false, guidanceWaitsForDescription(priority, descriptionHoldsTheFloor = false))
        }
    }
}
