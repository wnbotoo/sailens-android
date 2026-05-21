package com.friady.sailens.domain.processor.analysis

import com.friady.sailens.domain.config.AnalysisConfig
import com.friady.sailens.domain.model.common.BinaryMask
import com.friady.sailens.domain.model.common.BottomStats
import com.friady.sailens.domain.model.common.GroundType
import com.friady.sailens.domain.model.common.ObstacleCategory
import com.friady.sailens.domain.model.perception.ClassMapper
import com.friady.sailens.domain.model.perception.SegmentationAnalysis
import com.friady.sailens.domain.model.perception.SegmentationMask
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadSafetyAnalyzerTest {
    private val analyzer = RoadSafetyAnalyzer(
        config = AnalysisConfig(onRoadDebounceFrames = 1),
        classMapper = mapper,
    )

    @Test
    fun `on road area produces a warning even without vehicle`() {
        val result = analyzer.analyze(
            analysis = analysis(bottomCenterRoadRatio = 0.45f),
            obstacles = emptyList(),
        )

        assertTrue(result.isOnRoad)
        assertTrue(result.isDangerous)
        assertTrue(result.dangerConfidence >= 0.4f)
    }

    @Test
    fun `off road area does not produce road warning`() {
        analyzer.reset()

        val result = analyzer.analyze(
            analysis = analysis(bottomCenterRoadRatio = 0.10f),
            obstacles = emptyList(),
        )

        assertFalse(result.isOnRoad)
        assertFalse(result.isDangerous)
    }

    private fun analysis(bottomCenterRoadRatio: Float): SegmentationAnalysis {
        val mask = BinaryMask(width = 2, height = 2)
        val segmentation = SegmentationMask(
            width = 2,
            height = 2,
            classMap = intArrayOf(ROAD, ROAD, ROAD, ROAD),
        )
        return SegmentationAnalysis(
            passableMask = mask,
            obstacleMask = mask,
            roadRatio = 0.5f,
            hasTrafficLight = false,
            bottomCenterGroundDistribution = mapOf(GroundType.ROAD to bottomCenterRoadRatio),
            bottomCenterRoadRatio = bottomCenterRoadRatio,
            bottomStats = BottomStats(
                coverage = 1f,
                maxRunWidth = 2,
                maxRunWidthRatio = 1f,
                maxRunRow = 1,
                maxRunStart = 0,
                maxRunEnd = 1,
                maxRunCenter = 0.5f,
            ),
            passablePixelCount = 4,
            navigationPassableRatio = 1f,
            obstaclePixelCount = 0,
            dominantClassNames = listOf("road"),
            segmentation = segmentation,
            width = 2,
            height = 2,
        )
    }

    private companion object {
        private const val ROAD = 0

        private val mapper = object : ClassMapper {
            override val datasetName: String = "test"
            override val classCount: Int = 1
            override fun isPassable(classId: Int): Boolean = classId == ROAD
            override fun isObstacle(classId: Int): Boolean = false
            override fun isRoad(classId: Int): Boolean = classId == ROAD
            override fun isTrafficLight(classId: Int): Boolean = false
            override fun toGroundType(classId: Int): GroundType = GroundType.ROAD
            override fun toObstacleCategory(classId: Int): ObstacleCategory = ObstacleCategory.UNKNOWN
            override fun getClassName(classId: Int): String = "road"
        }
    }
}
