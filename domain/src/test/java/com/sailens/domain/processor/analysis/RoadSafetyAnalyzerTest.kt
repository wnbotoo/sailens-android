package com.sailens.domain.processor.analysis

import com.sailens.vision.taxonomy.TaxonomyId
import com.sailens.domain.config.AnalysisConfig
import com.sailens.core.mask.BinaryMask
import com.sailens.core.mask.BottomStats
import com.sailens.domain.model.common.GroundType
import com.sailens.domain.model.common.DirectionZone
import com.sailens.domain.model.common.DistanceLevel
import com.sailens.core.geometry.NormalizedRect
import com.sailens.domain.model.common.ObstacleCategory
import com.sailens.domain.model.common.UrgencyLevel
import com.sailens.domain.model.analysis.VehicleOnRoadReason
import com.sailens.domain.model.analysis.VehicleOnRoadSource
import com.sailens.domain.semantics.NavigationSemantics
import com.sailens.domain.model.perception.ObstacleDetection
import com.sailens.domain.model.perception.DetectedObstacle
import com.sailens.domain.model.perception.SegmentationAnalysis
import com.sailens.domain.model.perception.SegmentationMask
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadSafetyAnalyzerTest {
    private val analyzer = RoadSafetyAnalyzer(
        config = AnalysisConfig(onRoadDebounceFrames = 1),
        navigationSemantics = mapper,
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
        assertEquals(VehicleOnRoadReason.NEAR_BOTTOM, result.vehicleOnRoadReason)
        assertEquals(0.8f, result.vehicleOnRoadBottomY, 0.0001f)
    }

    @Test
    fun `tracked vehicle on road requires stable track before warning`() {
        analyzer.reset()

        val result = analyzer.analyze(
            analysis = analysis(bottomCenterRoadRatio = 0.45f),
            obstacles = listOf(vehicleObstacle(stableFrames = 2)),
        )

        assertTrue(result.isOnRoad)
        assertFalse(result.hasVehicleOnRoad)
        assertFalse(result.isDangerous)
    }

    @Test
    fun `tracked side vehicle must be close before road warning`() {
        analyzer.reset()

        val result = analyzer.analyze(
            analysis = analysis(bottomCenterRoadRatio = 0.45f),
            obstacles = listOf(
                vehicleObstacle(
                    boundingBox = NormalizedRect(0.05f, 0.35f, 0.18f, 0.20f),
                )
            ),
        )

        assertTrue(result.isOnRoad)
        assertFalse(result.hasVehicleOnRoad)
        assertFalse(result.isDangerous)
    }

    @Test
    fun `center road vehicle may warn before it is very close`() {
        analyzer.reset()

        val result = analyzer.analyze(
            analysis = analysis(bottomCenterRoadRatio = 0.45f),
            obstacles = listOf(
                vehicleObstacle(
                    boundingBox = NormalizedRect(0.42f, 0.25f, 0.16f, 0.25f),
                )
            ),
        )

        assertTrue(result.isOnRoad)
        assertTrue(result.hasVehicleOnRoad)
        assertTrue(result.isDangerous)
        assertEquals(VehicleOnRoadReason.CENTER_BAND, result.vehicleOnRoadReason)
        assertEquals(1.0f, result.vehicleOnRoadCenterBandOverlap, 0.0001f)
    }

    @Test
    fun `raw vehicle detection on road requires three consecutive evidence frames before warning`() {
        analyzer.reset()

        val first = analyzer.analyze(
            analysis = analysis(bottomCenterRoadRatio = 0.45f),
            obstacles = emptyList(),
            obstacleDetections = listOf(vehicleDetection()),
        )
        val second = analyzer.analyze(
            analysis = analysis(bottomCenterRoadRatio = 0.45f),
            obstacles = emptyList(),
            obstacleDetections = listOf(vehicleDetection()),
        )
        val third = analyzer.analyze(
            analysis = analysis(bottomCenterRoadRatio = 0.45f),
            obstacles = emptyList(),
            obstacleDetections = listOf(vehicleDetection()),
        )

        assertTrue(first.isOnRoad)
        assertFalse(first.hasVehicleOnRoad)
        assertFalse(first.isDangerous)
        assertFalse(second.hasVehicleOnRoad)
        assertFalse(second.isDangerous)
        assertTrue(third.hasVehicleOnRoad)
        assertTrue(third.isDangerous)
        assertTrue(third.vehicleOnRoadSource == VehicleOnRoadSource.RAW)
        assertEquals(VehicleOnRoadReason.NEAR_BOTTOM, third.vehicleOnRoadReason)
        assertEquals(0.08f, third.vehicleOnRoadAreaRatio, 0.0001f)
    }

    @Test
    fun `far small raw vehicle on road does not produce a warning`() {
        analyzer.reset()
        val detection = vehicleDetection(
            boundingBox = NormalizedRect(0.45f, 0.12f, 0.10f, 0.18f),
        )

        repeat(3) {
            val result = analyzer.analyze(
                analysis = analysis(bottomCenterRoadRatio = 0.45f),
                obstacles = emptyList(),
                obstacleDetections = listOf(detection),
            )
            assertFalse(result.hasVehicleOnRoad)
            assertFalse(result.isDangerous)
        }
    }

    @Test
    fun `vehicle bottom samples road just below bbox`() {
        analyzer.reset()

        val frameAnalysis = analysis(
            bottomCenterRoadRatio = 0.45f,
            width = 4,
            height = 4,
            classMap = intArrayOf(
                ROAD, ROAD, ROAD, ROAD,
                ROAD, ROAD, ROAD, ROAD,
                ROAD, OTHER, OTHER, ROAD,
                ROAD, ROAD, ROAD, ROAD,
            )
        )
        val detections = listOf(
            vehicleDetection(
                boundingBox = NormalizedRect(
                    x = 0.25f,
                    y = 0.25f,
                    width = 0.5f,
                    height = 0.48f,
                )
            )
        )

        analyzer.analyze(
            analysis = frameAnalysis,
            obstacles = emptyList(),
            obstacleDetections = detections,
        )
        analyzer.analyze(
            analysis = frameAnalysis,
            obstacles = emptyList(),
            obstacleDetections = detections,
        )
        val result = analyzer.analyze(
            analysis = frameAnalysis,
            obstacles = emptyList(),
            obstacleDetections = detections,
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

    private fun vehicleObstacle(
        boundingBox: NormalizedRect = NormalizedRect(0.4f, 0.4f, 0.2f, 0.4f),
        stableFrames: Int = 3,
    ): DetectedObstacle {
        return DetectedObstacle(
            boundingBox = boundingBox,
            category = ObstacleCategory.VEHICLE,
            zone = DirectionZone.CENTER,
            distance = DistanceLevel.NEAR,
            urgency = UrgencyLevel.CRITICAL,
            confidence = 0.9f,
            stableFrames = stableFrames,
            areaRatio = 0.08f,
            timestamp = 1L,
        )
    }

    private fun vehicleDetection(
        boundingBox: NormalizedRect = NormalizedRect(0.4f, 0.4f, 0.2f, 0.4f),
    ): ObstacleDetection {
        return ObstacleDetection(
            classId = 2,
            className = "car",
            confidence = 0.82f,
            boundingBox = boundingBox,
            category = ObstacleCategory.VEHICLE,
        )
    }

    private companion object {
        private const val ROAD = 0
        private const val OTHER = 1

        private val mapper = object : NavigationSemantics {
            override val taxonomyId: TaxonomyId = TaxonomyId("test")
            override val classCount: Int = 2
            override fun isPassable(classId: Int): Boolean = classId == ROAD
            override fun isObstacle(classId: Int): Boolean = false
            override fun isRoad(classId: Int): Boolean = classId == ROAD
            override fun isTrafficLight(classId: Int): Boolean = false
            override fun toGroundType(classId: Int): GroundType = GroundType.ROAD
            override fun toObstacleCategory(classId: Int): ObstacleCategory = ObstacleCategory.UNKNOWN
        }
    }
}
