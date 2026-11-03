package com.sailens.guidance.delivery.fingerprint

import com.sailens.guidance.trace.regression.RegressionReplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The guards around a capture fingerprint run: only a whole, exact replay counts, and a baseline is never overwritten. */
class FingerprintFilesTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private fun report(replayed: Int, stoppedAt: Long? = null, mismatch: RegressionReplay.Mismatch? = null, approximate: List<Long> = emptyList()) =
        RegressionReplay.Report(replayed, decisions = replayed, firstMismatch = mismatch, approximateFrames = approximate, stoppedAtLostSeq = stoppedAt)

    @Test
    fun `only a replay of every recorded frame, exact, counts`() {
        assertNull(replayProblem(report(replayed = 120), recordedFrames = 120))
        assertNotNull("stopped before a lost frame, nothing replayed", replayProblem(report(replayed = 0, stoppedAt = 1), recordedFrames = 120))
        assertNotNull("stopped before a lost frame mid-way", replayProblem(report(replayed = 60, stoppedAt = 61), recordedFrames = 120))
        assertNotNull("fewer frames than recorded", replayProblem(report(replayed = 119), recordedFrames = 120))
        assertNotNull("a mismatch", replayProblem(report(replayed = 10, mismatch = RegressionReplay.Mismatch(10, "events", "a", "b")), recordedFrames = 120))
        assertNotNull("an approximate frame", replayProblem(report(replayed = 120, approximate = listOf(7)), recordedFrames = 120))
    }

    @Test
    fun `a baseline that is the output directory is refused before anything is written`() {
        val directory = temporary.newFolder("fingerprint")
        val baselineFile = File(directory, "a.off.v1.txt").apply { writeText("1 decided []\n") }
        for (alias in listOf(directory, File(directory, "../${directory.name}"), File(directory.path + File.separator))) {
            try {
                FingerprintFiles(directory, alias)
                fail("$alias was accepted as the baseline of $directory")
            } catch (expected: IllegalArgumentException) {
                // refused
            }
        }
        assertEquals("the baseline was not touched", "1 decided []\n", baselineFile.readText())
    }

    @Test
    fun `a file is compared with the baseline, then written`() {
        val baseline = temporary.newFolder("baseline")
        val out = temporary.newFolder("out")
        File(baseline, "a.off.v1.txt").writeText("1 decided []\n2 decided []\n")
        val files = FingerprintFiles(out, baseline)
        assertNull(files.record("a.off.v1.txt", listOf("1 decided []", "2 decided []")))
        assertNotNull(files.record("a.off.v1.txt", listOf("1 decided []", "2 stop")))
        assertNotNull("no baseline file", files.record("b.off.v1.txt", listOf("1 decided []")))
        assertEquals("1 decided []\n2 stop\n", File(out, "a.off.v1.txt").readText())
        assertEquals("the baseline was not touched", "1 decided []\n2 decided []\n", File(baseline, "a.off.v1.txt").readText())
    }
}
