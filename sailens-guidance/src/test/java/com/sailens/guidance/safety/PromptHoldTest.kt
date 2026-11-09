package com.sailens.guidance.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PromptHoldTest {

    private val hold = PromptHold<String>()
    private var seq = 0L

    /** A usable result at [atMs], received [lagMs] before it; [receivedAtMs] overrides the receipt. */
    private fun result(atMs: Long, lagMs: Long = 0, receivedAtMs: Long? = atMs - lagMs) =
        hold.step(atMs, cause = null, result = PromptHold.Result(++seq, receivedAtMs))

    /** Results every [stepMs] from [fromMs]; the index of the one that released the hold, or null. */
    private fun resultsUntilRelease(fromMs: Long, stepMs: Long, max: Int = 100): Int? {
        repeat(max) { i ->
            if (result(fromMs + i * stepMs) is PromptHold.Change.Released) return i
        }
        return null
    }

    @Test
    fun `a stop begins once, goes on while its cause holds, and makes no progress meanwhile`() {
        assertEquals(PromptHold.Change.StopBegan, hold.step(1_000, "camera", null))
        assertEquals(PromptHold.Change.None, hold.step(1_100, "camera", PromptHold.Result(1, 1_100)))
        val held = checkNotNull(hold.held)
        assertEquals(1_000L, held.sinceMs)
        assertTrue(held.stopping)
        assertEquals(0, held.fresh)
    }

    @Test
    fun `nothing is held before a stop`() {
        assertEquals(PromptHold.Change.None, result(1_000))
        assertNull(hold.held)
    }

    @Test
    fun `the cause going away without a result starts the recovery`() {
        hold.step(1_000, "lifecycle", null)
        hold.step(2_000, null, null)
        val held = checkNotNull(hold.held)
        assertEquals(false, held.stopping)
        assertEquals(1_000L, held.sinceMs)
        assertEquals(0, held.fresh)
    }

    @Test
    fun `background twice with no result in between - the second stop has its own start`() {
        assertEquals(PromptHold.Change.StopBegan, hold.step(1_000, "lifecycle", null))
        hold.step(2_000, null, null)
        assertEquals(PromptHold.Change.StopBegan, hold.step(2_300, "lifecycle", null))
        hold.step(3_000, null, null)
        assertEquals(2_300L, hold.held?.sinceMs)
        // A frame received at 2100, between the two stops, is stale.
        result(3_050, receivedAtMs = 2_100)
        assertEquals(0, hold.held?.fresh)
    }

    @Test
    fun `a different cause begins a new stop`() {
        hold.step(1_000, "camera", null)
        assertEquals(PromptHold.Change.StopBegan, hold.step(1_500, "stall", null))
        assertEquals("stall", hold.held?.cause)
        assertEquals(1_500L, hold.held?.sinceMs)
    }

    @Test
    fun `the same cause after a recovery began begins a new stop`() {
        hold.step(1_000, "camera", null)
        result(2_000)
        result(2_100)
        assertEquals(PromptHold.Change.StopBegan, hold.step(2_200, "camera", null))
        assertEquals(2_200L, hold.held?.sinceMs)
        assertEquals(0, hold.held?.fresh)
    }

    @Test
    fun `stale results - received at or before the stop began - neither count nor reset`() {
        hold.step(1_000, "stall", null)
        result(1_500, receivedAtMs = 1_000)
        result(1_510, receivedAtMs = 900)
        assertEquals(0, hold.held?.fresh)
        result(1_600)
        result(1_610, receivedAtMs = 950)
        assertEquals(1, hold.held?.fresh)
    }

    @Test
    fun `forty stale results first, then fresh ones release as usual`() {
        hold.step(1_000, "stall", null)
        repeat(40) { result(1_100L + it, receivedAtMs = 500L + it) }
        assertEquals(0, hold.held?.fresh)
        assertEquals(5, resultsUntilRelease(2_000, 100))
    }

    @Test
    fun `three results are not enough until they span 500 ms, nor is 500 ms with two`() {
        hold.step(0, "camera", null)
        assertEquals("10 fps: the sixth (500 ms)", 5, resultsUntilRelease(1_000, 100))
        hold.step(10_000, "camera", null)
        assertEquals("2 fps: the third (1000 ms), two span 500 ms but are two", 2, resultsUntilRelease(11_000, 500))
        hold.step(20_000, "camera", null)
        assertEquals("1 fps: the third (2000 ms)", 2, resultsUntilRelease(21_000, 1_000))
        hold.step(30_000, "camera", null)
        assertEquals("about 22 fps: the twelfth (506 ms)", 11, resultsUntilRelease(31_000, 46))
        hold.step(40_000, "camera", null)
        assertEquals("7 fps: the fifth (572 ms)", 4, resultsUntilRelease(41_000, 143))
    }

    @Test
    fun `499 ms is short, 500 ms is enough`() {
        hold.step(0, "camera", null)
        result(1_000)
        result(1_200)
        assertEquals(PromptHold.Change.None, result(1_499))
        val released = result(1_500)
        assertTrue(released is PromptHold.Change.Released)
        assertNull(hold.held)
    }

    @Test
    fun `the release carries the evidence, the releasing result counted in`() {
        hold.step(100, "camera", null)
        result(1_000)
        result(1_100)
        result(1_200)
        result(1_300)
        val change = result(1_550)
        val release = (change as PromptHold.Change.Released).release
        assertEquals(PromptHold.Release("camera", 100, 5, 1_000, 1_550, 0, seq), release)
        assertEquals(550L, release.freshSpanMs)
    }

    @Test
    fun `backlogged fresh results arriving together release at once - the span is picture time`() {
        hold.step(1_000, "stall", null)
        result(5_000, receivedAtMs = 1_100)
        result(5_001, receivedAtMs = 1_400)
        val change = result(5_002, receivedAtMs = 1_700)
        assertTrue(change is PromptHold.Change.Released)
    }

    @Test
    fun `an unknown receipt counts at its arrival, and says so`() {
        hold.step(1_000, "camera", null)
        result(2_000)
        result(2_200, receivedAtMs = null)
        assertEquals(1, hold.held?.receiptFallbacks)
        assertEquals(2_200L, hold.held?.lastFreshReceivedAtMs)
        val release = (result(2_500) as PromptHold.Change.Released).release
        assertEquals(1, release.receiptFallbacks)
    }

    @Test
    fun `clear forgets the hold`() {
        hold.step(1_000, "camera", null)
        hold.clear()
        assertNull(hold.held)
        assertEquals(PromptHold.Change.None, result(2_000))
    }

    /**
     * Over random inputs: a release is always by a result, every result counted since the stop began was received
     * after it, and the release meets both thresholds; while results keep coming with fresh receipts the hold ends.
     */
    @Test
    fun `random inputs never release on stale evidence, and steady results always release`() {
        val random = Random(20261109)
        repeat(2_000) {
            val hold = PromptHold<Int>()
            var now = 0L
            var since: Long? = null
            val countedReceipts = mutableListOf<Long>()
            repeat(60) {
                now += 1 + random.nextLong(200)
                when (random.nextInt(4)) {
                    0 -> {
                        val cause = random.nextInt(2)
                        val before = hold.held
                        if (hold.step(now, cause, null) == PromptHold.Change.StopBegan) {
                            since = now
                            countedReceipts.clear()
                        } else {
                            assertTrue(before != null && before.stopping && before.cause == cause)
                        }
                    }
                    else -> {
                        val received = if (random.nextInt(10) == 0) null else now - random.nextLong(3_000)
                        val before = hold.held
                        val change = hold.step(now, null, PromptHold.Result(it.toLong(), received))
                        val effective = received ?: now
                        if (before != null && effective > before.sinceMs) countedReceipts += effective
                        if (change is PromptHold.Change.Released) {
                            val release = change.release
                            assertEquals(since, release.sinceMs)
                            assertTrue(countedReceipts.all { r -> r > release.sinceMs })
                            assertTrue(release.fresh >= 3 && release.freshSpanMs >= 500)
                            assertEquals(countedReceipts.size, release.fresh)
                            countedReceipts.clear()
                        }
                    }
                }
            }
            if (hold.held != null) {
                repeat(20) { i ->
                    now += 100
                    hold.step(now, null, PromptHold.Result(1_000L + i, now))
                }
                assertNull("steady fresh results end the hold", hold.held)
            }
        }
    }
}
