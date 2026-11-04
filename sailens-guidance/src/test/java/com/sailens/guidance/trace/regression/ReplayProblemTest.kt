package com.sailens.guidance.trace.regression

import com.sailens.guidance.trace.capture.RegressionStats
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The gate of every capture replay test: only a replay of every recorded frame, exact, counts. */
class ReplayProblemTest {

    private fun report(replayed: Int, stoppedAt: Long? = null, mismatch: RegressionReplay.Mismatch? = null, approximate: List<Long> = emptyList()) =
        RegressionReplay.Report(replayed, decisions = replayed, firstMismatch = mismatch, approximateFrames = approximate, stoppedAtLostSeq = stoppedAt)

    private val whole = RegressionStats(framesRecorded = 120)

    @Test
    fun `only a replay of every recorded frame, exact, counts`() {
        assertNull(replayProblem(report(replayed = 120), readFrames = 120, stats = whole))
        assertNotNull("stopped before a lost frame, nothing replayed", replayProblem(report(replayed = 0, stoppedAt = 1), readFrames = 120, stats = whole))
        assertNotNull("stopped before a lost frame mid-way", replayProblem(report(replayed = 60, stoppedAt = 61), readFrames = 120, stats = whole))
        assertNotNull("fewer frames replayed than read", replayProblem(report(replayed = 119), readFrames = 120, stats = whole))
        assertNotNull("a mismatch", replayProblem(report(replayed = 10, mismatch = RegressionReplay.Mismatch(10, "events", "a", "b")), readFrames = 120, stats = whole))
        assertNotNull("an approximate frame", replayProblem(report(replayed = 120, approximate = listOf(7)), readFrames = 120, stats = whole))
    }

    /** A loss at the tail never stops the replay, which has nothing left to replay: only the manifest knows of it. */
    @Test
    fun `a capture short of its tail is refused by what the manifest says`() {
        assertNotNull(
            "the manifest counts a frame the file does not have",
            replayProblem(report(replayed = 119), readFrames = 119, stats = RegressionStats(framesRecorded = 120)),
        )
        assertNotNull(
            "file and manifest agree, but the manifest says a frame after the last one kept was lost",
            replayProblem(report(replayed = 119), readFrames = 119, stats = RegressionStats(framesRecorded = 119, framesLost = 1, firstLostSeq = 120)),
        )
        assertNotNull(
            "a loss counted without its seq",
            replayProblem(report(replayed = 119), readFrames = 119, stats = RegressionStats(framesRecorded = 119, framesLost = 1)),
        )
    }
}
