package com.sailens.shell.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HapticArbiterTest {
    private var now = 1_000L
    private val arbiter = HapticArbiter(clock = { now })

    @Test
    fun `a lower or equal priority rhythm does not cut one still playing`() {
        assertTrue(arbiter.tryClaim(priority = 3, durationMs = 400))
        now += 100
        assertFalse(arbiter.tryClaim(priority = 1, durationMs = 60))
        assertFalse(arbiter.tryClaim(priority = 3, durationMs = 60))
    }

    @Test
    fun `a strictly higher priority rhythm interrupts`() {
        assertTrue(arbiter.tryClaim(priority = 1, durationMs = 400))
        now += 100
        assertTrue(arbiter.tryClaim(priority = 3, durationMs = 60))
    }

    @Test
    fun `anything plays once the previous rhythm has finished`() {
        assertTrue(arbiter.tryClaim(priority = 3, durationMs = 400))
        now += 401
        assertTrue(arbiter.tryClaim(priority = 0, durationMs = 60))
    }

    @Test
    fun `an alarm is never cut by a navigation rhythm`() {
        arbiter.claimUnconditionally(durationMs = 1_500)
        now += 100
        assertFalse(arbiter.tryClaim(priority = 3, durationMs = 60))
    }

    @Test
    fun `cancelling frees the motor`() {
        assertTrue(arbiter.tryClaim(priority = 3, durationMs = 400))
        arbiter.clear()
        assertTrue(arbiter.tryClaim(priority = 0, durationMs = 60))
    }
}
