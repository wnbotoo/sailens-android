package com.sailens.shell.capture

import com.sailens.camera.CameraCharacteristicsProvider
import com.sailens.guidance.model.trace.FrameTrace
import com.sailens.guidance.model.trace.PromptOutcomeTrace
import com.sailens.guidance.model.trace.SessionTraceMetadata
import com.sailens.guidance.model.trace.SessionTraceSummary
import com.sailens.guidance.service.TraceService
import com.sailens.guidance.trace.capture.AnchorReason
import com.sailens.guidance.trace.capture.CaptureSessionReader
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
        encoder: JpegEncoder = JpegEncoder { image, _ -> ByteArray(image.width) },
        config: FieldCaptureConfig = FieldCaptureConfig(),
    ) = FieldCaptureController(
        root = tmp.root,
        isEnabled = enabled,
        frameSource = frames,
        cameraFacts = CameraCharacteristicsProvider { null },
        sensors = sensors,
        encoder = encoder,
        clock = clock,
        deviceInfo = TEST_DEVICE,
        log = log,
        config = config,
    )

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
