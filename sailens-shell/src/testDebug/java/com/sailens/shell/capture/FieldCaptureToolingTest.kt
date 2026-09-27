package com.sailens.shell.capture

import android.view.KeyEvent
import com.sailens.camera.CameraCharacteristicsProvider
import com.sailens.guidance.trace.capture.CaptureManifest
import com.sailens.guidance.trace.capture.CaptureSchema
import com.sailens.guidance.trace.capture.CaptureSessionReader
import com.sailens.guidance.trace.capture.orThrow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

class FieldCaptureToolingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val root by lazy { tmp.newFolder("captures") }
    private val exports by lazy { File(tmp.root, "capture_exports") }
    private val frames = FakeFrameSource()

    private fun controller() = FieldCaptureController(
        root = root,
        isEnabled = { true },
        frameSource = frames,
        cameraFacts = CameraCharacteristicsProvider { null },
        sensors = FakeSensors(),
        encoder = JpegEncoder { image, _ -> ByteArray(image.width) },
        clock = FakeClock(),
        deviceInfo = TEST_DEVICE,
        log = RecordingLog(),
        exporter = CaptureExporter(root, exports),
    )

    /** A finished capture with a couple of frames, recorded through the real engine. */
    private suspend fun recorded(capture: FieldCaptureController, id: String) {
        capture.onSessionStarted(id, null)!!.join()
        frames.emit(yuvFrame(1))
        awaitTrue("frame released") { 1L in frames.released }
        capture.onSessionFinished().join()
        frames.released.clear()
    }

    // ---- Volume Down ---------------------------------------------------------------------------

    @Test
    fun `volume down marks once per press, only while capturing, and never steals other keys`() {
        val down = KeyEvent.ACTION_DOWN
        val up = KeyEvent.ACTION_UP
        val volDown = KeyEvent.KEYCODE_VOLUME_DOWN
        val volUp = KeyEvent.KEYCODE_VOLUME_UP

        assertEquals(MarkerKeyDecision.MARK, markerKeyDecision(true, volDown, down, repeatCount = 0))
        assertEquals("holding the key is one press", MarkerKeyDecision.CONSUME, markerKeyDecision(true, volDown, down, 3))
        assertEquals("the key-up is swallowed, not counted", MarkerKeyDecision.CONSUME, markerKeyDecision(true, volDown, up, 0))
        assertEquals("normal volume control when not capturing", MarkerKeyDecision.PASS, markerKeyDecision(false, volDown, down, 0))
        assertEquals("volume up is never ours", MarkerKeyDecision.PASS, markerKeyDecision(true, volUp, down, 0))
    }

    // ---- capture list --------------------------------------------------------------------------

    @Test
    fun `the list shows finished and active sessions, and the active one cannot be deleted or exported`() = runBlocking {
        val capture = controller()
        recorded(capture, "done")
        capture.onSessionStarted("live", null)!!.join()

        val sessions = capture.listSessions().associateBy { it.sessionId }
        assertTrue(sessions.getValue("done").complete)
        assertFalse(sessions.getValue("done").active)
        assertTrue(sessions.getValue("live").active)

        assertFalse(capture.delete("live"))
        assertFalse(capture.setPinned("live", true))
        try {
            capture.export("live")
            fail("exporting a recording capture must be refused")
        } catch (expected: IOException) {
        }
        assertTrue(File(root, "live").isDirectory)
        capture.onSessionFinished().join()
    }

    @Test
    fun `keeping and deleting a finished session`() = runBlocking {
        val capture = controller()
        recorded(capture, "a")

        assertTrue(capture.setPinned("a", true))
        assertTrue(capture.listSessions().single().pinned)
        assertTrue(capture.setPinned("a", false))
        assertFalse(capture.listSessions().single().pinned)

        assertTrue(capture.delete("a"))
        assertTrue(capture.listSessions().isEmpty())
    }

    @Test
    fun `export packs the whole session under its id, outside the capture directory, and marks it exported`() = runBlocking {
        val capture = controller()
        recorded(capture, "a")

        val zip = capture.export("a")

        assertEquals(exports.canonicalFile, zip.parentFile.canonicalFile)
        val expected = File(root, "a").walkTopDown().filter { it.isFile }
            .map { "a/" + it.relativeTo(File(root, "a")).invariantSeparatorsPath }
            .toSet() - "a/${CaptureSchema.MANIFEST_FILE}" // rewritten after export
        val entries = ZipFile(zip).use { file -> file.entries().toList().map { it.name }.toSet() }
        assertTrue("missing: ${expected - entries}", entries.containsAll(expected))
        assertTrue(entries.contains("a/${CaptureSchema.MANIFEST_FILE}"))
        assertTrue("only this session's files", entries.all { it.startsWith("a/") && !it.endsWith(".tmp") })

        val manifest: CaptureManifest = CaptureSessionReader.read(File(root, "a")).orThrow().manifest
        assertNotNull(manifest.exportedAtWallMs)
        assertTrue(capture.listSessions().single().exported)
    }

    @Test
    fun `opening the list clears old export ZIPs`() = runBlocking {
        val capture = controller()
        recorded(capture, "a")
        val zip = capture.export("a")
        assertTrue(zip.isFile)

        capture.runMaintenance().join()

        assertFalse(zip.exists())
        assertTrue("the session itself stays", File(root, "a").isDirectory)
    }
}
