package com.sailens.shell.capture

import com.sailens.camera.CameraCharacteristicsProvider
import com.sailens.core.geometry.NormalizedRect
import com.sailens.core.mask.BinaryMask
import com.sailens.guidance.model.analysis.ConnectivityStats
import com.sailens.guidance.model.analysis.FrameQuality
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.perception.ObstacleDetection
import com.sailens.guidance.model.perception.SegmentationMask
import com.sailens.guidance.model.perception.SegmentationOutput
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.trace.PromptOutcomeTrace
import com.sailens.guidance.processor.analysis.FrameQualityMeasurement
import com.sailens.guidance.processor.analysis.FrameQualityResult
import com.sailens.guidance.trace.capture.CaptureGroups
import com.sailens.guidance.trace.capture.CaptureManifest
import com.sailens.guidance.trace.capture.CaptureModes
import com.sailens.guidance.trace.capture.CaptureSchema
import com.sailens.guidance.trace.capture.CaptureSessionReader
import com.sailens.guidance.trace.capture.RegressionClocks
import com.sailens.guidance.trace.capture.RegressionManifest
import com.sailens.guidance.trace.capture.RegressionUtteranceRecord
import com.sailens.guidance.trace.capture.UtteranceRecordEvents
import com.sailens.guidance.model.trace.PromptDeliveryChannels
import com.sailens.output.UtteranceEvent
import com.sailens.output.UtteranceEventKind
import com.sailens.guidance.trace.capture.SemanticRuns
import com.sailens.guidance.trace.capture.WindowTriggers
import com.sailens.guidance.trace.capture.orThrow
import com.sailens.guidance.trace.regression.toRecord
import com.sailens.guidance.trace.regression.withoutId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class RegressionCaptureTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val log = RecordingLog()
    private val clock = FakeClock()

    private fun capture(
        config: RegressionCaptureConfig = RegressionCaptureConfig(),
        speechSessions: SpeechLifecycleSessions? = null,
    ) = RegressionCapture(
        manifestBase = { RegressionManifest(taxonomyId = "cityscapes-19", depthSource = "position") },
        encoder = JpegEncoder { image, _ -> ByteArray(image.width * image.height) { 1 } },
        clock = clock,
        log = log,
        writerDispatcher = Dispatchers.IO.limitedParallelism(1),
        config = config,
        speechSessions = speechSessions,
    )

    /** The speech side's session hooks; [onEnd] reports what the session still owed, as SpeechManager does. */
    private class FakeSpeechSessions : SpeechLifecycleSessions {
        val calls = mutableListOf<String>()
        var onEnd: (String) -> Unit = {}

        /** What the speech side says the session produced; a later ending of the same session says 0, as a non-idempotent side would. */
        var produced = 0
        private val ended = mutableSetOf<String>()
        override fun begin(sessionId: String) {
            calls += "begin $sessionId"
        }

        override fun end(sessionId: String): Int {
            calls += "end $sessionId"
            onEnd(sessionId)
            return if (ended.add(sessionId)) produced else 0
        }
    }

    private fun utterance(event: String, attempt: String, at: Long = 1) =
        RegressionUtteranceRecord(event = event, attemptId = attempt, logicalId = attempt, engineGeneration = 1, elapsedRealtimeNanos = at)

    private fun ids(seq: Long) = IntArray(8 * 6) { ((it + seq) % 19).toInt() }

    private fun event(seq: Long) = SceneEvent(
        timestamp = 1000 + seq,
        category = EventCategory.values().first(),
        priority = EventPriority.values().last(),
        messageKey = "event_obstacle_center_person",
        expiresAt = 6000 + seq,
        dedupeKey = "obstacle_center",
    )

    private val mask = BinaryMask(8, 6).apply { set(1, 1, true) }
    private val connectivity = ConnectivityStats(4, 6, 0.8f, 0.6f, -0.1f, 0.9f, 0.5f, 0.4f, null)
    private val quality = FrameQualityResult(FrameQualityMeasurement(120f, 40f, 33f), FrameQuality.OK, FrameQuality.OK)
    private val person = ObstacleDetection(0, "person", 0.81f, NormalizedRect(0.1f, 0.2f, 0.3f, 0.6f), ObstacleCategory.values().first())

    /** One processed frame through every hook, as Guidance calls them. */
    private fun RegressionCapture.process(seq: Long, semanticRan: Boolean = true, events: List<SceneEvent> = listOf(event(seq))) {
        val frame = yuvFrame(seq)
        beginFrame(frame)
        onClock(RegressionClocks.PERCEPTION, 100 * seq)
        onClock(RegressionClocks.PERCEPTION, 100 * seq + 7)
        if (semanticRan) {
            onSemantic(frame, ran = true, output = SegmentationOutput(SegmentationMask(8, 6, ids(seq)), 1, 2, 3), failure = null)
        } else {
            onSemantic(frame, ran = false, output = null, failure = null)
        }
        onDetection(frame, listOf(person), null)
        onConnectivity(mask, connectivity)
        onClock(RegressionClocks.GROUND, 100 * seq + 8)
        onFrameQuality(quality)
        onStationary(seq % 2 == 0L)
        onClock(RegressionClocks.DECIDE, 100 * seq + 9)
        endFrame(frame, events, null)
    }

    /** The directory the controller would have created, with a manifest for the reader. */
    private fun sessionDir(id: String): File = tmp.newFolder(id).also { dir ->
        File(dir, CaptureSchema.MANIFEST_FILE).writeText(
            CaptureSchema.encodeManifest(
                CaptureManifest(
                    sessionId = id, captureMode = CaptureModes.MODEL_REGRESSION, startedWallMs = 1, startedElapsedRealtimeNanos = 1,
                    deviceManufacturer = "Acme", deviceModel = "Phone", sdkInt = 36, complete = true,
                    regression = RegressionManifest(),
                ),
            ),
        )
    }

    @Test
    fun `every frame is recorded with its clocks, model outputs and decisions`() = runBlocking {
        val capture = capture()
        assertFalse(capture.isRecording)
        capture.startSession("s1")
        assertTrue(capture.isRecording)
        // Frames before the directory exists wait in the queue.
        capture.process(1)
        capture.process(2, semanticRan = false, events = emptyList())
        capture.attach("s1", sessionDir("s1"))
        capture.process(3)
        val stats = capture.finish("s1")!!
        assertFalse(capture.isRecording)

        assertEquals(3, stats.framesRecorded)
        assertEquals(0, stats.framesLost)
        assertNull(stats.firstLostSeq)
        val session = CaptureSessionReader.read(File(tmp.root, "s1")).orThrow()
        val frames = session.regressionFrames
        assertEquals(listOf(1L, 2L, 3L), frames.map { it.seq })
        val first = frames[0]
        assertEquals(mapOf(RegressionClocks.PERCEPTION to listOf(100L, 107L), RegressionClocks.GROUND to listOf(108L), RegressionClocks.DECIDE to listOf(109L)), first.clocks)
        assertEquals(SemanticRuns.RAN, first.semantic!!.run)
        assertArrayEquals(ids(1), CaptureSessionReader.classMap(session, first))
        assertEquals(SemanticRuns.CACHED, frames[1].semantic!!.run)
        assertNull(CaptureSessionReader.classMap(session, frames[1]))
        assertArrayEquals(ids(3), CaptureSessionReader.classMap(session, frames[2]))
        assertEquals("person", first.detection!!.detections.single().label)
        assertEquals(connectivity.toRecord(), first.connectivity.single().stats)
        assertEquals(33f, first.quality!!.sharpness)
        assertEquals(false, first.stationary)
        // Compared without the random id, as replay compares decisions.
        assertEquals(listOf(event(1).toRecord().withoutId()), first.events.map { it.withoutId() })
        assertEquals(emptyList<Any>(), frames[1].events)
    }

    @Test
    fun `frames that do not fit the queue are lost, and the first lost one ends exact replay`() = runBlocking {
        val capture = capture(RegressionCaptureConfig(queueCapacity = 2))
        capture.startSession("s1")
        (1L..4L).forEach { capture.process(it) }
        capture.attach("s1", sessionDir("s1"))
        val stats = capture.finish("s1")!!
        assertEquals(2, stats.framesRecorded)
        assertEquals(2, stats.framesLost)
        assertEquals(3L, stats.firstLostSeq)
    }

    @Test
    fun `a capture that never starts writing loses what it queued`() = runBlocking {
        val capture = capture()
        capture.startSession("s1")
        capture.process(1)
        capture.process(2)
        val stats = capture.finish("s1")!!
        assertEquals(0, stats.framesRecorded)
        assertEquals(2, stats.framesLost)
        assertEquals(1L, stats.firstLostSeq)
    }

    @Test
    fun `an exhausted class map pool loses the frame rather than record it without its map`() = runBlocking {
        val capture = capture(RegressionCaptureConfig(classMapPoolSize = 1))
        capture.startSession("s1")
        capture.process(1)
        capture.process(2) // the only copy is still queued with frame 1
        capture.attach("s1", sessionDir("s1"))
        val stats = capture.finish("s1")!!
        assertEquals(1, stats.framesRecorded)
        assertEquals(2L, stats.firstLostSeq)
    }

    @Test
    fun `a hook that fails costs the record that frame, never throws into Guidance`() = runBlocking {
        val capture = capture()
        capture.startSession("s1")
        capture.attach("s1", sessionDir("s1"))
        capture.process(1)
        val frame = yuvFrame(2)
        capture.beginFrame(frame)
        // A class map smaller than its stated size: copying it throws inside the recorder.
        capture.onSemantic(frame, ran = true, output = SegmentationOutput(SegmentationMask(8, 6, IntArray(3)), 1, 2, 3), failure = null)
        capture.endFrame(frame, emptyList(), null)
        capture.process(3)
        val stats = capture.finish("s1")!!
        assertEquals(2, stats.framesRecorded)
        assertEquals(2L, stats.firstLostSeq)
    }

    @Test
    fun `a revoke is recorded with the number of decisions before it`() = runBlocking {
        val capture = capture()
        capture.startSession("s1")
        capture.attach("s1", sessionDir("s1"))
        capture.process(1)
        val id = UUID.randomUUID()
        capture.onRevoke(id, decisionsBefore = 1)
        capture.process(2)
        capture.finish("s1")
        val revoke = CaptureSessionReader.read(File(tmp.root, "s1")).orThrow().revokes.single()
        assertEquals(id.toString(), revoke.eventId)
        assertEquals(1, revoke.decisionsBefore)
    }

    @Test
    fun `nothing is recorded outside a session, or after an abort`() = runBlocking {
        val capture = capture()
        capture.process(1) // no session: ignored
        capture.startSession("s1")
        capture.process(2)
        val stats = capture.abort("s1", "test")!!
        assertFalse(capture.isRecording)
        assertEquals(1, stats.framesLost)
        capture.process(3)
        assertNull(capture.finish("s1"))
    }

    @Test
    fun `event windows keep frames around a delivered prompt and before a marker`() = runBlocking {
        // Test frames are 1 ms apart on the camera clock and 2 ms apart on the receipt clock.
        val capture = capture(
            RegressionCaptureConfig(
                windowIntervalNanos = 1_000_000, windowRingSize = 20,
                promptWindowBeforeNanos = 3_000_000, promptWindowAfterNanos = 2_000_000,
                markerWindowBeforeNanos = 3_000_000,
            ),
        )
        capture.startSession("s1")
        capture.attach("s1", sessionDir("s1"))
        (1L..10L).forEach { capture.process(it) }
        capture.onPromptOutcome(
            PromptOutcomeTrace(
                eventId = "e10", sourceSequenceNumber = 10, messageKey = "k", category = "OBSTACLE", priority = "HIGH",
                deliveredAt = 1, deliveredVia = listOf("speech"),
                speechEnabled = true, screenReaderActive = false, hapticsEnabled = true,
            ),
        )
        (11L..14L).forEach { capture.process(it) }
        capture.onMarker(elapsedRealtimeNanos = 2_000_000L * 15)
        (15L..18L).forEach { capture.process(it) }
        val stats = capture.finish("s1")!!

        val windows = CaptureSessionReader.read(File(tmp.root, "s1")).orThrow().windowFrames
        // Prompt at seq 10 (camera 10 ms): camera 7..12 ms = seq 7..12. Marker at receipt 30 ms:
        // receipt 27..30 ms = seq 14 and 15 (seq 13 is at 26 ms).
        assertEquals((7L..12L).toList(), windows.filter { it.trigger == WindowTriggers.DELIVERED_PROMPT }.map { it.seq })
        assertEquals(listOf(14L, 15L), windows.filter { it.trigger == WindowTriggers.MARKER }.map { it.seq })
        assertTrue(windows.all { File(File(tmp.root, "s1"), it.file).length() == 48L })
        assertEquals(2, stats.windowsRecorded)
        assertEquals(8, stats.windowFramesStored)
    }

    // ---- with the controller ---------------------------------------------------------------------

    private fun controller(regression: RegressionCapture, freeBytes: () -> Long = { Long.MAX_VALUE }) = FieldCaptureController(
        root = tmp.root,
        sessionMode = { CaptureModes.MODEL_REGRESSION },
        frameSource = FakeFrameSource(),
        cameraFacts = CameraCharacteristicsProvider { null },
        sensors = FakeSensors(),
        encoder = JpegEncoder { image, _ -> ByteArray(image.width) },
        clock = clock,
        deviceInfo = TEST_DEVICE,
        log = log,
        regression = regression,
        sessionLabels = { SessionLabels(CaptureGroups.OUTDOOR, "granite sidewalk") },
        freeBytes = freeBytes,
    )

    @Test
    fun `a model regression capture records from the first frame and finishes its manifest`() = runBlocking {
        val regression = capture()
        val controller = controller(regression)
        val started = controller.onSessionStarted("s1", "qti_sm8850")!!
        // Synchronous: recording before the capture's own start has run.
        assertTrue(regression.isRecording)
        regression.process(1)
        started.join()
        regression.process(2)
        controller.onSessionFinished().join()

        val session = CaptureSessionReader.read(File(tmp.root, "s1")).orThrow()
        assertEquals(CaptureModes.MODEL_REGRESSION, session.manifest.captureMode)
        assertEquals(CaptureGroups.OUTDOOR, session.manifest.group)
        assertEquals("granite sidewalk", session.manifest.sceneLabel)
        assertEquals("cityscapes-19", session.manifest.regression!!.taxonomyId)
        assertEquals(2, session.manifest.regression!!.stats.framesRecorded)
        assertEquals(listOf(1L, 2L), session.regressionFrames.map { it.seq })
        assertTrue(session.manifest.complete)
    }

    // ---- the speech lifecycle (det-first plan D1.3) ----------------------------------------------

    @Test
    fun `the session's lifecycle events are recorded beside its frames and read back without warnings`() = runBlocking {
        val speech = FakeSpeechSessions().apply { produced = 3 }
        val capture = capture(speechSessions = speech)
        speech.onEnd = { id -> capture.onUtterance(id, utterance(UtteranceRecordEvents.UNKNOWN_TERMINAL, "open", at = 99)) }
        assertTrue("the manifest says the record keeps the lifecycle", capture.manifestBlock().utteranceLifecycle)
        capture.startSession("s1")
        capture.onUtterance("s1", utterance(UtteranceRecordEvents.SUBMITTED, "a"))
        capture.process(1)
        capture.attach("s1", sessionDir("s1"))
        capture.onUtterance("s1", utterance(UtteranceRecordEvents.DONE, "a", at = 2))
        capture.process(2)
        val stats = capture.finish("s1")!!

        assertEquals(listOf("begin s1", "end s1"), speech.calls)
        assertEquals(3, stats.utteranceEventsRecorded)
        assertEquals(0, stats.utteranceEventsDropped)
        assertEquals(2, stats.framesRecorded)
        val session = CaptureSessionReader.read(File(tmp.root, "s1")).orThrow()
        assertEquals(emptyList<String>(), session.warnings)
        assertEquals(
            "what the speech side owed at the end is recorded before the record closes",
            listOf("submitted", "done", "unknown_terminal"),
            session.utterances.map { it.event },
        )
    }

    @Test
    fun `drops are what the speech side produced and the record did not write, each counted once`() = runBlocking {
        // The session produced a submission and a done; only the submission arrived before the end.
        val speech = FakeSpeechSessions().apply { produced = 2 }
        val capture = capture(speechSessions = speech)
        capture.startSession("s1")
        capture.attach("s1", sessionDir("s1"))
        capture.onUtterance("s1", utterance(UtteranceRecordEvents.SUBMITTED, "a"))
        val stats = capture.finish("s1")!!
        // The done arrives late, after the record closed: still one loss, not two.
        capture.onUtterance("s1", utterance(UtteranceRecordEvents.DONE, "a"))

        assertEquals(1, stats.utteranceEventsRecorded)
        assertEquals(1, stats.utteranceEventsDropped)
    }

    @Test
    fun `a new session starting while the old one drains shares the old one's single speech ending`() {
        // finish(s1) is inside the speech side's end; meanwhile the controller starts s2, which
        // abandons s1. The speech side must be ended once, and its count kept.
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val speech = FakeSpeechSessions().apply { produced = 2 }
        speech.onEnd = { id ->
            if (id == "s1") {
                entered.countDown()
                release.await()
            }
        }
        val capture = capture(speechSessions = speech)
        capture.startSession("s1")
        capture.attach("s1", sessionDir("s1"))
        capture.onUtterance("s1", utterance(UtteranceRecordEvents.SUBMITTED, "a"))
        capture.process(1) // lets the writer take the submission

        var stats: com.sailens.guidance.trace.capture.RegressionStats? = null
        val finishing = Thread { stats = runBlocking { capture.finish("s1") } }.apply { start() }
        entered.await()
        val starting = Thread { capture.startSession("s2") }.apply { start() }
        Thread.sleep(100)
        release.countDown()
        finishing.join(5_000)
        starting.join(5_000)

        assertEquals("ended once", 1, speech.calls.count { it == "end s1" })
        val s = stats!!
        assertEquals("every produced event is either written or dropped, once", 2L, s.utteranceEventsRecorded + s.utteranceEventsDropped)
        assertTrue("one was really lost", s.utteranceEventsDropped >= 1)
        assertEquals(0, s.utteranceEventsUnreconciled)
    }

    @Test
    fun `more written than the speech side produced is reported as unreconciled, not hidden`() = runBlocking {
        val capture = capture(speechSessions = FakeSpeechSessions().apply { produced = 0 })
        capture.startSession("s1")
        capture.attach("s1", sessionDir("s1"))
        capture.onUtterance("s1", utterance(UtteranceRecordEvents.SUBMITTED, "a"))
        val stats = capture.finish("s1")!!

        assertEquals(0, stats.utteranceEventsDropped)
        assertEquals(1, stats.utteranceEventsUnreconciled)
    }

    @Test
    fun `another session's late event never marks this one incomplete`() = runBlocking {
        val speech = FakeSpeechSessions().apply { produced = 1 }
        val capture = capture(speechSessions = speech)
        capture.startSession("s2")
        capture.attach("s2", sessionDir("s2"))
        capture.onUtterance("s1", utterance(UtteranceRecordEvents.DONE, "old"))
        capture.onUtterance("s2", utterance(UtteranceRecordEvents.SUBMITTED, "b"))
        val stats = capture.finish("s2")!!

        assertEquals(1, stats.utteranceEventsRecorded)
        assertEquals(0, stats.utteranceEventsDropped)
    }

    @Test
    fun `without the speech hooks the manifest says there is no lifecycle evidence`() {
        assertFalse(capture().manifestBlock().utteranceLifecycle)
    }

    @Test
    fun `a late event of an ended session is never written into the next one`() = runBlocking {
        val capture = capture(speechSessions = FakeSpeechSessions())
        capture.startSession("s1")
        capture.attach("s1", sessionDir("s1"))
        capture.finish("s1")
        capture.startSession("s2")
        capture.attach("s2", sessionDir("s2"))
        capture.onUtterance("s1", utterance(UtteranceRecordEvents.DONE, "late"))
        capture.onUtterance(null, utterance(UtteranceRecordEvents.STOPPED, "unknown"))
        capture.onUtterance("s2", utterance(UtteranceRecordEvents.SUBMITTED, "b"))
        val stats = capture.finish("s2")!!

        assertEquals(listOf("b"), CaptureSessionReader.read(File(tmp.root, "s2")).orThrow().utterances.map { it.attemptId })
        assertEquals(0, stats.utteranceEventsDropped)
    }

    @Test
    fun `lifecycle events that do not fit are counted dropped and never cost a frame`() = runBlocking {
        val capture = capture(RegressionCaptureConfig(utteranceQueueCapacity = 2), FakeSpeechSessions().apply { produced = 5 })
        capture.startSession("s1")
        capture.process(1)
        repeat(5) { capture.onUtterance("s1", utterance(UtteranceRecordEvents.STARTED, "a$it")) }
        capture.attach("s1", sessionDir("s1"))
        val stats = capture.finish("s1")!!

        assertEquals(2, stats.utteranceEventsRecorded)
        assertEquals(3, stats.utteranceEventsDropped)
        assertEquals(1, stats.framesRecorded)
        assertEquals(0, stats.framesLost)
        assertNull(stats.firstLostSeq)
    }

    @Test
    fun `a capture that never starts writing counts its queued lifecycle events dropped`() = runBlocking {
        val capture = capture(speechSessions = FakeSpeechSessions().apply { produced = 1 })
        capture.startSession("s1")
        capture.onUtterance("s1", utterance(UtteranceRecordEvents.SUBMITTED, "a"))
        val stats = capture.finish("s1")!!
        assertEquals(1, stats.utteranceEventsDropped)
    }

    @Test
    fun `a prompt handed to the screen reader is noted, since that path reports no lifecycle`() = runBlocking {
        val capture = capture(speechSessions = FakeSpeechSessions())
        capture.startSession("s1")
        capture.attach("s1", sessionDir("s1"))
        capture.process(1)
        capture.onPromptOutcome(outcome("e1", via = listOf(PromptDeliveryChannels.SCREEN_READER)))
        capture.onPromptOutcome(outcome("e2", via = listOf(PromptDeliveryChannels.SPEECH)))
        capture.finish("s1")

        val noted = CaptureSessionReader.read(File(tmp.root, "s1")).orThrow().utterances.single()
        assertEquals(UtteranceRecordEvents.SCREEN_READER_PUBLISHED, noted.event)
        assertEquals("e1", noted.logicalId)
    }

    @Test
    fun `the bridge carries every field of the speech side's event`() {
        val event = UtteranceEvent(
            kind = UtteranceEventKind.REQUEUE_FAILED, attemptId = "a~1", logicalId = "a", engineGeneration = 3, session = "s1",
            elapsedRealtimeNanos = 42, reason = "r", interrupted = true, errorCode = -5, previousAttemptId = "a", owner = "describe",
            priority = 2, textLength = 9,
        )
        assertEquals(
            RegressionUtteranceRecord(
                event = "requeue_failed", attemptId = "a~1", logicalId = "a", engineGeneration = 3, elapsedRealtimeNanos = 42,
                reason = "r", interrupted = true, errorCode = -5, previousAttemptId = "a", owner = "describe", priority = 2, textLength = 9,
            ),
            event.toRecord(),
        )
        val seen = mutableListOf<Pair<String?, RegressionUtteranceRecord>>()
        val recorder = object : com.sailens.guidance.trace.regression.RegressionRecorder {
            override val isRecording = true
            override fun onUtterance(sessionId: String?, record: RegressionUtteranceRecord) {
                seen += sessionId to record
            }
        }
        UtteranceLifecycleRecording(recorder).onUtteranceEvent(event)
        assertEquals(listOf("s1" to event.toRecord()), seen)
    }

    private fun outcome(id: String, via: List<String>) = PromptOutcomeTrace(
        eventId = id, sourceSequenceNumber = 1, messageKey = "event_obstacle_center_person", category = "obstacle", priority = "high",
        deliveredAt = 5, deliveredVia = via, speechEnabled = true, screenReaderActive = PromptDeliveryChannels.SCREEN_READER in via,
        hapticsEnabled = false,
    )

    @Test
    fun `below the free-storage floor a capture is refused and the record stops`() = runBlocking {
        val regression = capture()
        val controller = controller(regression, freeBytes = { 1L * 1024 * 1024 })
        controller.onSessionStarted("s1", null)!!.join()
        assertFalse(regression.isRecording)
        val manifest = CaptureSessionReader.read(File(tmp.root, "s1")).orThrow().manifest
        assertFalse(manifest.complete)
        assertEquals(FieldCaptureController.STORAGE_LOW, manifest.failureReason)
    }
}
