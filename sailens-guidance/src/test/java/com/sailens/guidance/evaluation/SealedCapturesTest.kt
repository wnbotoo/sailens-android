package com.sailens.guidance.evaluation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** det-first D1.8: a marker anywhere above a capture seals it. */
class SealedCapturesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a capture under a sealed folder is sealed, one beside it is not`() {
        val sealedSession = File(tmp.root, "detfirst-d18/sealed/session-a").apply { mkdirs() }
        val calibrationSession = File(tmp.root, "detfirst-d18/calibration/session-b").apply { mkdirs() }
        File(tmp.root, "detfirst-d18/sealed/${SealedCaptures.MARKER}").createNewFile()

        assertTrue(SealedCaptures.isSealed(sealedSession))
        assertFalse(SealedCaptures.isSealed(calibrationSession))
    }

    @Test
    fun `the marker in the capture's own folder seals it too`() {
        val session = tmp.newFolder("session-c")
        assertFalse(SealedCaptures.isSealed(session))
        File(session, SealedCaptures.MARKER).createNewFile()
        assertTrue(SealedCaptures.isSealed(session))
    }
}
