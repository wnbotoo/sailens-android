package com.sailens.shell.capture

import com.sailens.camera.CameraCharacteristicsProvider
import com.sailens.guidance.trace.capture.CaptureModes
import com.sailens.guidance.model.trace.FrameTrace
import com.sailens.guidance.model.trace.PromptOutcomeTrace
import com.sailens.guidance.model.trace.SessionTraceMetadata
import com.sailens.guidance.model.trace.SessionTraceSummary
import com.sailens.guidance.service.TraceService
import com.sailens.guidance.trace.capture.AnchorReason
import com.sailens.guidance.trace.capture.CaptureSessionReader
import com.sailens.guidance.trace.capture.FrameEncoding
import com.sailens.guidance.trace.capture.MarkerSource
import com.sailens.guidance.trace.capture.orThrow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FieldCaptureControllerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val frames = FakeFrameSource()
    private val sensors = FakeSensors()
    private val clock = FakeClock()
    private val log = RecordingLog()

    private fun controller(
        enabled: () -> Boolean = { true },
        sessionMode: (() -> String?)? = null,
        encoder: JpegEncoder = JpegEncoder { image, _ -> ByteArray(image.width) },
        config: FieldCaptureConfig = FieldCaptureConfig(),
        retention: CaptureRetention = CaptureRetention(tmp.root),
    ) = FieldCaptureController(
        root = tmp.root,
        sessionMode = sessionMode ?: { if (enabled()) CaptureModes.FIELD_EVIDENCE else null },
        frameSource = frames,
        cameraFacts = CameraCharacteristicsProvider { null },
        sensors = sensors,
        encoder = encoder,
        clock = clock,
        deviceInfo = TEST_DEVICE,
        log = log,
        config = config,
        retention = retention,
    )

    /** A finished, unpinned session on disk, started [ageMs] before the fake clock's now. */
    private fun oldSession(id: String, ageMs: Long, imageBytes: Int) {
        CaptureSessionWriter(
            File(tmp.root, id),
            com.sailens.guidance.trace.capture.CaptureManifest(
                sessionId = id,
                captureMode = com.sailens.guidance.trace.capture.CaptureModes.FIELD_EVIDENCE,
                startedWallMs = clock.wallMs() - ageMs,
                startedElapsedRealtimeNanos = 0,
                deviceManufacturer = "Acme",
                deviceModel = "Phone",
                sdkInt = 35,
                complete = true,
            ),
        ).use { it.writeFrameImage("000001.jpg", ByteArray(imageBytes)) }
    }

    @Test
    fun `expired captures are deleted at start-up even with the switch off`() = runBlocking {
        oldSession("expired", ageMs = CaptureRetention.DEFAULT_MAX_AGE_MS + 1, imageBytes = 10)
        oldSession("fresh", ageMs = 1_000, imageBytes = 10)

        controller(enabled = { false }).runMaintenance().join()

        assertEquals(listOf("fresh"), tmp.root.list()!!.toList())
    }

    @Test
    fun `the storage cap is enforced during a capture, older sessions first, then the capture ends`() = runBlocking {
        oldSession("old", ageMs = 60_000, imageBytes = 1_000)
        val capture = controller(
            encoder = JpegEncoder { _, _ -> ByteArray(400) },
            retention = CaptureRetention(tmp.root, maxTotalBytes = 3_000),
        )
        capture.onSessionStarted("s1", null)!!.join()

        var seq = 0L
        awaitTrue("the capture to hit the cap") {
            frames.emit(yuvFrame(++seq))
            Thread.sleep(5)
            log.warnings.any { FieldCaptureController.STORAGE_LIMIT_REACHED in it }
        }
        capture.onSessionFinished().join()

        assertFalse("the older unpinned session is evicted before the capture is ended", File(tmp.root, "old").exists())
        val manifest = read("s1").manifest
        assertFalse(manifest.complete)
        assertEquals(FieldCaptureController.STORAGE_LIMIT_REACHED, manifest.failureReason)
        val onDisk = File(tmp.root, "s1").walk().filter { it.isFile }.sumOf { it.length() }
        assertTrue("stopped near the cap, not at a full disk: $onDisk", onDisk < 3_000 + 1_500)
    }

    @Test
    fun `sensor samples the capture drops are persisted, so gaps can be explained`() = runBlocking {
        val encoding = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val capture = controller(
            encoder = JpegEncoder { image, _ ->
                encoding.countDown()
                unblock.await(5, TimeUnit.SECONDS)
                ByteArray(image.width)
            },
            config = FieldCaptureConfig(sensorQueueCapacity = 2),
        )
        capture.onSessionStarted("s1", null)!!.join()
        awaitTrue("sensors registered") { sensors.onRecord != null }

        frames.emit(yuvFrame(1))
        assertTrue("the writer thread is busy encoding", encoding.await(5, TimeUnit.SECONDS))
        repeat(50) { sensors.emit(it.toLong()) }
        unblock.countDown()
        capture.onSessionFinished().join()

        val session = read("s1")
        val stats = session.manifest.stats
        assertEquals(stats.sensorEvents, session.sensors.size.toLong())
        assertTrue("some samples were dropped: $stats", stats.sensorEventsDropped > 0)
        assertEquals("persisted + dropped explains every sample received", 50L, stats.sensorEvents + stats.sensorEventsDropped)
    }

    private fun read(sessionId: String) = CaptureSessionReader.read(File(tmp.root, sessionId)).orThrow()

    @Test
    fun `with the switch off nothing is captured`() = runBlocking {
        val capture = controller(enabled = { false })

        assertNull(capture.onSessionStarted("s1", "sm8850"))
        capture.onSessionFinished().join()

        assertTrue(tmp.root.list()!!.isEmpty())
    }

    @Test
    fun `a switch that throws is treated as off and never reaches the caller`() {
        val capture = controller(enabled = { error("prefs broken") })

        assertNull(capture.onSessionStarted("s1", null))
    }

    @Test
    fun `a session records frames, sensors and anchors and finishes complete`() = runBlocking {
        val capture = controller()
        capture.onSessionStarted("s1", "sm8850")!!.join()

        (1L..3L).forEach { frames.emit(yuvFrame(it)) }
        awaitTrue("three frames released") { frames.released.size == 3 }
        awaitTrue("sensors registered") { sensors.onRecord != null }
        sensors.emit(10)
        sensors.emit(20)
        capture.onSessionFinished().join()

        val session = read("s1")
        assertTrue(session.manifest.complete)
        assertEquals("sm8850", session.manifest.targetHardwareProfile)
        // Build provenance: the code and the model weights Guidance ran with.
        assertEquals(TEST_DEVICE.gitSha, session.manifest.gitSha)
        assertEquals(TEST_DEVICE.modelArtifacts, session.manifest.modelArtifacts)
        assertEquals(listOf("gravity", "gyroscope"), session.manifest.sensorsAvailable)
        assertEquals(3L, session.manifest.stats.framesOffered)
        assertEquals(
            "every offered frame is either encoded or counted as dropped",
            3L,
            session.manifest.stats.framesEncoded + session.manifest.stats.framesDroppedByEncoder,
        )
        assertEquals(2, session.sensors.size)
        assertEquals(listOf(AnchorReason.START, AnchorReason.END), session.anchors.map { it.reason })
        val first = session.frames.first()
        assertEquals(90, first.rotationDegrees)
        assertEquals(9, first.sensorToBufferTransform?.size)
        assertEquals(listOf(0, 0, 8, 6), first.cropRect)
        assertTrue(File(session.directory, first.file).isFile)
        assertEquals(1, sensors.stopped)
    }

    @Test
    fun `frames are released at once even while the encoder is stuck, and the backlog stays bounded`() = runBlocking {
        val unblock = CountDownLatch(1)
        val capture = controller(encoder = JpegEncoder { image, _ ->
            unblock.await(5, TimeUnit.SECONDS)
            ByteArray(image.width)
        })
        capture.onSessionStarted("s1", null)!!.join()

        (1L..20L).forEach { frames.emit(yuvFrame(it)) }
        awaitTrue("all frames released while the encoder is blocked") { frames.released.size == 20 }
        unblock.countDown()
        capture.onSessionFinished().join()

        val stats = read("s1").manifest.stats
        assertEquals(20L, stats.framesOffered)
        assertTrue("most frames were dropped, not queued: $stats", stats.framesDroppedByEncoder >= 17)
        assertEquals(20L, stats.framesEncoded + stats.framesDroppedByEncoder)
    }

    @Test
    fun `a failing encoder ends the capture as incomplete, and frames are still released`() = runBlocking {
        val capture = controller(encoder = JpegEncoder { _, _ -> throw IOException("disk full") })
        capture.onSessionStarted("s1", null)!!.join()

        frames.emit(yuvFrame(1))
        awaitTrue("the capture to fail") { log.warnings.any { "Field capture failed" in it } }
        frames.emit(yuvFrame(2))
        capture.onSessionFinished().join()

        val manifest = read("s1").manifest
        assertFalse(manifest.complete)
        assertTrue(manifest.failureReason!!, "disk full" in manifest.failureReason!!)
        assertTrue(frames.released.contains(1L))
    }

    @Test
    fun `a new session closes one that never finished as incomplete`() = runBlocking {
        val capture = controller()
        capture.onSessionStarted("s1", null)!!.join()
        capture.onSessionStarted("s2", null)!!.join()
        capture.onSessionFinished().join()

        assertFalse(read("s1").manifest.complete)
        assertTrue(read("s1").manifest.failureReason!!.contains("superseded"))
        assertTrue(read("s2").manifest.complete)
    }

    @Test
    fun `a marker is recorded only while a capture is active`() = runBlocking {
        val capture = controller()
        assertFalse(capture.recordMarker(MarkerSource.VOLUME_DOWN))

        capture.onSessionStarted("s1", null)!!.join()
        assertTrue(capture.recordMarker(MarkerSource.SCREEN_BUTTON))
        capture.onSessionFinished().join()

        val session = read("s1")
        assertEquals(1, session.markers.size)
        assertEquals(MarkerSource.SCREEN_BUTTON, session.markers.single().source)
        assertEquals(1L, session.manifest.stats.markers)
    }

    @Test
    fun `a timing-sync burst stores every frame as raw luma, counts its own mailbox misses and ends on its own`() = runBlocking {
        val capture = controller(
            sessionMode = { CaptureModes.TIMING_SYNC },
            config = FieldCaptureConfig(timingSync = TimingSyncConfig(durationMs = 1_000, maxLongSide = 4)),
        )
        capture.onSessionStarted("burst", null)!!.join()
        assertTrue(capture.isCapturing.value)
        // Seq 3 and 4 were converted and offered but replaced in capture's mailbox.
        listOf(1L, 2L, 5L).forEach { frames.emit(yuvFrame(it)) }
        awaitTrue("frames released") { frames.released.containsAll(listOf(1L, 2L, 5L)) }

        awaitTrue("burst ended by itself") { !capture.isCapturing.value }
        // Read at once: inactive is published only after the files are final.
        val session = read("burst")
        assertEquals(CaptureModes.TIMING_SYNC, session.manifest.captureMode)
        assertTrue(session.manifest.complete)
        assertEquals(AnchorReason.END, session.anchors.last().reason)
        assertEquals(listOf(1L, 2L, 5L), session.frames.map { it.seq })
        session.frames.forEach { frame ->
            assertEquals(FrameEncoding.LUMA8, frame.encoding)
            assertEquals(4 to 3, frame.width to frame.height)
            assertEquals(12L, File(session.directory, frame.file).length())
        }
        assertEquals(3L, session.manifest.stats.framesOffered)
        assertEquals(3L, session.manifest.stats.framesEncoded)
        assertEquals(2L, session.manifest.stats.framesMissedBySubscriber)

        // The session's own end then has nothing to stop and changes nothing.
        capture.onSessionFinished().join()
        assertEquals(session.manifest, read("burst").manifest)
    }

    @Test
    fun `a capture is inactive only once its manifest is final, and field evidence does not claim mailbox misses`() = runBlocking {
        val capture = controller()
        capture.onSessionStarted("s", null)!!.join()
        frames.emit(yuvFrame(1))
        frames.emit(yuvFrame(9)) // a gap the sampled stream makes by design
        awaitTrue("frames released") { frames.released.containsAll(listOf(1L, 9L)) }

        capture.onSessionFinished() // not joined: react to the state flow, as the capture list does
        awaitTrue("inactive") { !capture.isCapturing.value }

        val session = read("s")
        assertTrue(session.manifest.complete)
        assertEquals(AnchorReason.END, session.anchors.last().reason)
        assertEquals(session.frames.size.toLong(), session.manifest.stats.framesEncoded)
        assertNull(session.manifest.stats.framesMissedBySubscriber)
    }

    @Test
    fun `an unknown mode captures nothing and never reaches the caller`() {
        assertNull(controller(sessionMode = { "future_mode" }).onSessionStarted("s", null))
        assertFalse(File(tmp.root, "s").exists())
    }

    @Test
    fun `the trace decorator forwards every call to the base service first`() {
        val calls = mutableListOf<String>()
        val base = object : TraceService {
            override fun startSession(metadata: SessionTraceMetadata) { calls += "start" }
            override fun recordFrame(frameTrace: FrameTrace) { calls += "frame" }
            override fun recordOverlayRender(
                renderedAt: Long, renderMs: Long, overlayMode: String, bitmapRendered: Boolean,
                sourceSequenceNumber: Long, sourcePipelineCompletedAt: Long, sourceAgeMs: Long,
            ) { calls += "overlay" }
            override fun recordPromptOutcome(outcome: PromptOutcomeTrace) { calls += "prompt" }
            override fun recordError(sessionId: String, stage: String, throwable: Throwable) { calls += "error" }
            override fun finishSession(summary: SessionTraceSummary) { calls += "finish" }
        }
        // A capture that cannot even read its switch must not disturb tracing.
        val traced = CapturingTraceService(base, controller(enabled = { error("broken") }))

        traced.startSession(SessionTraceMetadata("s1", 0, "default", "sm8850"))
        traced.recordOverlayRender(0, 0, "none", false)
        traced.recordError("s1", "stage", RuntimeException())

        assertEquals(listOf("start", "overlay", "error"), calls)
        assertTrue(tmp.root.list()!!.isEmpty())
    }
}
