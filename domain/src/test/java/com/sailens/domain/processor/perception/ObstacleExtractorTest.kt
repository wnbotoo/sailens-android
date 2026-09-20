package com.sailens.domain.processor.perception

import com.sailens.vision.taxonomy.TaxonomyId
import com.sailens.domain.config.AnalysisConfig
import com.sailens.domain.config.PerceptionConfig
import com.sailens.domain.model.common.DistanceLevel
import com.sailens.domain.model.common.GroundType
import com.sailens.core.geometry.NormalizedRect
import com.sailens.domain.model.common.ObstacleCategory
import com.sailens.domain.semantics.NavigationSemantics
import com.sailens.domain.model.perception.ObstacleDetection
import com.sailens.domain.model.perception.SegmentationMask
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

    @Test
    fun `far side detection outside perspective corridor is ignored`() {
        val perspectiveExtractor = ObstacleExtractor(
            PerceptionConfig(
                minObstacleConfidence = 0.1f,
                navigationCorridorCenterWidth = 0.50f,
                navigationCorridorFarWidth = 0.20f,
                navigationCorridorHorizonY = 0.35f,
                semanticObstacleMinBottomY = 0.45f,
                semanticObstacleMinCorridorOverlapRatio = 0.12f,
            ),
            mapper,
        )
        val detection = detection(NormalizedRect(x = 0.62f, y = 0.20f, width = 0.08f, height = 0.10f))

        val obstacles = perspectiveExtractor.extractFromDetections(listOf(detection)) { DistanceLevel.FAR }

        assertTrue(obstacles.isEmpty())
    }

    @Test
    fun `far center detection inside perspective corridor remains relevant`() {
        val perspectiveExtractor = ObstacleExtractor(
            PerceptionConfig(
                minObstacleConfidence = 0.1f,
                navigationCorridorCenterWidth = 0.50f,
                navigationCorridorFarWidth = 0.20f,
                navigationCorridorHorizonY = 0.35f,
                semanticObstacleMinBottomY = 0.45f,
                semanticObstacleMinCorridorOverlapRatio = 0.12f,
            ),
            mapper,
        )
        val detection = detection(NormalizedRect(x = 0.46f, y = 0.20f, width = 0.08f, height = 0.10f))

        val obstacles = perspectiveExtractor.extractFromDetections(listOf(detection)) { DistanceLevel.FAR }

        assertEquals(1, obstacles.size)
        assertEquals(ObstacleCategory.PERSON, obstacles.single().category)
    }

    private fun detection(box: NormalizedRect): ObstacleDetection {
        return ObstacleDetection(
            classId = PERSON,
            className = "person",
            confidence = 0.9f,
            boundingBox = box,
            category = ObstacleCategory.PERSON,
        )
    }

    private companion object {
        private const val ROAD = 0
        private const val BUILDING = 1
        private const val PERSON = 2

        private val mapper = object : NavigationSemantics {
            override val taxonomyId: TaxonomyId = TaxonomyId("test")
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

        }
    }
}
