package com.friady.sailens.domain.processor.perception

import com.friady.sailens.domain.config.AnalysisConfig
import com.friady.sailens.domain.config.PerceptionConfig
import com.friady.sailens.domain.model.common.DistanceLevel
import com.friady.sailens.domain.model.common.GroundType
import com.friady.sailens.domain.model.common.ObstacleCategory
import com.friady.sailens.domain.model.perception.ClassMapper
import com.friady.sailens.domain.model.perception.SegmentationMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObstacleExtractorTest {
    private val analyzer = SegmentationAnalyzer(AnalysisConfig(), mapper)
    private val extractor = ObstacleExtractor(PerceptionConfig(minObstacleAreaRatio = 0.01f), mapper)

    @Test
    fun `semantic background structures are not announced as obstacles`() {
        val classMap = IntArray(100) { index ->
            val x = index % 10
            if (x in 3..6) ROAD else BUILDING
        }
        val analysis = analyzer.analyze(SegmentationMask(width = 10, height = 10, classMap = classMap))

        val obstacles = extractor.extractFromSemantic(analysis) { DistanceLevel.NEAR }

        assertTrue(obstacles.isEmpty())
    }

    @Test
    fun `center near semantic person remains navigationally relevant`() {
        val classMap = IntArray(100) { ROAD }
        for (y in 5..9) {
            for (x in 4..5) {
                classMap[y * 10 + x] = PERSON
            }
        }
        val analysis = analyzer.analyze(SegmentationMask(width = 10, height = 10, classMap = classMap))

        val obstacles = extractor.extractFromSemantic(analysis) { DistanceLevel.NEAR }

        assertEquals(1, obstacles.size)
        assertEquals(ObstacleCategory.PERSON, obstacles.single().category)
    }

    private companion object {
        private const val ROAD = 0
        private const val BUILDING = 1
        private const val PERSON = 2

        private val mapper = object : ClassMapper {
            override val datasetName: String = "test"
            override val classCount: Int = 3

            override fun isPassable(classId: Int): Boolean = classId == ROAD
            override fun isObstacle(classId: Int): Boolean = classId == BUILDING || classId == PERSON
            override fun isRoad(classId: Int): Boolean = classId == ROAD
            override fun isTrafficLight(classId: Int): Boolean = false
            override fun toGroundType(classId: Int): GroundType = if (classId == ROAD) {
                GroundType.ROAD
            } else {
                GroundType.UNKNOWN
            }

            override fun toObstacleCategory(classId: Int): ObstacleCategory = if (classId == PERSON) {
                ObstacleCategory.PERSON
            } else {
                ObstacleCategory.UNKNOWN
            }

            override fun getClassName(classId: Int): String = when (classId) {
                ROAD -> "road"
                BUILDING -> "building"
                PERSON -> "person"
                else -> "unknown"
            }
        }
    }
}
