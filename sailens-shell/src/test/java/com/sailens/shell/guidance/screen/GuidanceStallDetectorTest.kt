package com.sailens.shell.guidance.screen

import com.sailens.shell.guidance.safety.StallCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceStallDetectorTest {
    private val detector = GuidanceStallDetector(
        startupTimeoutMs = 20_000,
        stallAfterMs = 2_500,
        repeatEveryMs = 10_000,
    )

    @Test
    fun `a session that keeps producing results never alarms`() {
        detector.start(nowMs = 0)
        var now = 0L
        repeat(100) {
            now += 100
            detector.onResult(now)
            assertFalse(detector.alarmDue(now))
        }
    }

    @Test
    fun `a session that goes quiet after producing results alarms once the stall limit passes`() {
        detector.start(nowMs = 0)
        detector.onResult(nowMs = 1_000)

        assertFalse(detector.alarmDue(nowMs = 3_000))
        assertTrue(detector.alarmDue(nowMs = 3_500))
    }

    @Test
    fun `model loading gets the longer startup allowance`() {
        detector.start(nowMs = 0)

        assertFalse(detector.alarmDue(nowMs = 10_000))
        assertTrue(detector.alarmDue(nowMs = 20_000))
    }

    @Test
    fun `the alarm repeats while the stall lasts, but not on every poll`() {
        detector.start(nowMs = 0)
        detector.onResult(nowMs = 0)

        assertTrue(detector.alarmDue(nowMs = 3_000))
        assertFalse(detector.alarmDue(nowMs = 3_500))
        assertFalse(detector.alarmDue(nowMs = 12_900))
        assertTrue(detector.alarmDue(nowMs = 13_000))
    }

    @Test
    fun `results resuming re-arm the alarm and a stopped detector is silent`() {
        detector.start(nowMs = 0)
        detector.onResult(nowMs = 0)
        assertTrue(detector.alarmDue(nowMs = 3_000))

        detector.onResult(nowMs = 4_000)
        assertFalse(detector.alarmDue(nowMs = 5_000))
        assertTrue(detector.alarmDue(nowMs = 6_600))

        detector.stop()
        assertFalse(detector.alarmDue(nowMs = 60_000))
    }

    @Test
    fun `the condition is the limit passing - the startup one before a result, the stall one after - and nothing while not watching`() {
        assertEquals(StallCondition.NONE, detector.condition(nowMs = 100_000))
        detector.start(nowMs = 0)
        assertEquals(StallCondition.NONE, detector.condition(nowMs = 19_999))
        assertEquals(StallCondition.STARTUP_TIMEOUT, detector.condition(nowMs = 20_000))
        detector.onResult(nowMs = 21_000)
        assertEquals(StallCondition.NONE, detector.condition(nowMs = 23_499))
        assertEquals(StallCondition.NO_RESULTS, detector.condition(nowMs = 23_500))
        detector.stop()
        assertEquals(StallCondition.NONE, detector.condition(nowMs = 60_000))
    }

    @Test
    fun `reading the condition records no alarm and leaves the repeat alone`() {
        detector.start(nowMs = 0)
        detector.onResult(nowMs = 1_000)
        repeat(50) { detector.condition(nowMs = 3_500L + it * 100) }
        assertTrue("the first alarm is still due", detector.alarmDue(nowMs = 8_500))
        repeat(50) { detector.condition(nowMs = 8_600L + it * 100) }
        assertFalse(detector.alarmDue(nowMs = 18_499))
        assertTrue("the repeat comes when it would have", detector.alarmDue(nowMs = 18_500))
        assertEquals(StallCondition.NO_RESULTS, detector.condition(nowMs = 18_500))
    }
}
