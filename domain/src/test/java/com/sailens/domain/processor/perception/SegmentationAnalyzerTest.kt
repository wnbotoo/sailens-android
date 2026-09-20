package com.sailens.domain.processor.perception

import com.sailens.vision.taxonomy.TaxonomyId
import com.sailens.domain.config.AnalysisConfig
import com.sailens.domain.model.common.GroundType
import com.sailens.domain.model.common.ObstacleCategory
import com.sailens.domain.semantics.NavigationSemantics
import com.sailens.domain.model.perception.SegmentationMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SegmentationAnalyzerTest {
    private val navigationSemantics = object : NavigationSemantics {
        override val taxonomyId: TaxonomyId = TaxonomyId("test")
        override val classCount: Int = 3

        override fun isPassable(classId: Int): Boolean = classId == ROAD
        override fun isObstacle(classId: Int): Boolean = classId == OBSTACLE
        override fun isRoad(classId: Int): Boolean = classId == ROAD
        override fun isTrafficLight(classId: Int): Boolean = false
        override fun toGroundType(classId: Int): GroundType = if (classId == ROAD) GroundType.ROAD else GroundType.UNKNOWN
        override fun toObstacleCategory(classId: Int): ObstacleCategory = if (classId == OBSTACLE) {
            ObstacleCategory.STATIC_OBSTACLE
        } else {
            ObstacleCategory.UNKNOWN
        }

    }
    private val trafficLightClassMapper = object : NavigationSemantics {
        override val taxonomyId: TaxonomyId = TaxonomyId("test")
        override val classCount: Int = 4

        override fun isPassable(classId: Int): Boolean = classId == ROAD
        override fun isObstacle(classId: Int): Boolean = classId == OBSTACLE
        override fun isRoad(classId: Int): Boolean = classId == ROAD
        override fun isTrafficLight(classId: Int): Boolean = classId == TRAFFIC_LIGHT
        override fun toGroundType(classId: Int): GroundType = if (classId == ROAD) GroundType.ROAD else GroundType.UNKNOWN
        override fun toObstacleCategory(classId: Int): ObstacleCategory = when (classId) {
            OBSTACLE,
            TRAFFIC_LIGHT -> ObstacleCategory.STATIC_OBSTACLE
            else -> ObstacleCategory.UNKNOWN
        }

    }

    @Test
    fun `navigation passable ratio focuses on lower image region`() {
        val analyzer = SegmentationAnalyzer(AnalysisConfig(), navigationSemantics)
        val mask = SegmentationMask(
            width = 4,
            height = 4,
            classMap = intArrayOf(
                BACKGROUND, BACKGROUND, BACKGROUND, BACKGROUND,
                BACKGROUND, BACKGROUND, BACKGROUND, BACKGROUND,
                ROAD, ROAD, ROAD, ROAD,
                ROAD, ROAD, ROAD, ROAD,
            ),
        )

        val result = analyzer.analyze(mask)

        assertEquals(0.5f, result.passablePixelCount.toFloat() / 16f, 0.0001f)
        assertEquals(1.0f, result.navigationPassableRatio, 0.0001f)
    }

    @Test
    fun `passable mask uses current frame instead of pixel temporal voting`() {
        val analyzer = SegmentationAnalyzer(AnalysisConfig(), navigationSemantics)

        val roadMask = SegmentationMask(width = 1, height = 1, classMap = intArrayOf(ROAD))
        val backgroundMask = SegmentationMask(width = 1, height = 1, classMap = intArrayOf(BACKGROUND))

        analyzer.analyze(roadMask)
        analyzer.analyze(roadMask)
        val currentFrame = analyzer.analyze(backgroundMask)

        assertFalse(currentFrame.passableMask.get(0, 0))
        assertEquals(0, currentFrame.passablePixelCount)
    }

    @Test
    fun `precomputed stats path matches direct analysis path`() {
        val config = AnalysisConfig(
            roadRatioSmoothWindow = 1,
            trafficLightDebounceFrames = 1,
        )
        val mask = SegmentationMask(
            width = 4,
            height = 4,
            classMap = intArrayOf(
                ROAD, ROAD, BACKGROUND, BACKGROUND,
                ROAD, OBSTACLE, BACKGROUND, BACKGROUND,
                ROAD, ROAD, ROAD, OBSTACLE,
                ROAD, ROAD, ROAD, ROAD,
            ),
        )

        val direct = SegmentationAnalyzer(config, navigationSemantics).analyze(mask)
        val stats = KotlinSegmentationStatsExtractor(config, navigationSemantics).extract(mask)
        val fromStats = SegmentationAnalyzer(config, navigationSemantics).analyze(mask, stats)

        assertEquals(direct, fromStats)
    }

    @Test
    fun `precomputed stats path does not re-extract class map`() {
        val config = AnalysisConfig(
            roadRatioSmoothWindow = 1,
            trafficLightDebounceFrames = 1,
        )
        val mask = SegmentationMask(
            width = 2,
            height = 2,
            classMap = intArrayOf(ROAD, ROAD, BACKGROUND, OBSTACLE),
        )
        val stats = KotlinSegmentationStatsExtractor(config, navigationSemantics).extract(mask)
        val rejectingExtractor = object : SegmentationStatsExtractor {
            override fun extract(segmentation: SegmentationMask) =
                error("stats extractor should not run when precomputed stats are supplied")
        }

        val result = SegmentationAnalyzer(config, navigationSemantics, rejectingExtractor).analyze(mask, stats)

        assertEquals(stats.passablePixelCount, result.passablePixelCount)
        assertEquals(stats.obstaclePixelCount, result.obstaclePixelCount)
    }

    @Test
    fun `traffic light requires enough semantic area`() {
        val config = AnalysisConfig(
            roadRatioSmoothWindow = 1,
            trafficLightDebounceFrames = 1,
            trafficLightMinPixelRatio = 0.10f,
            trafficLightMinRoadRatio = 0.30f,
        )
        val mask = SegmentationMask(
            width = 4,
            height = 4,
            classMap = intArrayOf(
                TRAFFIC_LIGHT, ROAD, ROAD, ROAD,
                ROAD, ROAD, ROAD, ROAD,
                BACKGROUND, BACKGROUND, BACKGROUND, BACKGROUND,
                BACKGROUND, BACKGROUND, BACKGROUND, BACKGROUND,
            ),
        )

        val result = SegmentationAnalyzer(config, trafficLightClassMapper).analyze(mask)

        assertFalse(result.hasTrafficLight)
    }

    @Test
    fun `traffic light requires traffic context`() {
        val config = AnalysisConfig(
            roadRatioSmoothWindow = 1,
            trafficLightDebounceFrames = 1,
            trafficLightMinPixelRatio = 0.10f,
            trafficLightMinRoadRatio = 0.50f,
        )
        val mask = SegmentationMask(
            width = 4,
            height = 4,
            classMap = intArrayOf(
                TRAFFIC_LIGHT, TRAFFIC_LIGHT, ROAD, ROAD,
                BACKGROUND, BACKGROUND, BACKGROUND, BACKGROUND,
                BACKGROUND, BACKGROUND, BACKGROUND, BACKGROUND,
                BACKGROUND, BACKGROUND, BACKGROUND, BACKGROUND,
            ),
        )

        val result = SegmentationAnalyzer(config, trafficLightClassMapper).analyze(mask)

        assertFalse(result.hasTrafficLight)
    }

    @Test
    fun `traffic light passes area and road context gate`() {
        val config = AnalysisConfig(
            roadRatioSmoothWindow = 1,
            trafficLightDebounceFrames = 1,
            trafficLightMinPixelRatio = 0.10f,
            trafficLightMinRoadRatio = 0.30f,
        )
        val mask = SegmentationMask(
            width = 4,
            height = 4,
            classMap = intArrayOf(
                TRAFFIC_LIGHT, TRAFFIC_LIGHT, ROAD, ROAD,
                ROAD, ROAD, ROAD, ROAD,
                ROAD, ROAD, BACKGROUND, BACKGROUND,
                BACKGROUND, BACKGROUND, BACKGROUND, BACKGROUND,
            ),
        )

        val result = SegmentationAnalyzer(config, trafficLightClassMapper).analyze(mask)

        assertEquals(true, result.hasTrafficLight)
    }

    private companion object {
        private const val ROAD = 0
        private const val OBSTACLE = 1
        private const val BACKGROUND = 2
        private const val TRAFFIC_LIGHT = 3
    }
}
