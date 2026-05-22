package com.friady.sailens.domain.processor.analysis

import com.friady.sailens.domain.config.AnalysisConfig
import com.friady.sailens.domain.model.common.BinaryMask
import com.friady.sailens.domain.model.common.BottomStats
import com.friady.sailens.domain.model.common.GroundType
import com.friady.sailens.domain.model.common.DirectionZone
import com.friady.sailens.domain.model.common.DistanceLevel
import com.friady.sailens.domain.model.common.NormalizedRect
import com.friady.sailens.domain.model.common.ObstacleCategory
import com.friady.sailens.domain.model.common.UrgencyLevel
import com.friady.sailens.domain.model.perception.ClassMapper
import com.friady.sailens.domain.model.perception.DetectedInstance
import com.friady.sailens.domain.model.perception.DetectedObstacle
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
    fun `on road area alone does not produce a warning`() {
        val result = analyzer.analyze(
            analysis = analysis(bottomCenterRoadRatio = 0.45f),
            obstacles = emptyList(),
        )

        assertTrue(result.isOnRoad)
        assertFalse(result.isDangerous)
    }

    @Test
    fun `vehicle on road produces a warning`() {
        analyzer.reset()

        val result = analyzer.analyze(
            analysis = analysis(bottomCenterRoadRatio = 0.45f),
            obstacles = listOf(vehicleObstacle()),
        )

        assertTrue(result.isOnRoad)
        assertTrue(result.hasVehicleOnRoad)
        assertTrue(result.isDangerous)
        assertTrue(result.dangerConfidence >= 0.8f)
    }

    @Test
    fun `raw vehicle instance on road produces a warning before tracker is stable`() {
        analyzer.reset()

        val result = analyzer.analyze(
            analysis = analysis(bottomCenterRoadRatio = 0.45f),
            obstacles = emptyList(),
            instanceDetections = listOf(vehicleInstance()),
        )

        assertTrue(result.isOnRoad)
        assertTrue(result.hasVehicleOnRoad)
        assertTrue(result.isDangerous)
    }

    @Test
    fun `vehicle bottom samples road just below bbox`() {
        analyzer.reset()

        val result = analyzer.analyze(
            analysis = analysis(
                bottomCenterRoadRatio = 0.45f,
                width = 4,
                height = 4,
                classMap = intArrayOf(
                    ROAD, ROAD, ROAD, ROAD,
                    ROAD, ROAD, ROAD, ROAD,
                    ROAD, OTHER, OTHER, ROAD,
                    ROAD, ROAD, ROAD, ROAD,
                )
            ),
            obstacles = emptyList(),
            instanceDetections = listOf(
                vehicleInstance(
                    boundingBox = NormalizedRect(
                        x = 0.25f,
                        y = 0.25f,
                        width = 0.5f,
                        height = 0.48f,
                    )
                )
            ),
        )

        assertTrue(result.hasVehicleOnRoad)
        assertTrue(result.isDangerous)
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

    private fun analysis(
        bottomCenterRoadRatio: Float,
        width: Int = 2,
        height: Int = 2,
        classMap: IntArray = IntArray(width * height) { ROAD },
    ): SegmentationAnalysis {
        val mask = BinaryMask(width = width, height = height)
        val segmentation = SegmentationMask(
            width = width,
            height = height,
            classMap = classMap,
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
                maxRunWidth = width,
                maxRunWidthRatio = 1f,
                maxRunRow = height - 1,
                maxRunStart = 0,
                maxRunEnd = width - 1,
                maxRunCenter = 0.5f,
            ),
            passablePixelCount = width * height,
            navigationPassableRatio = 1f,
            obstaclePixelCount = 0,
            dominantClassNames = listOf("road"),
            segmentation = segmentation,
            width = width,
            height = height,
        )
    }

    private fun vehicleObstacle(): DetectedObstacle {
        return DetectedObstacle(
            boundingBox = NormalizedRect(0.4f, 0.4f, 0.2f, 0.4f),
            category = ObstacleCategory.VEHICLE,
            zone = DirectionZone.CENTER,
            distance = DistanceLevel.NEAR,
            urgency = UrgencyLevel.CRITICAL,
            confidence = 0.9f,
            stableFrames = 2,
            areaRatio = 0.08f,
            timestamp = 1L,
        )
    }

    private fun vehicleInstance(
        boundingBox: NormalizedRect = NormalizedRect(0.4f, 0.4f, 0.2f, 0.4f),
    ): DetectedInstance {
        return DetectedInstance(
            classId = 2,
            className = "car",
            confidence = 0.82f,
            boundingBox = boundingBox,
            mask = null,
            category = ObstacleCategory.VEHICLE,
        )
    }

    private companion object {
        private const val ROAD = 0
        private const val OTHER = 1

        private val mapper = object : ClassMapper {
            override val datasetName: String = "test"
            override val classCount: Int = 2
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
