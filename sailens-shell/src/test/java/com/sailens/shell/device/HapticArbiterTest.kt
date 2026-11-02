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

    private val played = mutableListOf<String>()
    private val results = mutableMapOf<String, Boolean>()

    private fun HapticArbiter.navigation(name: String, priority: Int, durationMs: Long = 100, expiresAtMs: Long = Long.MAX_VALUE) =
        claimOrQueueNavigation(priority, durationMs, expiresAtMs, start = { played += name }, onQueuedResult = { results[name] = it })

    @Test
    fun `a rhythm alone waits for an alarm, and starts when the motor is free, with no frame`() {
        var now = 0L
        val arbiter = HapticArbiter { now }
        arbiter.claimUnconditionally(940)
        assertEquals(NavigationClaim.QUEUED, arbiter.navigation("n", 24))
        assertEquals(940L, arbiter.reservationFreeAtMs())
        assertTrue("not yet", !arbiter.startReservedIfFree(939))
        now = 940
        assertTrue(arbiter.startReservedIfFree(now))
        assertEquals(listOf("n"), played)
        assertEquals(true, results["n"])
    }

    @Test
    fun `a lower rhythm cannot take the place of a higher one waiting, and the motor plays the higher`() {
        var now = 0L
        val arbiter = HapticArbiter { now }
        arbiter.claimUnconditionally(940)
        assertEquals(NavigationClaim.QUEUED, arbiter.navigation("critical", 28))
        assertEquals(NavigationClaim.REFUSED, arbiter.navigation("low", 8))
        now = 940
        arbiter.startReservedIfFree(now)
        assertEquals(listOf("critical"), played)
    }

    @Test
    fun `an equal or higher rhythm takes the place, and the one it replaces did not play`() {
        val arbiter = HapticArbiter { 0L }
        arbiter.claimUnconditionally(940)
        arbiter.navigation("a", 16)
        assertEquals(NavigationClaim.QUEUED, arbiter.navigation("b", 16))
        assertEquals(false, results["a"])
        assertEquals(NavigationClaim.QUEUED, arbiter.navigation("c", 24))
        assertEquals(false, results["b"])
    }

    @Test
    fun `one that expired while waiting loses its place, and does not play`() {
        var now = 0L
        val arbiter = HapticArbiter { now }
        arbiter.claimUnconditionally(940)
        arbiter.navigation("old", 28, expiresAtMs = 500)
        now = 600
        assertEquals("the place is free", NavigationClaim.QUEUED, arbiter.navigation("new", 8))
        assertEquals(false, results["old"])
        now = 940
        arbiter.startReservedIfFree(now)
        assertEquals(listOf("new"), played)

        val alone = HapticArbiter { now }
        alone.claimUnconditionally(100)
        alone.navigation("late", 28, expiresAtMs = now + 50)
        assertTrue(!alone.startReservedIfFree(now + 200))
        assertEquals(false, results["late"])
    }

    @Test
    fun `a fault rhythm reserved goes first, and the rhythm waiting after it`() {
        var now = 0L
        val arbiter = HapticArbiter { now }
        arbiter.claimUnconditionally(100)
        arbiter.navigation("n", 24, durationMs = 50)
        arbiter.claimOrReserve(28, 200) { played += "fault" }
        now = 100
        arbiter.startReservedIfFree(now)
        assertEquals(listOf("fault"), played)
        assertEquals(300L, arbiter.reservationFreeAtMs())
        now = 300
        arbiter.startReservedIfFree(now)
        assertEquals(listOf("fault", "n"), played)
    }

    // ---- The timer: what waits starts with no frame and no further claim ----------------------------

    private var timerDueAt: Long? = null
    private val wait = MotorWait(arbiter, clock = { now }, post = { timerDueAt = now + it }, removePosted = { timerDueAt = null })

    /** Lets time pass as the main loop does: only a posted timer runs anything. */
    private fun runTimersUntil(end: Long) {
        while (true) {
            val due = timerDueAt?.takeIf { it <= end } ?: break
            timerDueAt = null
            now = maxOf(now, due)
            wait.onDue()
        }
        now = end
    }

    private fun playsAt(start: Long, block: (log: MutableList<Pair<String, Long>>) -> Unit): List<Pair<String, Long>> {
        now = start
        return mutableListOf<Pair<String, Long>>().also(block)
    }

    @Test
    fun `a fault rhythm starting leaves the timer running for the navigation rhythm behind it`() {
        val log = playsAt(1_000) { log ->
            arbiter.claimUnconditionally(durationMs = 100)
            arbiter.claimOrReserve(priority = 0, durationMs = 200) { log += "fault" to now }
            wait.reschedule()
            arbiter.claimOrQueueNavigation(priority = 3, durationMs = 60, expiresAtMs = 2_000, start = { log += "navigation" to now }, onQueuedResult = {})
            wait.reschedule()
            runTimersUntil(1_500)
        }
        assertEquals(listOf("fault" to 1_100L, "navigation" to 1_300L), log)
        assertNull(timerDueAt)
    }

    @Test
    fun `a cancelled reservation leaves the timer running for the navigation rhythm waiting`() {
        val log = playsAt(1_000) { log ->
            arbiter.claimUnconditionally(durationMs = 100)
            arbiter.claimOrReserve(priority = 0, durationMs = 200) { log += "fault" to now }
            arbiter.claimOrQueueNavigation(priority = 3, durationMs = 60, expiresAtMs = 2_000, start = { log += "navigation" to now }, onQueuedResult = {})
            wait.reschedule()
            arbiter.cancelReservation()
            wait.reschedule()
            runTimersUntil(1_500)
        }
        assertEquals(listOf("navigation" to 1_100L), log)
    }

    @Test
    fun `when the motor is taken again before the timer is due, the timer looks again`() {
        val log = playsAt(1_000) { log ->
            arbiter.claimUnconditionally(durationMs = 100)
            arbiter.claimOrQueueNavigation(priority = 3, durationMs = 60, expiresAtMs = 2_000, start = { log += "navigation" to now }, onQueuedResult = {})
            wait.reschedule()
            now = 1_050
            arbiter.claimUnconditionally(durationMs = 200)
            runTimersUntil(1_500)
        }
        assertEquals(listOf("navigation" to 1_250L), log)
    }

    @Test
    fun `with nothing waiting no timer is left`() {
        arbiter.claimUnconditionally(durationMs = 100)
        wait.reschedule()
        assertNull(timerDueAt)
    }

    @Test
    fun `held off by another navigation rhythm it is refused, as ever`() {
        val arbiter = HapticArbiter { 0L }
        assertEquals(NavigationClaim.STARTED, arbiter.navigation("a", 24))
        assertEquals(NavigationClaim.REFUSED, arbiter.navigation("b", 24))
        assertEquals(NavigationClaim.STARTED, arbiter.navigation("c", 28))
    }

    @Test
    fun `navigation vibration off drops it, and a stop drops it without a word`() {
        val arbiter = HapticArbiter { 0L }
        arbiter.claimUnconditionally(940)
        arbiter.navigation("a", 24)
        arbiter.dropQueuedNavigation()
        assertEquals(false, results["a"])

        arbiter.navigation("b", 24)
        arbiter.stop()
        assertEquals(null, results["b"])
        assertEquals(null, arbiter.reservationFreeAtMs())
    }
}
