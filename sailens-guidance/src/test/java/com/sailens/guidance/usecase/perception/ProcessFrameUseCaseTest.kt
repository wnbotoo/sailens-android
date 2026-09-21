package com.sailens.guidance.usecase.perception

import com.sailens.vision.taxonomy.TaxonomyId
import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.config.PerceptionConfig
import com.sailens.guidance.model.common.DistanceLevel
import com.sailens.guidance.model.common.GroundType
import com.sailens.guidance.model.common.ObstacleProviderType
import com.sailens.core.geometry.NormalizedRect
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.common.ObstacleRunKind
import com.sailens.guidance.model.common.PerceptionProfile
import com.sailens.guidance.model.common.SemanticProviderType
import com.sailens.guidance.semantics.NavigationSemantics
import com.sailens.guidance.model.perception.ObstacleDetection
import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.ImagePixelFormat
import com.sailens.guidance.model.perception.ObstacleModelOutput
import com.sailens.guidance.model.perception.SegmentationMask
import com.sailens.guidance.model.perception.SegmentationOutput
import com.sailens.guidance.processor.perception.ObstacleExtractor
import com.sailens.guidance.processor.perception.ObstacleTracker
import com.sailens.guidance.processor.perception.PerceptionProfileManager
import com.sailens.guidance.processor.perception.SegmentationAnalyzer
import com.sailens.guidance.repository.DepthRepository
import com.sailens.guidance.repository.ObstacleProvider
import com.sailens.guidance.repository.PerceptionRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessFrameUseCaseTest {

    private class FakeClock(var nowMs: Long = 1_000L)

    @Test
    fun `basic profile runs semantic only and never obstacle models`() {
        val clock = FakeClock()
        val realtimeProvider = FakeObstacleProvider()
        val useCase = createUseCase(
            profile = PerceptionProfile.BASIC,
            realtimeObstacleProvider = realtimeProvider,
            clock = clock,
        )

        runBlocking {
            repeat(3) { index ->
                clock.nowMs = 1_000L + index * 200L
                val result = useCase(createFrame(sequenceNumber = index + 1L))
                assertTrue(result.isSuccess)
            }
        }

        assertEquals(0, realtimeProvider.detectCalls)
    }

    @Test
    fun `basic profile does not require obstacle providers to be initialized`() {
        val clock = FakeClock()
        val useCase = createUseCase(
            profile = PerceptionProfile.BASIC,
            realtimeObstacleProvider = FakeObstacleProvider(initialized = false),
            clock = clock,
        )

        val result = runBlocking { useCase(createFrame(sequenceNumber = 1)) }

        assertTrue(result.isSuccess)
    }

    @Test
    fun `semantic runs at configured fps and reuses cached analysis in between`() {
        val clock = FakeClock()
        val repository = FakePerceptionRepository()
        val useCase = createUseCase(
            profile = PerceptionProfile.BASIC,
            perceptionRepository = repository,
            semanticTargetFps = 10,
            clock = clock,
        )

        runBlocking {
            clock.nowMs = 1_000L
            useCase(createFrame(sequenceNumber = 1))
            clock.nowMs = 1_050L
            useCase(createFrame(sequenceNumber = 2))
            clock.nowMs = 1_101L
            useCase(createFrame(sequenceNumber = 3))
        }

        assertEquals(2, repository.segmentCalls)
    }

    @Test
    fun `detection runs at configured fps with tracker prediction in between`() {
        val clock = FakeClock()
        val obstacleProvider = FakeObstacleProvider()
        val useCase = createUseCase(
            profile = PerceptionProfile.DEFAULT,
            realtimeObstacleProvider = obstacleProvider,
            detectionTargetFps = 10,
            clock = clock,
        )

        runBlocking {
            clock.nowMs = 1_000L
            val first = useCase(createFrame(sequenceNumber = 1)).getOrThrow()
            clock.nowMs = 1_050L
            val second = useCase(createFrame(sequenceNumber = 2)).getOrThrow()
            clock.nowMs = 1_101L
            val third = useCase(createFrame(sequenceNumber = 3)).getOrThrow()

            assertEquals(1, first.obstacles.size)
            assertEquals(ObstacleRunKind.DETECTION, first.obstacleRunKind)
            // det 未到间隔的帧由跟踪器预测补偿
            assertEquals(1, second.obstacles.size)
            assertEquals(0, second.obstacleDetections.size)
            assertEquals(ObstacleRunKind.NONE, second.obstacleRunKind)
            assertEquals(1, third.obstacles.size)
        }

        assertEquals(2, obstacleProvider.detectCalls)
    }

    @Test
    fun `uncapped detection runs on every processed frame`() {
        val clock = FakeClock()
        val obstacleProvider = FakeObstacleProvider()
        val useCase = createUseCase(
            profile = PerceptionProfile.DEFAULT,
            realtimeObstacleProvider = obstacleProvider,
            detectionTargetFps = 0,
            clock = clock,
        )

        runBlocking {
            repeat(3) { index ->
                clock.nowMs = 1_000L + index * 10L
                useCase(createFrame(sequenceNumber = index + 1L))
            }
        }

        assertEquals(3, obstacleProvider.detectCalls)
    }

    @Test
    fun `tracked obstacles expire after detection result ttl when detection idles`() {
        val clock = FakeClock()
        val obstacleProvider = FakeObstacleProvider()
        val useCase = createUseCase(
            profile = PerceptionProfile.DEFAULT,
            realtimeObstacleProvider = obstacleProvider,
            detectionTargetFps = 1,
            detectionResultTtlMs = 300,
            clock = clock,
        )

        runBlocking {
            clock.nowMs = 1_000L
            val first = useCase(createFrame(sequenceNumber = 1)).getOrThrow()
            assertEquals(1, first.obstacles.size)

            clock.nowMs = 1_200L
            val second = useCase(createFrame(sequenceNumber = 2)).getOrThrow()
            assertEquals(1, second.obstacles.size)

            clock.nowMs = 1_400L
            val third = useCase(createFrame(sequenceNumber = 3)).getOrThrow()
            assertEquals(0, third.obstacles.size)
        }

        assertEquals(1, obstacleProvider.detectCalls)
    }

    @Test
    fun `configured realtime obstacle provider fails fast when not initialized`() {
        val useCase = createUseCase(
            profile = PerceptionProfile.DEFAULT,
            realtimeObstacleProvider = FakeObstacleProvider(initialized = false),
            clock = FakeClock(),
        )

        val error = runCatching {
            runBlocking { useCase(createFrame(sequenceNumber = 1)) }
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(
            error?.message?.contains("Realtime obstacle provider is configured") == true
        )
    }

    private fun createUseCase(
        profile: PerceptionProfile,
        clock: FakeClock,
        realtimeObstacleProvider: ObstacleProvider = FakeObstacleProvider(),
        perceptionRepository: FakePerceptionRepository = FakePerceptionRepository(),
        semanticTargetFps: Int = 0,
        detectionTargetFps: Int = 10,
        detectionResultTtlMs: Long = 500,
        distanceLevel: DistanceLevel = DistanceLevel.MEDIUM,
    ): ProcessFrameUseCase {
        val config = PerceptionConfig(
            profile = profile,
            semanticProviderType = SemanticProviderType.SEGMENTATION_MODEL,
            realtimeObstacleProviderType = ObstacleProviderType.DETECTION_MODEL,
            semanticTargetFps = semanticTargetFps,
            detectionTargetFps = detectionTargetFps,
            detectionResultTtlMs = detectionResultTtlMs,
            minObstacleConfidence = 0.1f,
            trackerMinStableFrames = 1,
        )
        val navigationSemantics = FakeSemanticClassMapper()

        return ProcessFrameUseCase(
            profileManager = PerceptionProfileManager(config),
            perceptionRepository = perceptionRepository,
            realtimeObstacleProvider = realtimeObstacleProvider,
            depthRepository = object : DepthRepository {
                override fun estimateDistance(boundingBox: NormalizedRect): DistanceLevel = distanceLevel
            },
            segmentationAnalyzer = SegmentationAnalyzer(AnalysisConfig(), navigationSemantics),
            obstacleExtractor = ObstacleExtractor(config, navigationSemantics),
            obstacleTracker = ObstacleTracker(config),
            clock = { clock.nowMs },
        )
    }

    private fun createFrame(sequenceNumber: Long): ImageFrame {
        return ImageFrame(
            width = 4,
            height = 4,
            pixelBytes = ByteArray(4 * 4 * 4),
            pixelFormat = ImagePixelFormat.RGBA_8888,
            timestamp = sequenceNumber * 100L,
            rotationDegrees = 0,
            sequenceNumber = sequenceNumber,
        )
    }

    private class FakePerceptionRepository(
        private val classMap: IntArray = IntArray(16) { 0 },
    ) : PerceptionRepository {
        var segmentCalls: Int = 0

        override val isInitialized: Boolean = true

        override suspend fun initialize() = Unit

        override suspend fun segment(frame: ImageFrame): Result<SegmentationOutput> {
            segmentCalls++
            return Result.success(
                SegmentationOutput(
                    mask = SegmentationMask(
                        width = 4,
                        height = 4,
                        classMap = classMap.copyOf(),
                    ),
                    preprocessTimeMs = 1,
                    inferenceTimeMs = 1,
                    postprocessTimeMs = 1,
                )
            )
        }

        override suspend fun release() = Unit
    }

    private class FakeObstacleProvider(
        initialized: Boolean = true,
        private val detections: List<ObstacleDetection> = listOf(personDetection()),
    ) : ObstacleProvider {
        var detectCalls: Int = 0
        override val isInitialized: Boolean = initialized

        override suspend fun initialize() = Unit

        override suspend fun detect(frame: ImageFrame): ObstacleModelOutput {
            detectCalls++
            return ObstacleModelOutput(
                detections = detections,
                preprocessTimeMs = 5,
                inferenceTimeMs = 7,
                outputReadTimeMs = 1,
                postprocessTimeMs = 2,
            )
        }

        override fun release() = Unit

        private companion object {
            fun personDetection() = ObstacleDetection(
                classId = 0,
                className = "person",
                confidence = 0.9f,
                boundingBox = NormalizedRect(0.4f, 0.4f, 0.2f, 0.2f),
                category = ObstacleCategory.PERSON,
            )
        }
    }

    private class FakeSemanticClassMapper : NavigationSemantics {
        override val taxonomyId: TaxonomyId = TaxonomyId("test")
        override val classCount: Int = 2
        override fun isPassable(classId: Int): Boolean = classId == 0
        override fun isObstacle(classId: Int): Boolean = classId == 1
        override fun isRoad(classId: Int): Boolean = classId == 0
        override fun isTrafficLight(classId: Int): Boolean = false
        override fun toGroundType(classId: Int): GroundType = GroundType.ROAD
        override fun toObstacleCategory(classId: Int): ObstacleCategory =
            if (classId == 1) ObstacleCategory.STATIC_OBSTACLE else ObstacleCategory.UNKNOWN

    }
}
