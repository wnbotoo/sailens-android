package com.friady.sailens.domain.usecase.perception

import com.friady.sailens.domain.config.AnalysisConfig
import com.friady.sailens.domain.config.PerceptionConfig
import com.friady.sailens.domain.model.common.DistanceLevel
import com.friady.sailens.domain.model.common.GroundType
import com.friady.sailens.domain.model.common.InferenceStrategy
import com.friady.sailens.domain.model.common.InstanceProviderType
import com.friady.sailens.domain.model.common.NormalizedRect
import com.friady.sailens.domain.model.common.ObstacleCategory
import com.friady.sailens.domain.model.common.PerceptionMode
import com.friady.sailens.domain.model.common.SemanticProviderType
import com.friady.sailens.domain.model.perception.ClassMapper
import com.friady.sailens.domain.model.perception.DetectedInstance
import com.friady.sailens.domain.model.perception.ImageFrame
import com.friady.sailens.domain.model.perception.ImagePixelFormat
import com.friady.sailens.domain.model.perception.SegmentationMask
import com.friady.sailens.domain.model.perception.SegmentationOutput
import com.friady.sailens.domain.processor.perception.ObstacleExtractor
import com.friady.sailens.domain.processor.perception.ObstacleTracker
import com.friady.sailens.domain.processor.perception.SegmentationAnalyzer
import com.friady.sailens.domain.repository.DepthRepository
import com.friady.sailens.domain.repository.InstanceSegmentationProvider
import com.friady.sailens.domain.repository.PerceptionRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessFrameUseCaseTest {

    @Test
    fun `simultaneous strategy runs instance inference every frame`() {
        val instanceProvider = FakeInstanceProvider()
        val useCase = createUseCase(InferenceStrategy.SIMULTANEOUS, instanceProvider)

        val first = runBlocking { useCase(createFrame(sequenceNumber = 1, timestamp = 100L)) }
        val second = runBlocking { useCase(createFrame(sequenceNumber = 2, timestamp = 200L)) }

        assertTrue(first.isSuccess)
        assertTrue(second.isSuccess)
        assertEquals(2, instanceProvider.detectCalls)
        assertEquals(1, second.getOrThrow().obstacles.size)
    }

    @Test
    fun `alternating strategy runs instance inference every other frame`() {
        val instanceProvider = FakeInstanceProvider()
        val useCase = createUseCase(InferenceStrategy.ALTERNATING, instanceProvider)

        val first = runBlocking { useCase(createFrame(sequenceNumber = 1, timestamp = 100L)) }
        val second = runBlocking { useCase(createFrame(sequenceNumber = 2, timestamp = 200L)) }

        assertTrue(first.isSuccess)
        assertTrue(second.isSuccess)
        assertEquals(1, instanceProvider.detectCalls)
        assertEquals(1, first.getOrThrow().obstacles.size)
        assertEquals(1, second.getOrThrow().obstacles.size)
    }

    @Test
    fun `semantic frame skipping reuses previous analysis when enabled`() {
        val repository = FakePerceptionRepository()
        val useCase = createUseCase(
            inferenceStrategy = InferenceStrategy.ALTERNATING,
            instanceProvider = FakeInstanceProvider(),
            perceptionRepository = repository,
            enableSemanticFrameSkipping = true,
            semanticFrameInterval = 2,
        )

        runBlocking {
            useCase(createFrame(sequenceNumber = 1, timestamp = 100L))
            useCase(createFrame(sequenceNumber = 2, timestamp = 200L))
            useCase(createFrame(sequenceNumber = 3, timestamp = 300L))
        }

        assertEquals(2, repository.segmentCalls)
    }

    @Test
    fun `semantic frame skipping can be disabled`() {
        val repository = FakePerceptionRepository()
        val useCase = createUseCase(
            inferenceStrategy = InferenceStrategy.ALTERNATING,
            instanceProvider = FakeInstanceProvider(),
            perceptionRepository = repository,
            enableSemanticFrameSkipping = false,
        )

        runBlocking {
            useCase(createFrame(sequenceNumber = 1, timestamp = 100L))
            useCase(createFrame(sequenceNumber = 2, timestamp = 200L))
            useCase(createFrame(sequenceNumber = 3, timestamp = 300L))
        }

        assertEquals(3, repository.segmentCalls)
    }

    private fun createUseCase(
        inferenceStrategy: InferenceStrategy,
        instanceProvider: InstanceSegmentationProvider,
        perceptionRepository: FakePerceptionRepository = FakePerceptionRepository(),
        enableSemanticFrameSkipping: Boolean = true,
        semanticFrameInterval: Int = 2,
    ): ProcessFrameUseCase {
        val config = PerceptionConfig(
            mode = PerceptionMode.COMBINED,
            semanticProviderType = SemanticProviderType.YOLO26_SEM,
            instanceProviderType = InstanceProviderType.YOLO26_SEG,
            inferenceStrategy = inferenceStrategy,
            enableSemanticFrameSkipping = enableSemanticFrameSkipping,
            semanticFrameInterval = semanticFrameInterval,
            minObstacleConfidence = 0.1f,
            trackerMinStableFrames = 1,
        )
        val classMapper = FakeSemanticClassMapper()

        return ProcessFrameUseCase(
            perceptionConfig = config,
            perceptionRepository = perceptionRepository,
            instanceProvider = instanceProvider,
            depthRepository = object : DepthRepository {
                override fun estimateDistance(boundingBox: NormalizedRect): DistanceLevel = DistanceLevel.MEDIUM
            },
            segmentationAnalyzer = SegmentationAnalyzer(AnalysisConfig(), classMapper),
            obstacleExtractor = ObstacleExtractor(config, classMapper),
            obstacleTracker = ObstacleTracker(config),
        )
    }

    private fun createFrame(sequenceNumber: Long, timestamp: Long): ImageFrame {
        return ImageFrame(
            width = 4,
            height = 4,
            pixelBytes = ByteArray(4 * 4 * 4),
            pixelFormat = ImagePixelFormat.RGBA_8888,
            timestamp = timestamp,
            rotationDegrees = 0,
            sequenceNumber = sequenceNumber,
        )
    }

    private class FakePerceptionRepository : PerceptionRepository {
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
                        classMap = IntArray(16) { 0 },
                    ),
                    preprocessTimeMs = 1,
                    inferenceTimeMs = 1,
                    postprocessTimeMs = 1,
                )
            )
        }

        override suspend fun release() = Unit
    }

    private class FakeInstanceProvider : InstanceSegmentationProvider {
        var detectCalls: Int = 0
        override val isInitialized: Boolean = true

        override suspend fun initialize() = Unit

        override suspend fun detect(frame: ImageFrame): List<DetectedInstance> {
            detectCalls++
            return listOf(
                DetectedInstance(
                    classId = 0,
                    className = "person",
                    confidence = 0.9f,
                    boundingBox = NormalizedRect(0.4f, 0.4f, 0.2f, 0.2f),
                    mask = null,
                    category = ObstacleCategory.PERSON,
                )
            )
        }

        override fun release() = Unit
    }

    private class FakeSemanticClassMapper : ClassMapper {
        override val datasetName: String = "test"
        override val classCount: Int = 2
        override fun isPassable(classId: Int): Boolean = classId == 0
        override fun isObstacle(classId: Int): Boolean = classId == 1
        override fun isRoad(classId: Int): Boolean = classId == 0
        override fun isTrafficLight(classId: Int): Boolean = false
        override fun toGroundType(classId: Int): GroundType = GroundType.ROAD
        override fun toObstacleCategory(classId: Int): ObstacleCategory =
            if (classId == 1) ObstacleCategory.STATIC_OBSTACLE else ObstacleCategory.UNKNOWN

        override fun getClassName(classId: Int): String = if (classId == 0) "road" else "obstacle"
    }
}
