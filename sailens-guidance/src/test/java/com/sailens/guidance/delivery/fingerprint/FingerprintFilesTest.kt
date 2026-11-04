package com.sailens.guidance.delivery.fingerprint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The guards around a capture fingerprint run: a baseline is never overwritten. That only a whole, exact replay counts
 * is the shared gate's, tested in `ReplayProblemTest`.
 */
class FingerprintFilesTest {

    @get:Rule
    val temporary = TemporaryFolder()

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
