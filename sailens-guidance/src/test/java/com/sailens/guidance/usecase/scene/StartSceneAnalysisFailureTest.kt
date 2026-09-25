package com.sailens.guidance.usecase.scene

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
import com.sailens.guidance.usecase.decision.DecideEventsUseCase
import com.sailens.guidance.usecase.perception.AnalyzeSceneUseCase
import com.sailens.guidance.usecase.perception.ProcessFrameUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
            runBlocking { useCase(frames(10), semanticMaskSnapshot = { false }).toList() }
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

        val results = runBlocking { useCase(frames(script.size), semanticMaskSnapshot = { false }).toList() }

        assertEquals(2, results.size)
        assertTrue(results.all { it.sequenceNumber in setOf(3L, 6L) })
    }

    // Not a failure case, but this is where a whole session can be run end to end.
    @Test
    fun `the semantic mask leaves the pipeline only when asked for`() {
        val script = List(3) { true }

        val unasked = runBlocking {
            useCase(ScriptedRepository(script), maxFailures = 3)(frames(3), semanticMaskSnapshot = { false }).toList()
        }
        val asked = runBlocking {
            useCase(ScriptedRepository(script), maxFailures = 3)(frames(3), semanticMaskSnapshot = { true }).toList()
        }

        assertTrue(unasked.all { it.segmentationMask == null })
        assertEquals(3, asked.size)
        asked.forEach { result ->
            val mask = checkNotNull(result.segmentationMask)
            assertEquals(4, mask.width)
            assertTrue(mask.classMap.all { it == 0 })
        }
    }

    private fun useCase(repository: PerceptionRepository, maxFailures: Int): StartSceneAnalysisUseCase {
        val perceptionConfig = PerceptionConfig(profile = PerceptionProfile.BASIC)
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
                    override fun estimateDistance(boundingBox: NormalizedRect) = DistanceLevel.FAR
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
            traceService = NoOpTraceService,
            traceRuntimeConfig = TraceRuntimeConfig(enabled = false),
            pipelineBudget = PipelinePerformanceBudget(maxConsecutiveFrameFailures = maxFailures),
        )
    }

    private fun frames(count: Int) = (1..count).map { index ->
        ImageFrame(
            width = 4,
            height = 4,
            pixelBytes = ByteArray(4 * 4 * 4) { 100 },
            pixelFormat = ImagePixelFormat.RGBA_8888,
            timestamp = index * 33_000_000L,
            rotationDegrees = 0,
            sequenceNumber = index.toLong(),
        )
    }.asFlow()

    /** Succeeds or fails per call, in order. */
    private class ScriptedRepository(private val script: List<Boolean>) : PerceptionRepository {
        private var call = 0
        override val isInitialized: Boolean = true
        override suspend fun initialize() = Unit
        override suspend fun segment(frame: ImageFrame): Result<SegmentationOutput> {
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
