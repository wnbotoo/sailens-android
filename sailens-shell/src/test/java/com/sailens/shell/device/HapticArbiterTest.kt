package com.sailens.shell.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ---- Reservations (the vision-fault rhythms) --------------------------------------------------

    private val started = mutableListOf<Long>()
    private val start: () -> Unit = { started += now }

    @Test
    fun `a reservation does not cut an equal or higher rhythm, and starts when it ends`() {
        assertTrue(arbiter.tryClaim(priority = 3, durationMs = 400))
        assertFalse(arbiter.claimOrReserve(priority = 0, durationMs = 940, start = start))
        assertEquals(1_400L, arbiter.reservationFreeAtMs())
        now = 1_399
        assertFalse(arbiter.startReservedIfFree())
        now = 1_400
        assertTrue(arbiter.startReservedIfFree())
        assertEquals(listOf(1_400L), started)
        assertNull(arbiter.reservationFreeAtMs())
    }

    @Test
    fun `a reservation goes before any rhythm asked for after it, even at the same instant`() {
        assertTrue(arbiter.tryClaim(priority = 2, durationMs = 300))
        assertFalse(arbiter.claimOrReserve(priority = 0, durationMs = 940, start = start))
        now = 1_300
        // The timer has not run yet; a CRITICAL rhythm asks first and finds the reservation starting.
        assertFalse(arbiter.tryClaim(priority = 3, durationMs = 210))
        assertEquals(listOf(1_300L), started)
    }

    @Test
    fun `once started nothing cuts it, but it starts at once over a strictly lower rhythm`() {
        assertTrue(arbiter.tryClaim(priority = 1, durationMs = 400))
        assertTrue(arbiter.claimOrReserve(priority = 3, durationMs = 940, start = start))
        now += 100
        assertFalse(arbiter.tryClaim(priority = 3, durationMs = 60))
        now += 900
        assertTrue(arbiter.tryClaim(priority = 0, durationMs = 60))
    }

    @Test
    fun `a strictly higher rhythm interrupting the one playing makes the reservation wait for it`() {
        assertTrue(arbiter.tryClaim(priority = 1, durationMs = 400))
        assertFalse(arbiter.claimOrReserve(priority = 0, durationMs = 940, start = start))
        now = 1_200
        assertTrue(arbiter.tryClaim(priority = 3, durationMs = 400))
        now = 1_400
        assertFalse(arbiter.startReservedIfFree())
        assertEquals(1_600L, arbiter.reservationFreeAtMs())
        now = 1_600
        assertTrue(arbiter.startReservedIfFree())
    }

    @Test
    fun `turning navigation vibration off releases a navigation rhythm, but not an alarm or a started reservation`() {
        assertTrue(arbiter.tryClaim(priority = 3, durationMs = 400))
        assertTrue("a navigation rhythm is stopped", arbiter.releaseNavigation())
        assertTrue(arbiter.tryClaim(priority = 0, durationMs = 60))

        now = 2_000
        assertTrue(arbiter.claimOrReserve(priority = 0, durationMs = 940, start = start))
        now = 2_100
        assertFalse("a started fault rhythm plays on", arbiter.releaseNavigation())
        assertFalse(arbiter.tryClaim(priority = 3, durationMs = 60))

        now = 4_000
        arbiter.claimUnconditionally(durationMs = 1_500)
        now = 4_100
        assertFalse("an alarm plays on", arbiter.releaseNavigation())
        now = 5_500
        assertTrue("once it has ended there is nothing to keep", arbiter.releaseNavigation())
    }

    @Test
    fun `a cancelled reservation never starts, and a newer one replaces it`() {
        assertTrue(arbiter.tryClaim(priority = 3, durationMs = 400))
        assertFalse(arbiter.claimOrReserve(priority = 0, durationMs = 940) { started += -1 })
        assertFalse(arbiter.claimOrReserve(priority = 0, durationMs = 940, start = start))
        now = 1_400
        assertTrue(arbiter.startReservedIfFree())
        assertEquals("only the newer one", listOf(1_400L), started)

        now = 3_000
        assertTrue(arbiter.tryClaim(priority = 3, durationMs = 400))
        assertFalse(arbiter.claimOrReserve(priority = 0, durationMs = 940, start = start))
        arbiter.cancelReservation()
        now = 3_400
        assertFalse(arbiter.startReservedIfFree())
        assertEquals(listOf(1_400L), started)
    }
}
