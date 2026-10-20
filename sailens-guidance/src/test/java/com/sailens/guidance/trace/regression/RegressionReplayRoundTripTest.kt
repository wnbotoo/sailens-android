package com.sailens.guidance.trace.regression

import kotlin.math.sin
import com.sailens.guidance.sim.SyntheticCamera
import com.sailens.guidance.sim.Pose
import com.sailens.guidance.motion.OrientationSource
import com.sailens.guidance.motion.NoOrientationSource
import com.sailens.guidance.motion.MotionTracker
import com.sailens.guidance.geometry.toCameraFacts
import com.sailens.guidance.geometry.Vec3
import com.sailens.guidance.geometry.SensorSample
import com.sailens.guidance.geometry.Placement
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.geometry.CameraFactsSource
import com.sailens.guidance.depth.GroundContactDistanceConfig
import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.ImagePixelFormat
import com.sailens.core.frame.Yuv420FrameData
import com.sailens.core.frame.YuvPlaneData
import com.sailens.core.geometry.NormalizedRect
import com.sailens.core.log.LogService
import com.sailens.core.mask.BinaryMask
import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.config.PerceptionConfig
import com.sailens.guidance.config.PipelinePerformanceBudget
import com.sailens.guidance.config.TraceRuntimeConfig
import com.sailens.guidance.depth.DefaultDepthRepository
import com.sailens.guidance.depth.GroundContactDistanceEstimator
import com.sailens.guidance.depth.ImagePositionDepthEstimator
import com.sailens.guidance.model.analysis.ConnectivityStats
import com.sailens.guidance.model.common.GroundType
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.common.ObstacleProviderType
import com.sailens.guidance.model.common.PerceptionProfile
import com.sailens.guidance.model.perception.ObstacleDetection
import com.sailens.guidance.model.perception.ObstacleModelOutput
import com.sailens.guidance.model.perception.SegmentationAnalysisStats
import com.sailens.guidance.model.perception.SegmentationMask
import com.sailens.guidance.model.perception.SegmentationOutput
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.trace.FrameTrace
import com.sailens.guidance.model.trace.PromptOutcomeTrace
import com.sailens.guidance.model.trace.SessionTraceMetadata
import com.sailens.guidance.model.trace.SessionTraceSummary
import com.sailens.guidance.processor.analysis.ConnectivityChecker
import com.sailens.guidance.processor.analysis.CrossValidator
import com.sailens.guidance.processor.analysis.FrameQualityAnalyzer
import com.sailens.guidance.processor.analysis.FrameQualityResult
import com.sailens.guidance.processor.analysis.GroundRecognitionAnalyzer
import com.sailens.guidance.processor.analysis.GroundTypeDetector
import com.sailens.guidance.processor.analysis.KotlinConnectivityStatsExtractor
import com.sailens.guidance.processor.analysis.ObstacleOcclusionAnalyzer
import com.sailens.guidance.processor.analysis.RoadSafetyAnalyzer
import com.sailens.guidance.processor.analysis.SceneClassifier
import com.sailens.guidance.processor.decision.CooldownManager
import com.sailens.guidance.processor.decision.EventConflictResolver
import com.sailens.guidance.processor.decision.EventGenerator
import com.sailens.guidance.processor.decision.EventMerger
import com.sailens.guidance.processor.perception.ObstacleExtractor
import com.sailens.guidance.processor.perception.ObstacleTracker
import com.sailens.guidance.processor.perception.PerceptionProfileManager
import com.sailens.guidance.processor.perception.SegmentationAnalyzer
import com.sailens.guidance.repository.DeviceSensorRepository
import com.sailens.guidance.repository.ObstacleProvider
import com.sailens.guidance.repository.PerceptionRepository
import com.sailens.guidance.semantics.CityscapesNavigationSemantics
import com.sailens.guidance.service.TraceService
import com.sailens.guidance.trace.capture.CaptureSchema
import com.sailens.guidance.trace.capture.ClassMapStore
import com.sailens.guidance.trace.capture.EventRecord
import com.sailens.guidance.trace.capture.RegressionClocks
import com.sailens.guidance.trace.capture.RegressionFrameRecord
import com.sailens.guidance.trace.capture.RegressionRevokeRecord
import com.sailens.guidance.usecase.decision.DecideEventsUseCase
import com.sailens.guidance.usecase.decision.RevokeUndeliveredEventUseCase
import com.sailens.guidance.usecase.perception.AnalyzeSceneUseCase
import com.sailens.guidance.usecase.perception.ProcessFrameUseCase
import com.sailens.guidance.usecase.scene.StartSceneAnalysisUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * M0b exit criterion (3), in CI: a synthetic Guidance session through the real
 * `StartSceneAnalysisUseCase` decides the same with recording on and off, and its record replays
 * exactly through [RegressionReplay]. This is also what keeps the replay harness's per-frame order
 * in step with the production pipeline.
 */
class RegressionReplayRoundTripTest {

    private val semantics = CityscapesNavigationSemantics
    private val analysisConfig = AnalysisConfig()
    private val perceptionConfig = PerceptionConfig(
        profile = PerceptionProfile.DEFAULT,
        realtimeObstacleProviderType = ObstacleProviderType.DETECTION_MODEL,
        // Frames are 200 ms apart: sem runs on alternate frames (the others reuse the cached
        // analysis), det on most, with tracker prediction in between.
        semanticTargetFps = 3,
        detectionTargetFps = 4,
    )
    private val frameCount = 150L
    private val width = 36
    private val height = 64

    /** The recorder the debug shell would bind, keeping everything in memory. */
    private class MemoryRecorder : RegressionRecorder {
        val frames = mutableListOf<RegressionFrameRecord>()
        val classMaps = HashMap<Long, IntArray>()
        val revokes = mutableListOf<RegressionRevokeRecord>()
        val lost = mutableListOf<String>()
        private val assembler = RegressionFrameAssembler { size -> IntArray(size) }

        /** Where the next entry would go in `classmaps.bin`: references are made as the writer makes them. */
        private var classMapOffset = 0L
        override val isRecording: Boolean = true
        override fun beginFrame(frame: ImageFrame) { assembler.beginFrame(frame)?.let { lost += it.why } }
        override fun onClock(component: String, value: Long) { assembler.onClock(component, value) }
        override fun onSemantic(frame: ImageFrame, ran: Boolean, output: SegmentationOutput?, failure: Throwable?) =
            assembler.onSemantic(frame, ran, output, failure)
        override fun onDetection(frame: ImageFrame, detections: List<ObstacleDetection>?, failure: Throwable?) =
            assembler.onDetection(frame, detections, failure)
        override fun onConnectivity(passableMask: BinaryMask, stats: ConnectivityStats?) = assembler.onConnectivity(passableMask, stats)
        override fun onFrameQuality(result: FrameQualityResult) = assembler.onFrameQuality(result)
        override fun onGeometry(geometry: CameraGeometry) = assembler.onGeometry(geometry)
        override fun onStationary(stationary: Boolean) = assembler.onStationary(stationary)
        override fun endFrame(frame: ImageFrame, events: List<SceneEvent>, failure: Throwable?) {
            when (val outcome = assembler.endFrame(frame, events, failure)) {
                is RegressionFrameAssembler.Outcome.Lost -> lost += outcome.why
                is RegressionFrameAssembler.Outcome.Frame -> {
                    var record = outcome.record
                    outcome.classIds?.let { ids ->
                        val entry = ClassMapStore.encode(outcome.seq, outcome.width, outcome.height, ids)
                        record = record.copy(semantic = record.semantic?.copy(classMap = entry.ref(classMapOffset)))
                        classMapOffset += entry.bytes.size
                        classMaps[outcome.seq] = ids
                    }
                    // Through JSON, as the capture writes it, so the round trip covers the format too.
                    frames += CaptureSchema.json.decodeFromString(
                        RegressionFrameRecord.serializer(),
                        CaptureSchema.encodeRecord(record).replace("\"type\":\"regression_frame\",", ""),
                    )
                }
            }
        }
        override fun onRevoke(eventId: UUID, decisionsBefore: Long) {
            revokes += RegressionRevokeRecord(eventId.toString(), decisionsBefore, 0)
        }
    }

    /** A class map: building above the horizon, sidewalk below narrowing over time, a person ahead. */
    private fun classMap(seq: Long): IntArray {
        val ids = IntArray(width * height)
        val narrowing = (seq / 6).toInt().coerceAtMost(14)
        val personTop = 30 + (seq % 20).toInt()
        for (y in 0 until height) for (x in 0 until width) {
            ids[y * width + x] = when {
                y < 24 -> 2 // building
                x < narrowing || x >= width - narrowing -> 8 // vegetation closing in
                y in personTop..(personTop + 10) && x in 15..20 -> 11 // person
                y > 54 && seq in 40L..48L -> 0 // a stretch of road
                else -> 1 // sidewalk
            }
        }
        return ids
    }

    /** Native-like statistics on some frames, so mask rebuilding and its CRC check are exercised. */
    private fun stats(ids: IntArray, seq: Long): SegmentationAnalysisStats? {
        if (seq % 5 != 0L) return null
        val (passable, obstacle) = rebuildSemanticMasks(ids, width, height, semantics)
        val counts = IntArray(semantics.classCount).also { c -> ids.forEach { c[it]++ } }
        return SegmentationAnalysisStats(
            passableMask = passable,
            obstacleMask = obstacle,
            roadRatio = counts[0].toFloat() / ids.size,
            hasTrafficLight = false,
            bottomCenterGroundDistribution = mapOf(GroundType.SIDEWALK to 0.7f, GroundType.ROAD to 0.1f),
            bottomCenterRoadRatio = 0.1f,
            bottomStats = passable.getBottomStats(),
            passablePixelCount = passable.countTrue(),
            navigationPassableRatio = passable.coverage(),
            obstaclePixelCount = obstacle.countTrue(),
            classCounts = counts,
        )
    }

    /**
     * An obstacle approaching in a zone and of a kind that change every 12 frames. [farther] starts
     * it higher in the image, where today's level is MEDIUM and the ground contact can disagree.
     */
    private fun detections(seq: Long, farther: Boolean = false): List<ObstacleDetection> {
        if (seq % 7 == 3L) return emptyList()
        val phase = (seq / 12).toInt()
        val left = listOf(0.12f, 0.42f, 0.70f)[phase % 3]
        val (classId, label, category) = listOf(
            Triple(0, "person", ObstacleCategory.PERSON),
            Triple(1, "bicycle", ObstacleCategory.BICYCLE),
            Triple(2, "car", ObstacleCategory.VEHICLE),
        )[(phase / 3) % 3]
        val bottom = ((if (farther) 0.45f else 0.55f) + (seq % 12) * 0.035f).coerceAtMost(0.98f)
        return listOf(ObstacleDetection(classId, label, 0.85f, NormalizedRect(left, bottom - 0.35f, 0.18f, 0.35f), category))
    }

    /** Camera frames: textured, except a covered stretch that degrades the frame quality. */
    private fun cameraFrame(seq: Long): ImageFrame {
        val w = 64
        val h = 36
        val random = Random(seq)
        val covered = seq in 90L..100L
        val y = ByteArray(w * h) { if (covered) 4 else (110 + random.nextInt(-30, 30)).toByte() }
        val chroma = YuvPlaneData(ByteArray(w * h / 2), w, 2)
        return ImageFrame(w, h, ByteArray(0), ImagePixelFormat.YUV_420_888, seq * 33_000_000, 90, seq,
            yuvData = Yuv420FrameData(YuvPlaneData(y, w, 1), chroma, chroma))
    }

    private class Session(val events: List<List<SceneEvent>>, val recorder: MemoryRecorder?, val activeConfig: PerceptionConfig)

    /** Frames whose semantic run fails when it runs: such a frame produces no result. */
    private val failingFrames = setOf(30L, 31L)

    /**
     * The phone tilting between 26 and 38 degrees down over a few seconds, as gravity samples at
     * any time asked; the camera is the M2a scenarios' (its buffer mapping is assumed for these
     * small frames). With it, the ground-contact distance (M3a) has something to work on.
     */
    private val tiltingPhone = object : OrientationSource {
        private val noRotation = MotionTracker()
        override fun start() = Unit
        override fun stop() = Unit
        override fun gravityNear(timeNanos: Long): SensorSample {
            val pitch = 32f + 6f * sin(timeNanos / 1e9 * 1.3).toFloat()
            val g = Pose(1.3f, pitch).gravity()
            return SensorSample(timeNanos - 4_000_000, Vec3(g[0], g[1], g[2]))
        }
        override fun motionState(atNanos: Long, isStationary: Boolean) = noRotation.state(atNanos, isStationary)
    }

    private fun runSession(record: Boolean, groundContact: Boolean = false): Session = runBlocking {
        val perceptionConfig = if (groundContact) {
            perceptionConfig.copy(groundContact = GroundContactDistanceConfig(enabled = true))
        } else {
            perceptionConfig
        }
        val recorder = if (record) MemoryRecorder() else null
        val regression: RegressionRecorder = recorder ?: RegressionRecorder.None
        val now = AtomicLong(1_000_000)
        val clock = { now.incrementAndGet() }
        val failures = AtomicInteger()
        val stationary = MutableStateFlow(false)
        val repository = object : PerceptionRepository {
            override val isInitialized = true
            override suspend fun initialize() = Unit
            override suspend fun release() = Unit
            override suspend fun segment(frame: ImageFrame): Result<SegmentationOutput> {
                if (frame.sequenceNumber in failingFrames) {
                    failures.incrementAndGet()
                    return Result.failure(IllegalStateException("model failed"))
                }
                val ids = classMap(frame.sequenceNumber)
                return Result.success(SegmentationOutput(SegmentationMask(width, height, ids), 1, 2, 3, analysisStats = stats(ids, frame.sequenceNumber)))
            }
        }
        val provider = object : ObstacleProvider {
            override val isInitialized = true
            override suspend fun initialize() = Unit
            override fun release() = Unit
            override suspend fun detect(frame: ImageFrame) = ObstacleModelOutput(detections(frame.sequenceNumber, farther = groundContact))
        }
        val sensors = object : DeviceSensorRepository {
            override val deviceRotation: StateFlow<Int> = MutableStateFlow(0)
            override val deviceRotationValue = 0
            override val deviceRotationDegree = 0
            override val isStationary: StateFlow<Boolean> = stationary
            override fun startObserving() = Unit
            override fun stopObserving() = Unit
        }
        val profiles = PerceptionProfileManager(perceptionConfig)
        val cooldown = CooldownManager()
        val processFrame = ProcessFrameUseCase(
            profileManager = profiles,
            perceptionRepository = repository,
            realtimeObstacleProvider = provider,
            depthRepository = DefaultDepthRepository(ImagePositionDepthEstimator(), GroundContactDistanceEstimator(semantics)),
            segmentationAnalyzer = SegmentationAnalyzer(analysisConfig, semantics),
            obstacleExtractor = ObstacleExtractor(perceptionConfig, semantics),
            obstacleTracker = ObstacleTracker(perceptionConfig),
            clock = regression.recordingClock(RegressionClocks.PERCEPTION, clock),
            regressionRecorder = regression,
        )
        val extractor = KotlinConnectivityStatsExtractor(analysisConfig)
        val analyze = AnalyzeSceneUseCase(
            connectivityChecker = ConnectivityChecker(analysisConfig, recorder?.let { RecordingConnectivityStatsExtractor(extractor, it) } ?: extractor),
            roadSafetyAnalyzer = RoadSafetyAnalyzer(analysisConfig, semantics),
            groundTypeDetector = GroundTypeDetector(analysisConfig),
            sceneClassifier = SceneClassifier(analysisConfig),
            crossValidator = CrossValidator(analysisConfig),
            obstacleOcclusionAnalyzer = ObstacleOcclusionAnalyzer(perceptionConfig),
            groundRecognitionAnalyzer = GroundRecognitionAnalyzer(analysisConfig, semantics, regression.recordingClock(RegressionClocks.GROUND, clock)),
        )
        val decide = DecideEventsUseCase(
            EventGenerator(analysisConfig), EventConflictResolver(), EventMerger(), cooldown, sensors,
            clock = regression.recordingClock(RegressionClocks.DECIDE, clock),
            regressionRecorder = regression,
        )
        val revoke = RevokeUndeliveredEventUseCase(cooldown, regression)
        val start = StartSceneAnalysisUseCase(
            profileManager = profiles,
            perceptionRepository = repository,
            realtimeObstacleProvider = provider,
            processFrameUseCase = processFrame,
            analyzeSceneUseCase = analyze,
            decideEventsUseCase = decide,
            frameQualityAnalyzer = FrameQualityAnalyzer(),
            logService = SilentLog,
            traceService = NoTrace,
            traceRuntimeConfig = TraceRuntimeConfig(enabled = false),
            pipelineBudget = PipelinePerformanceBudget(),
            regressionRecorder = regression,
            orientationSource = if (groundContact) tiltingPhone else NoOrientationSource,
            cameraFactsSource = CameraFactsSource { if (groundContact) SyntheticCamera().cameraRecord().toCameraFacts() else null },
            phoneGeometry = { PhoneGeometrySettings(1.3f, HeightSource.PRESET, Placement.HANDHELD_CHEST) },
        )
        // The pipeline runs on its own dispatcher with a buffer before the collector, so on its own
        // it may decide several frames ahead of a revoke. The live recording copes (each revoke
        // records how many decisions preceded it); this comparison needs the revoke at a fixed
        // point, so each frame waits until the collector has handled every earlier result.
        // A frame yields a result unless its semantic run fails, and that run is skipped when the
        // cached analysis is reused -- so count the failures that happened, not the frames scripted
        // to fail. Both are known once emit returns: the frame has been processed by then.
        val handled = MutableStateFlow(0L)
        val frames = flow {
            for (seq in 1L..frameCount) {
                handled.first { it >= seq - 1 - failures.get() }
                now.addAndGet(200)
                stationary.value = seq in 50L..80L
                emit(cameraFrame(seq))
            }
        }
        val decided = mutableListOf<List<SceneEvent>>()
        var offered = 0
        start(frames, releaseFrame = {}, semanticMaskSnapshot = { false }).collect { result ->
            decided += result.events
            // The output side refuses every third prompt; its cooldown entry is revoked between frames.
            result.events.firstOrNull()?.let { if (offered++ % 3 == 1) revoke(it) }
            handled.value++
        }
        Session(decided, recorder, profiles.activeConfig)
    }

    @Test
    fun `recording does not change decisions, and the record replays exactly`() {
        val plain = runSession(record = false)
        val recorded = runSession(record = true)
        val recorder = recorded.recorder!!

        // (a) Observation only: the same session decides the same with the recorder attached.
        assertEquals(plain.events.map { e -> e.map { it.toRecord().withoutId() } }, recorded.events.map { e -> e.map { it.toRecord().withoutId() } })
        assertTrue("the scene must produce decisions worth comparing", recorded.events.sumOf { it.size } >= 5)
        assertTrue("some prompts were revoked", recorder.revokes.isNotEmpty())
        assertEquals(emptyList<String>(), recorder.lost)
        assertEquals(frameCount.toInt(), recorder.frames.size) // failed frames are recorded too
        // The session covers the paths replay must reproduce.
        assertTrue("cached semantic frames", recorder.frames.any { it.semantic?.run == com.sailens.guidance.trace.capture.SemanticRuns.CACHED })
        assertTrue("native statistics", recorder.frames.any { it.semantic?.stats != null })
        assertTrue("tracker prediction frames", recorder.frames.any { it.failure == null && it.detection == null })
        assertTrue("a failed frame", recorder.frames.any { it.failure != null })
        assertTrue("several kinds of prompt", recorded.events.flatten().map { it.messageKey }.distinct().size >= 3)

        // (b) The record replays exactly.
        val report = RegressionReplay(recorded.activeConfig, analysisConfig, semantics).replay(
            frames = recorder.frames,
            revokes = recorder.revokes,
            classMap = { recorder.classMaps[it.seq] },
        )
        assertNull(report.firstMismatch?.toString(), report.firstMismatch)
        assertTrue(report.exact)
        assertEquals(frameCount.toInt(), report.framesReplayed)
        assertEquals(recorded.events.size, report.decisions)

        // The revokes matter in this scene: without them the replay diverges, so their ordering is
        // really what made it exact.
        val withoutRevokes = RegressionReplay(recorded.activeConfig, analysisConfig, semantics)
            .replay(recorder.frames, emptyList(), classMap = { recorder.classMaps[it.seq] })
        assertTrue("revokes should change later decisions", withoutRevokes.firstMismatch != null)

        // Counterfactual delivery, run on the recorded config: taking each revoke where the event is
        // unchanged must reproduce the record exactly; taking none must not.
        fun counterfactual(delivery: RegressionReplay.CounterfactualDelivery): List<List<EventRecord>> {
            val decided = mutableListOf<List<EventRecord>>()
            val report = RegressionReplay(recorded.activeConfig, analysisConfig, semantics).replay(
                recorder.frames, recorder.revokes, classMap = { recorder.classMaps[it.seq] },
                onFrame = { _, outcome -> decided += outcome.events.map { it.toRecord().withoutId() } },
                counterfactual = delivery,
            )
            assertNull(report.firstMismatch)
            return decided
        }
        val asRecorded = recorder.frames.filter { it.failure == null }.map { frame -> frame.events.map(EventRecord::withoutId) }
        assertEquals(asRecorded, counterfactual(RegressionReplay.CounterfactualDelivery.UNCHANGED_AS_RECORDED))
        assertTrue(asRecorded != counterfactual(RegressionReplay.CounterfactualDelivery.ALL_DELIVERED))
    }

    @Test
    fun `with the ground contact on, the recorded geometry replays exactly and is what makes it exact`() {
        val plain = runSession(record = false, groundContact = true)
        val recorded = runSession(record = true, groundContact = true)
        val recorder = recorded.recorder!!
        assertEquals(plain.events.map { e -> e.map { it.toRecord().withoutId() } }, recorded.events.map { e -> e.map { it.toRecord().withoutId() } })
        assertTrue("every frame records its geometry", recorder.frames.all { it.geometry?.quality == "Degraded" })

        val raised = mutableListOf<Long>()
        val report = RegressionReplay(recorded.activeConfig, analysisConfig, semantics).replay(
            frames = recorder.frames,
            revokes = recorder.revokes,
            classMap = { recorder.classMaps[it.seq] },
            onFrame = { record, outcome ->
                if (outcome.obstacles.any { it.distanceEstimate?.let { e -> e.used != e.today } == true }) raised += record.seq
            },
        )
        assertNull(report.firstMismatch?.toString(), report.firstMismatch)
        assertEquals(frameCount.toInt(), report.framesReplayed)
        assertTrue("the ground contact must raise some level for this to test anything", raised.isNotEmpty())

        // Without the recorded geometry every distance falls back to today's, and the replay diverges.
        val withoutGeometry = RegressionReplay(recorded.activeConfig, analysisConfig, semantics)
            .replay(recorder.frames, recorder.revokes, classMap = { recorder.classMaps[it.seq] }, geometryOf = { null })
        assertTrue("geometry should change decisions", withoutGeometry.firstMismatch != null)
    }

    @Test
    fun `a record written to disk replays through the reader`() {
        val session = runSession(record = true)
        val recorder = session.recorder!!
        val dir = java.nio.file.Files.createTempDirectory("regression-capture").toFile()
        try {
            // As RegressionCapture writes it: class maps in seq order, frames pointing at them.
            java.io.FileOutputStream(java.io.File(dir, CaptureSchema.CLASS_MAPS_FILE)).use { out ->
                recorder.frames.forEach { frame ->
                    recorder.classMaps[frame.seq]?.let { ids ->
                        val ref = frame.semantic!!.classMap!!
                        out.write(ClassMapStore.encode(frame.seq, ref.width, ref.height, ids).bytes)
                    }
                }
            }
            java.io.File(dir, CaptureSchema.REGRESSION_FILE).writeText(
                (recorder.frames.map(CaptureSchema::encodeRecord) + recorder.revokes.map(CaptureSchema::encodeRecord))
                    .joinToString(separator = "\n", postfix = "\n"),
            )
            java.io.File(dir, CaptureSchema.MANIFEST_FILE).writeText(
                CaptureSchema.encodeManifest(
                    com.sailens.guidance.trace.capture.CaptureManifest(
                        sessionId = "synthetic", captureMode = com.sailens.guidance.trace.capture.CaptureModes.MODEL_REGRESSION,
                        startedWallMs = 1, startedElapsedRealtimeNanos = 1, deviceManufacturer = "Test", deviceModel = "JVM",
                        sdkInt = 0, complete = true,
                        regression = com.sailens.guidance.trace.capture.RegressionManifest(
                            perceptionConfig = CaptureSchema.json.encodeToJsonElement(PerceptionConfig.serializer(), session.activeConfig),
                            analysisConfig = CaptureSchema.json.encodeToJsonElement(AnalysisConfig.serializer(), analysisConfig),
                            taxonomyId = semantics.taxonomyId.value,
                        ),
                    ),
                ),
            )
            val read = com.sailens.guidance.trace.capture.CaptureSessionReader.read(dir).let {
                (it as com.sailens.guidance.trace.capture.CaptureReadResult.Read).session
            }
            val block = read.manifest.regression!!
            val report = RegressionReplay(
                CaptureSchema.json.decodeFromJsonElement(PerceptionConfig.serializer(), block.perceptionConfig!!),
                CaptureSchema.json.decodeFromJsonElement(AnalysisConfig.serializer(), block.analysisConfig!!),
                semantics,
            ).replay(read.regressionFrames, read.revokes, classMap = { com.sailens.guidance.trace.capture.CaptureSessionReader.classMap(read, it) })
            assertNull(report.firstMismatch?.toString(), report.firstMismatch)
            assertEquals(frameCount.toInt(), report.framesReplayed)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `replay notices a decision that differs from the record`() {
        val recorder = runSession(record = true).recorder!!
        val tampered = recorder.frames.toMutableList()
        val index = tampered.indexOfFirst { it.events.isNotEmpty() }
        tampered[index] = tampered[index].copy(events = emptyList())
        val report = RegressionReplay(perceptionConfig, analysisConfig, semantics)
            .replay(tampered, recorder.revokes, classMap = { recorder.classMaps[it.seq] })
        assertEquals(tampered[index].seq, report.firstMismatch!!.seq)
        assertEquals("decided events", report.firstMismatch!!.what)
    }

    @Test
    fun `replay notices an unused clock reading and stops before a lost frame`() {
        val recorder = runSession(record = true).recorder!!
        val tampered = recorder.frames.toMutableList()
        tampered[3] = tampered[3].copy(clocks = tampered[3].clocks + (RegressionClocks.DECIDE to tampered[3].clocks.getValue(RegressionClocks.DECIDE) + 1L))
        val replay = RegressionReplay(perceptionConfig, analysisConfig, semantics)
        val report = replay.replay(tampered, recorder.revokes, classMap = { recorder.classMaps[it.seq] })
        assertEquals(tampered[3].seq, report.firstMismatch!!.seq)

        val upToLoss = replay.replay(recorder.frames, recorder.revokes, classMap = { recorder.classMaps[it.seq] }, firstLostSeq = 11)
        assertEquals(10, upToLoss.framesReplayed)
        assertEquals(11L, upToLoss.stoppedAtLostSeq)
        assertNull(upToLoss.firstMismatch)
    }

    private object SilentLog : LogService {
        override fun debug(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun info(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun warning(tag: String, message: String, data: Map<String, Any>?, throwable: Throwable?) = Unit
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }

    private object NoTrace : TraceService {
        override fun startSession(metadata: SessionTraceMetadata) = Unit
        override fun recordFrame(frameTrace: FrameTrace) = Unit
        override fun recordOverlayRender(
            renderedAt: Long, renderMs: Long, overlayMode: String, bitmapRendered: Boolean,
            sourceSequenceNumber: Long, sourcePipelineCompletedAt: Long, sourceAgeMs: Long,
        ) = Unit
        override fun recordPromptOutcome(outcome: PromptOutcomeTrace) = Unit
        override fun recordError(sessionId: String, stage: String, throwable: Throwable) = Unit
        override fun finishSession(summary: SessionTraceSummary) = Unit
    }
}
