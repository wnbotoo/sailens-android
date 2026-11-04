package com.sailens.guidance.usecase.scene

import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.depth.DistanceFrame
import com.sailens.guidance.depth.DistanceEstimate
import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.ImagePixelFormat
import com.sailens.core.geometry.NormalizedRect
import com.sailens.core.log.LogService
import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.config.PerceptionConfig
import com.sailens.guidance.config.PipelinePerformanceBudget
import com.sailens.guidance.config.TraceRuntimeConfig
import com.sailens.guidance.model.common.DistanceLevel
import com.sailens.guidance.model.common.PerceptionProfile
import com.sailens.guidance.motion.NoOrientationSource
import com.sailens.guidance.motion.OrientationSource
import com.sailens.guidance.model.perception.ObstacleModelOutput
import com.sailens.guidance.model.perception.SegmentationMask
import com.sailens.guidance.model.perception.SegmentationOutput
import com.sailens.guidance.processor.analysis.ConnectivityChecker
import com.sailens.guidance.processor.analysis.CrossValidator
import com.sailens.guidance.processor.analysis.FrameQualityAnalyzer
import com.sailens.guidance.processor.analysis.GroundRecognitionAnalyzer
import com.sailens.guidance.processor.analysis.GroundTypeDetector
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
import com.sailens.guidance.repository.DepthRepository
import com.sailens.guidance.repository.DeviceSensorRepository
import com.sailens.guidance.repository.ObstacleProvider
import com.sailens.guidance.repository.PerceptionRepository
import com.sailens.guidance.semantics.CityscapesNavigationSemantics
import com.sailens.guidance.trace.NoOpTraceService
import com.sailens.guidance.depth.GroundContactDistanceConfig
import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.model.trace.FrameTrace
import com.sailens.guidance.safety.GuidanceCapability
import com.sailens.guidance.safety.GuidanceQualification
import com.sailens.guidance.safety.traceLevel
import com.sailens.guidance.safety.traceReasons
import com.sailens.guidance.safety.visionFaults
import com.sailens.guidance.service.TraceService
import com.sailens.guidance.usecase.decision.DecideEventsUseCase
import com.sailens.guidance.usecase.perception.AnalyzeSceneUseCase
import com.sailens.guidance.usecase.perception.ProcessFrameUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A session whose frames keep failing must end, so the shell can tell the user out loud. Before,
 * every failed frame was dropped and the session kept "running" -- producing no prompts at all.
 */
class StartSceneAnalysisFailureTest {

    @Test
    fun `a run of failed frames ends the session with a pipeline failure`() {
        val useCase = useCase(repository = ScriptedRepository(List(10) { false }), maxFailures = 3)

        try {
            runBlocking { useCase(frames(10), releaseFrame = {}, semanticMaskSnapshot = { false }).toList() }
            fail("the session should have ended")
        } catch (error: GuidancePipelineFailedException) {
            assertEquals(3, error.consecutiveFailures)
        }
    }

    @Test
    fun `isolated failures are dropped and the session carries on`() {
        // fail, fail, ok, fail, fail, ok: never three in a row.
        val script = listOf(false, false, true, false, false, true)
        val useCase = useCase(repository = ScriptedRepository(script), maxFailures = 3)

        val results = runBlocking {
            useCase(frames(script.size), releaseFrame = {}, semanticMaskSnapshot = { false }).toList()
        }

        assertEquals(2, results.size)
        assertTrue(results.all { it.sequenceNumber in setOf(3L, 6L) })
    }

    // Not a failure case, but this is where a whole session can be run end to end.
    @Test
    fun `the semantic mask leaves the pipeline only when asked for`() {
        val script = List(3) { true }

        val unasked = runBlocking {
            useCase(ScriptedRepository(script), maxFailures = 3)(frames(3), releaseFrame = {}, semanticMaskSnapshot = { false }).toList()
        }
        val asked = runBlocking {
            useCase(ScriptedRepository(script), maxFailures = 3)(frames(3), releaseFrame = {}, semanticMaskSnapshot = { true }).toList()
        }

        assertTrue(unasked.all { it.segmentationMask == null })
        assertEquals(3, asked.size)
        asked.forEach { result ->
            val mask = checkNotNull(result.segmentationMask)
            assertEquals(4, mask.width)
            assertTrue(mask.classMap.all { it == 0 })
        }
    }

    @Test
    fun `every frame goes back to its source once, after it has been processed`() {
        // ok, fail, ok: a processed frame, a failed one and one more processed frame.
        val events = mutableListOf<String>()
        val useCase = useCase(ScriptedRepository(listOf(true, false, true), events), maxFailures = 3)

        runBlocking {
            useCase(
                frames(3),
                releaseFrame = { events += "release ${it.sequenceNumber}" },
                semanticMaskSnapshot = { false },
            ).toList()
        }

        assertEquals(
            listOf("segment 1", "release 1", "segment 2", "release 2", "segment 3", "release 3"),
            events,
        )
    }

    @Test
    fun `the frame that ends the session is still given back`() {
        val released = mutableListOf<Long>()
        val useCase = useCase(ScriptedRepository(List(5) { false }), maxFailures = 2)

        try {
            runBlocking {
                useCase(
                    frames(5),
                    releaseFrame = { released += it.sequenceNumber },
                    semanticMaskSnapshot = { false },
                ).toList()
            }
            fail("the session should have ended")
        } catch (_: GuidancePipelineFailedException) {
        }

        assertEquals(listOf(1L, 2L), released)
    }

    @Test
    fun `the session holds the orientation sensors exactly while it runs, failure or not`() {
        val calls = mutableListOf<String>()
        val sensors = object : OrientationSource by NoOrientationSource {
            override fun start() { calls += "start" }
            override fun stop() { calls += "stop" }
        }

        runBlocking {
            useCase(ScriptedRepository(List(3) { true }), maxFailures = 3, orientation = sensors)(
                frames(3), releaseFrame = {}, semanticMaskSnapshot = { false },
            ).toList()
        }
        assertEquals(listOf("start", "stop"), calls)

        calls.clear()
        try {
            runBlocking {
                useCase(ScriptedRepository(List(5) { false }), maxFailures = 2, orientation = sensors)(
                    frames(5), releaseFrame = {}, semanticMaskSnapshot = { false },
                ).toList()
            }
            fail("the session should have ended")
        } catch (_: GuidancePipelineFailedException) {
        }
        assertEquals(listOf("start", "stop"), calls)
    }

    // Not a failure case either: the qualification and its trace fields, over a whole session.
    @Test
    fun `each frame carries its qualification, traced with the frame quality and the receipt-to-decision time`() {
        val traced = mutableListOf<FrameTrace>()
        val trace = object : TraceService by NoOpTraceService {
            override fun recordFrame(frameTrace: FrameTrace) {
                traced += frameTrace
            }
        }
        // Flat frames read as a covered lens: classified at once, debounced after a few frames.
        val received = 900_000_000L
        val results = runBlocking {
            useCase(ScriptedRepository(List(10) { true }), maxFailures = 3, trace = trace)(
                frames(10, receivedAt = { seq -> if (seq % 2L == 0L) received else 0L }),
                releaseFrame = {},
                semanticMaskSnapshot = { false },
            ).toList()
        }

        assertEquals(10, traced.size)
        val levels = results.map { it.qualification.baseline.traceLevel }
        assertTrue("$levels", "qualified" in levels && "unqualified" in levels)
        results.zip(traced).forEach { (result, frame) ->
            assertEquals(result.visionFaults, result.qualification.baseline.visionFaults())
            assertEquals(result.qualification.baseline.traceLevel, frame.qualification)
            assertEquals(result.qualification.baseline.traceReasons, frame.qualificationReasons)
            assertEquals("obstructed", frame.frameQualityClassified)
            assertEquals(result.visionFaults.frameQuality.name.lowercase(), frame.frameQuality)
            assertEquals(GuidanceCapability.entries.map { it.name.lowercase() }.toSet(), frame.capabilities.keys)
            // Received at 900 ms by the elapsed clock, decided at its 1 000 ms; unknown when the source did not say.
            if (frame.sequenceNumber % 2L == 0L) {
                assertEquals(100.0, frame.receiptToDecisionMs!!, 1e-9)
            } else {
                assertNull(frame.receiptToDecisionMs)
            }
        }
    }

    @Test
    fun `the optional inputs change the capabilities, never the baseline`() {
        fun run(orientation: OrientationSource, groundContact: Boolean, phone: PhoneGeometrySettings): List<GuidanceQualification> = runBlocking {
            useCase(
                ScriptedRepository(List(8) { true }),
                maxFailures = 3,
                orientation = orientation,
                groundContact = GroundContactDistanceConfig(enabled = groundContact),
                phone = phone,
            )(frames(8), releaseFrame = {}, semanticMaskSnapshot = { false }).toList().map { it.qualification }
        }

        val turning = object : OrientationSource by NoOrientationSource {}
        val runs = listOf(NoOrientationSource, turning).flatMap { orientation ->
            listOf(true, false).flatMap { groundContact ->
                listOf(PhoneGeometrySettings.DEFAULT, PhoneGeometrySettings(1.15f, HeightSource.PRESET)).map { phone ->
                    run(orientation, groundContact, phone)
                }
            }
        }
        val baselines = runs.map { qualifications -> qualifications.map { it.baseline } }
        assertTrue(baselines.all { it == baselines.first() })
        assertTrue("the inputs did change something", runs.map { q -> q.map { it.capabilities } }.distinct().size > 1)
    }

    private fun useCase(
        repository: PerceptionRepository,
        maxFailures: Int,
        orientation: OrientationSource = NoOrientationSource,
        trace: TraceService = NoOpTraceService,
        groundContact: GroundContactDistanceConfig = GroundContactDistanceConfig(),
        phone: PhoneGeometrySettings = PhoneGeometrySettings.DEFAULT,
    ): StartSceneAnalysisUseCase {
        val perceptionConfig = PerceptionConfig(profile = PerceptionProfile.BASIC, groundContact = groundContact)
        val analysisConfig = AnalysisConfig()
        val semantics = CityscapesNavigationSemantics
        val cooldown = CooldownManager()
        return StartSceneAnalysisUseCase(
            profileManager = PerceptionProfileManager(perceptionConfig),
            perceptionRepository = repository,
            realtimeObstacleProvider = NoObstacles,
            processFrameUseCase = ProcessFrameUseCase(
                profileManager = PerceptionProfileManager(perceptionConfig),
                perceptionRepository = repository,
                realtimeObstacleProvider = NoObstacles,
                depthRepository = object : DepthRepository {
                    override fun estimateDistance(boundingBox: NormalizedRect, category: ObstacleCategory, frame: DistanceFrame) =
                        DistanceEstimate.today(DistanceLevel.FAR)
                },
                segmentationAnalyzer = SegmentationAnalyzer(analysisConfig, semantics),
                obstacleExtractor = ObstacleExtractor(perceptionConfig, semantics),
                obstacleTracker = ObstacleTracker(perceptionConfig),
                clock = { 1_000L },
            ),
            analyzeSceneUseCase = AnalyzeSceneUseCase(
                connectivityChecker = ConnectivityChecker(analysisConfig),
                roadSafetyAnalyzer = RoadSafetyAnalyzer(analysisConfig, semantics),
                groundTypeDetector = GroundTypeDetector(analysisConfig),
                sceneClassifier = SceneClassifier(analysisConfig),
                crossValidator = CrossValidator(analysisConfig),
                obstacleOcclusionAnalyzer = ObstacleOcclusionAnalyzer(perceptionConfig),
                groundRecognitionAnalyzer = GroundRecognitionAnalyzer(
                    analysisConfig,
                    semantics,
                    clock = { 1_000L },
                ),
            ),
            decideEventsUseCase = DecideEventsUseCase(
                eventGenerator = EventGenerator(analysisConfig),
                conflictResolver = EventConflictResolver(),
                eventMerger = EventMerger(),
                cooldownManager = cooldown,
                deviceSensorRepository = StillSensors,
                clock = { 1_000L },
            ),
            frameQualityAnalyzer = FrameQualityAnalyzer(),
            logService = SilentLog,
            traceService = trace,
            traceRuntimeConfig = TraceRuntimeConfig(enabled = trace !== NoOpTraceService),
            pipelineBudget = PipelinePerformanceBudget(maxConsecutiveFrameFailures = maxFailures),
            orientationSource = orientation,
            phoneGeometry = { phone },
        )
    }

    private fun frames(count: Int, receivedAt: (Long) -> Long = { 0L }) = (1..count).map { index ->
        ImageFrame(
            width = 4,
            height = 4,
            pixelBytes = ByteArray(4 * 4 * 4) { 100 },
            pixelFormat = ImagePixelFormat.RGBA_8888,
            timestamp = index * 33_000_000L,
            rotationDegrees = 0,
            sequenceNumber = index.toLong(),
            receivedElapsedRealtimeNanos = receivedAt(index.toLong()),
        )
    }.asFlow()

    /** Succeeds or fails per call, in order. */
    private class ScriptedRepository(
        private val script: List<Boolean>,
        private val events: MutableList<String>? = null,
    ) : PerceptionRepository {
        private var call = 0
        override val isInitialized: Boolean = true
        override suspend fun initialize() = Unit
        override suspend fun segment(frame: ImageFrame): Result<SegmentationOutput> {
            events?.add("segment ${frame.sequenceNumber}")
            val ok = script.getOrElse(call++) { true }
            if (!ok) return Result.failure(IllegalStateException("GPU delegate lost"))
            return Result.success(
                SegmentationOutput(
                    mask = SegmentationMask(width = 4, height = 4, classMap = IntArray(16)),
                    preprocessTimeMs = 1,
                    inferenceTimeMs = 1,
                    postprocessTimeMs = 1,
                )
            )
        }
        override suspend fun release() = Unit
    }

    private object NoObstacles : ObstacleProvider {
        override val isInitialized: Boolean = true
        override suspend fun initialize() = Unit
        override suspend fun detect(frame: ImageFrame) = ObstacleModelOutput(detections = emptyList())
        override fun release() = Unit
    }

    private object StillSensors : DeviceSensorRepository {
        override val deviceRotation: StateFlow<Int> = MutableStateFlow(0)
        override val deviceRotationValue: Int = 0
        override val deviceRotationDegree: Int = 0
        override val isStationary: StateFlow<Boolean> = MutableStateFlow(false)
        override fun startObserving() = Unit
        override fun stopObserving() = Unit
    }

    private object SilentLog : LogService {
        override fun debug(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun info(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun warning(tag: String, message: String, data: Map<String, Any>?, throwable: Throwable?) = Unit
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }
}
