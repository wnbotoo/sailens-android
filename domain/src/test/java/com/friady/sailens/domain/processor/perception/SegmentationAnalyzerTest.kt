package com.friady.sailens.domain.processor.perception

import com.friady.sailens.domain.config.AnalysisConfig
import com.friady.sailens.domain.model.common.GroundType
import com.friady.sailens.domain.model.common.ObstacleCategory
import com.friady.sailens.domain.model.perception.ClassMapper
import com.friady.sailens.domain.model.perception.SegmentationMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SegmentationAnalyzerTest {
    private val classMapper = object : ClassMapper {
        override val datasetName: String = "test"
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

        override fun getClassName(classId: Int): String = when (classId) {
            ROAD -> "road"
            OBSTACLE -> "obstacle"
            else -> "background"
        }
    }

    @Test
    fun `navigation passable ratio focuses on lower image region`() {
        val analyzer = SegmentationAnalyzer(AnalysisConfig(), classMapper)
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
        val analyzer = SegmentationAnalyzer(AnalysisConfig(), classMapper)

        val roadMask = SegmentationMask(width = 1, height = 1, classMap = intArrayOf(ROAD))
        val backgroundMask = SegmentationMask(width = 1, height = 1, classMap = intArrayOf(BACKGROUND))

        analyzer.analyze(roadMask)
        analyzer.analyze(roadMask)
        val currentFrame = analyzer.analyze(backgroundMask)

        assertFalse(currentFrame.passableMask.get(0, 0))
        assertEquals(0, currentFrame.passablePixelCount)
    }

    private companion object {
        private const val ROAD = 0
        private const val OBSTACLE = 1
        private const val BACKGROUND = 2
    }
}
