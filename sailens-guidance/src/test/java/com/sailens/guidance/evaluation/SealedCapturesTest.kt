package com.sailens.guidance.evaluation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

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
    fun `dot-dot is resolved before looking for the marker`() {
        File(tmp.root, "sealed").mkdirs()
        File(tmp.root, "sealed/${SealedCaptures.MARKER}").createNewFile()
        File(tmp.root, "calibration/session-b").mkdirs()

        assertFalse(SealedCaptures.isSealed(File(tmp.root, "sealed/../calibration/session-b")))
    }

    @Test
    fun `an alias of a sealed capture is sealed`() {
        val target = File(tmp.root, "sealed/session-a").apply { mkdirs() }
        File(tmp.root, "sealed/${SealedCaptures.MARKER}").createNewFile()
        val alias = File(tmp.root, "alias-a")
        assumeTrue("cannot create a directory link here", link(alias, target))

        assertTrue(SealedCaptures.isSealed(alias))
        // A metric pointed at the alias finds the capture and skips it.
        File(alias, "manifest.json").writeText("{}")
        val split = SealedCaptures.forMetrics(alias, unseal = false)
        assertTrue(split.open.isEmpty())
        assertTrue(split.sealed.isNotEmpty())
        assertTrue("unsealed on request", SealedCaptures.forMetrics(alias, unseal = true).open.isNotEmpty())
    }

    /** A symbolic link, or on Windows without the privilege for one, a junction. */
    private fun link(alias: File, target: File): Boolean {
        if (runCatching { Files.createSymbolicLink(alias.toPath(), target.toPath()) }.isSuccess) return true
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return false
        val process = ProcessBuilder("cmd", "/c", "mklink", "/J", alias.absolutePath, target.absolutePath).redirectErrorStream(true).start()
        process.inputStream.readBytes()
        return process.waitFor() == 0 && alias.isDirectory
    }

    @Test
    fun `the marker in the capture's own folder seals it too`() {
        val session = tmp.newFolder("session-c")
        assertFalse(SealedCaptures.isSealed(session))
        File(session, SealedCaptures.MARKER).createNewFile()
        assertTrue(SealedCaptures.isSealed(session))
    }
}
