package com.sailens.shell.capture

import android.view.KeyEvent
import com.sailens.camera.CameraCharacteristicsProvider
import com.sailens.guidance.trace.capture.CaptureManifest
import com.sailens.guidance.trace.capture.CaptureSchema
import com.sailens.guidance.trace.capture.CaptureSessionReader
import com.sailens.guidance.trace.capture.MarkerSource
import com.sailens.guidance.trace.capture.orThrow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.zip.ZipFile

class FieldCaptureToolingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val root by lazy { tmp.newFolder("captures") }
    private val exports by lazy { File(tmp.root, "capture_exports") }
    private val frames = FakeFrameSource()
    private val clock = FakeClock()

    private fun controller(io: CoroutineDispatcher = Dispatchers.IO) = FieldCaptureController(
        root = root,
        isEnabled = { true },
        frameSource = frames,
        cameraFacts = CameraCharacteristicsProvider { null },
        sensors = FakeSensors(),
        encoder = JpegEncoder { image, _ -> ByteArray(image.width) },
        clock = clock,
        deviceInfo = TEST_DEVICE,
        log = RecordingLog(),
        exporter = CaptureExporter(root, exports),
        ioDispatcher = io,
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
    fun `opening the list clears export ZIPs older than a day and leaves fresh ones for the share target`() = runBlocking {
        val capture = controller()
        recorded(capture, "a")
        recorded(capture, "b")
        val stale = capture.export("a")
        val fresh = capture.export("b")
        val hour = 60L * 60 * 1000
        stale.setLastModified(clock.wallMs() - 25 * hour)
        fresh.setLastModified(clock.wallMs() - 1 * hour)

        capture.runMaintenance().join()

        assertFalse(stale.exists())
        assertTrue(fresh.isFile)
        assertTrue("the session itself stays", File(root, "a").isDirectory)
    }

    @Test
    fun `finished sessions are read-only while a capture is recording`() = runBlocking {
        val capture = controller()
        recorded(capture, "done")
        capture.onSessionStarted("live", null)!!.join()

        assertFalse(capture.setPinned("done", true))
        assertFalse(capture.delete("done"))
        try {
            capture.export("done")
            fail("exporting while recording must be refused")
        } catch (expected: IOException) {
        }
        assertTrue(File(root, "done").isDirectory)
        assertFalse(capture.listSessions().first { it.sessionId == "done" }.pinned)

        capture.onSessionFinished().join()
        assertTrue("allowed again once the recording ends", capture.setPinned("done", true))
    }

    @Test
    fun `a capture never runs alongside an export - one that starts anyway ends at once, visibly`() = runBlocking {
        val io = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val capture = controller(io)
            recorded(capture, "a")
            val release = CountDownLatch(1)
            io.executor.execute { release.await() } // holds the ZIP build until the capture has tried to start

            val export = async(Dispatchers.Default) { capture.export("a") }
            awaitTrue("export under way") { capture.isManagingFiles.value }
            capture.onSessionStarted("b", null)!!.join()
            assertFalse("no recording while exporting", capture.isCapturing.value)

            release.countDown()
            assertTrue(export.await().isFile)
            assertFalse(capture.isManagingFiles.value)
            val b = CaptureSessionReader.read(File(root, "b")).orThrow().manifest
            assertFalse(b.complete)
            assertEquals(FieldCaptureController.EXPORT_IN_PROGRESS, b.failureReason)
        } finally {
            io.close()
        }
    }

    @Test
    fun `an export that cannot mark its session exported fails and leaves no ZIP to share`() = runBlocking {
        val capture = controller()
        recorded(capture, "a")
        File(File(root, "a"), CaptureSchema.MANIFEST_FILE).writeText("{ not json")

        try {
            capture.export("a")
            fail("the session would still expire, so the export must not be offered for sharing")
        } catch (expected: IOException) {
        }
        assertFalse(File(exports, "capture_a.zip").exists())
        assertFalse(capture.isManagingFiles.value)
    }

    // ---- markers -------------------------------------------------------------------------------

    @Test
    fun `a marker keeps the time and frame of the press even when the write comes later`() = runBlocking {
        val capture = controller()
        capture.onSessionStarted("s", null)!!.join()
        frames.emit(yuvFrame(7))
        awaitTrue("frame stored") { capture.observeMarker(MarkerSource.VOLUME_DOWN)?.lastStoredFrameSeq == 7L }

        val observation = capture.observeMarker(MarkerSource.VOLUME_DOWN)!!
        clock.advanceMs(5_000) // the writer was busy; the write lands 5 s later
        frames.emit(yuvFrame(8))
        awaitTrue("newer frame stored") { capture.observeMarker(MarkerSource.VOLUME_DOWN)?.lastStoredFrameSeq == 8L }
        assertTrue(capture.writeMarker(observation))
        capture.onSessionFinished().join()

        val marker = CaptureSessionReader.read(File(root, "s")).orThrow().markers.single()
        assertEquals(observation.wallMs, marker.wallMs)
        assertEquals(observation.elapsedRealtimeNanos, marker.elapsedRealtimeNanos)
        assertEquals(7L, marker.lastStoredFrameSeq)
    }

    @Test
    fun `a marker observed in a session that has ended is not written anywhere`() = runBlocking {
        val capture = controller()
        capture.onSessionStarted("first", null)!!.join()
        val late = capture.observeMarker(MarkerSource.SCREEN_BUTTON)!!
        capture.onSessionFinished().join()
        assertFalse("session ended", capture.writeMarker(late))

        capture.onSessionStarted("second", null)!!.join()
        assertFalse("never lands in the next session", capture.writeMarker(late))
        capture.onSessionFinished().join()

        assertTrue(CaptureSessionReader.read(File(root, "first")).orThrow().markers.isEmpty())
        assertTrue(CaptureSessionReader.read(File(root, "second")).orThrow().markers.isEmpty())
        assertEquals(null, capture.observeMarker(MarkerSource.SCREEN_BUTTON))
    }
}
