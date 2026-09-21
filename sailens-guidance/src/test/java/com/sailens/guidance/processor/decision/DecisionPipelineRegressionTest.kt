package com.sailens.guidance.processor.decision

import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.model.analysis.RoadSafetyState
import com.sailens.guidance.model.analysis.SceneElements
import com.sailens.guidance.model.analysis.SceneSnapshot
import com.sailens.guidance.model.analysis.WalkPathConnectivity
import com.sailens.guidance.model.common.DirectionBias
import com.sailens.guidance.model.common.DirectionZone
import com.sailens.guidance.model.common.DistanceLevel
import com.sailens.guidance.model.common.EventCategory
import com.sailens.core.geometry.NormalizedRect
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.common.Severity
import com.sailens.guidance.model.common.UrgencyLevel
import com.sailens.guidance.model.perception.DetectedObstacle
import org.junit.Assert.assertEquals
import org.junit.Test

class DecisionPipelineRegressionTest {
    private val generator = EventGenerator()
    private val resolver = EventConflictResolver()
    private val merger = EventMerger()
    private val cooldown = CooldownManager()

    @Test
    fun `road area warning does not block later vehicle road warning`() {
        val roadWarningPipelineGenerator = EventGenerator(
            AnalysisConfig(enableRoadWarningEvents = true)
        )
        val first = runPipeline(
            snapshot = snapshot(
                roadSafety = RoadSafetyState(
                    isOnRoad = true,
                    isDangerous = true,
                    roadRatio = 0.5f,
                    hasVehicleOnRoad = false,
                    hasTrafficLight = false,
                    dangerConfidence = 0.45f,
                )
            ),
            now = 10_000L,
            eventGenerator = roadWarningPipelineGenerator,
        )

        assertEquals(listOf("event_road_warning"), first.map { it.messageKey })

        val second = runPipeline(
            snapshot = snapshot(
                roadSafety = RoadSafetyState(
                    isOnRoad = true,
                    isDangerous = true,
                    roadRatio = 0.6f,
                    hasVehicleOnRoad = true,
                    hasTrafficLight = false,
                    dangerConfidence = 0.9f,
                )
            ),
            now = 11_000L,
            eventGenerator = roadWarningPipelineGenerator,
        )

        assertEquals(listOf("event_road_warning_vehicle"), second.map { it.messageKey })
    }

    @Test
    fun `blocked suppresses center obstacle before obstacle merge`() {
        val events = listOf(
            sceneEvent(EventCategory.BLOCKED, "event_blocked"),
            sceneEvent(EventCategory.OBSTACLE, "event_obstacle_center"),
        )

        val resolved = resolver.resolve(events)
        val merged = merger.merge(resolved)

        assertEquals(1, merged.size)
        assertEquals(EventCategory.BLOCKED, merged.single().category)
    }

    @Test
    fun `path complex keeps typed center obstacle as primary prompt`() {
        val events = runPipeline(
            snapshot = snapshot(
                roadSafety = RoadSafetyState(
                    isOnRoad = false,
                    isDangerous = false,
                    roadRatio = 0f,
                    hasVehicleOnRoad = false,
                    hasTrafficLight = false,
                    dangerConfidence = 0f,
                ),
                obstacles = listOf(
                    personObstacle(
                        NormalizedRect(
                            x = 0.43f,
                            y = 0.45f,
                            width = 0.14f,
                            height = 0.25f,
                        )
                    )
                ),
                connectivity = connectivity().copy(
                    isBlocked = true,
                    blockageConfidence = 0.68f,
                    blockageSeverity = Severity.MODERATE,
                    verticalReachRatio = 0.50f,
                    floodReachRatio = 0.12f,
                    widthRetentionP25 = 0.32f,
                ),
            ),
            now = 30_000L,
        )

        assertEquals(
            listOf("event_obstacle_center_person", "event_path_complex"),
            events.map { it.messageKey },
        )
    }

    @Test
    fun `forward vehicle prompt outranks intersection prompt`() {
        val events = runPipeline(
            snapshot = snapshot(
                roadSafety = RoadSafetyState(
                    isOnRoad = false,
                    isDangerous = false,
                    roadRatio = 0f,
                    hasVehicleOnRoad = false,
                    hasTrafficLight = false,
                    dangerConfidence = 0f,
                ),
                obstacles = listOf(
                    vehicleObstacle(
                        NormalizedRect(
                            x = 0.42f,
                            y = 0.44f,
                            width = 0.18f,
                            height = 0.20f,
                        )
                    )
                ),
                sceneElements = SceneElements(hasIntersection = true),
            ),
            now = 40_000L,
        )

        assertEquals(
            listOf("event_obstacle_center_vehicle", "event_intersection"),
            events.map { it.messageKey },
        )
    }

    @Test
    fun `forward vehicle prompt outranks traffic light prompt`() {
        val events = runPipeline(
            snapshot = snapshot(
                roadSafety = RoadSafetyState(
                    isOnRoad = false,
                    isDangerous = false,
                    roadRatio = 0f,
                    hasVehicleOnRoad = false,
                    hasTrafficLight = false,
                    dangerConfidence = 0f,
                ),
                obstacles = listOf(
                    vehicleObstacle(
                        NormalizedRect(
                            x = 0.42f,
                            y = 0.44f,
                            width = 0.18f,
                            height = 0.20f,
                        )
                    )
                ),
                sceneElements = SceneElements(hasTrafficLight = true),
            ),
            now = 45_000L,
        )

        assertEquals(
            listOf("event_obstacle_center_vehicle", "event_traffic_light"),
            events.map { it.messageKey },
        )
    }

    @Test
    fun `forward vehicle prompt is selected over same-zone person at equal urgency`() {
        val events = runPipeline(
            snapshot = snapshot(
                roadSafety = RoadSafetyState(
                    isOnRoad = false,
                    isDangerous = false,
                    roadRatio = 0f,
                    hasVehicleOnRoad = false,
                    hasTrafficLight = false,
                    dangerConfidence = 0f,
                ),
                obstacles = listOf(
                    personObstacle(
                        NormalizedRect(
                            x = 0.40f,
                            y = 0.48f,
                            width = 0.20f,
                            height = 0.25f,
                        )
                    ),
                    vehicleObstacle(
                        boundingBox = NormalizedRect(
                            x = 0.44f,
                            y = 0.48f,
                            width = 0.14f,
                            height = 0.20f,
                        ),
                        urgency = UrgencyLevel.HIGH,
                    )
                ),
            ),
            now = 50_000L,
        )

        assertEquals(listOf("event_obstacle_center_vehicle"), events.map { it.messageKey })
    }

    @Test
    fun `narrowing and direction advice are suppressed by default`() {
        val events = runPipeline(
            snapshot = SceneSnapshot(
                timestamp = 1L,
                obstacles = emptyList(),
                bottomCoverage = 1f,
                connectivity = connectivity().copy(
                    isNarrowing = true,
                    suggestedBias = DirectionBias.LEFT,
                    narrowingConfidence = 0.9f,
                    narrowingSeverity = Severity.MODERATE,
                ),
                sceneElements = SceneElements(),
                roadSafety = RoadSafetyState(
                    isOnRoad = false,
                    isDangerous = false,
                    roadRatio = 0f,
                    hasVehicleOnRoad = false,
                    hasTrafficLight = false,
                    dangerConfidence = 0f,
                ),
                groundTypeChange = null,
            ),
            now = 20_000L,
        )

        assertEquals(emptyList<String>(), events.map { it.messageKey })
    }

    private fun runPipeline(
        snapshot: SceneSnapshot,
        now: Long,
        eventGenerator: EventGenerator = generator,
    ) = eventGenerator.generate(snapshot, now)
        .let(resolver::resolve)
        .let(merger::merge)
        .let { cooldown.filter(it, now) }
        .also { events -> events.forEach { cooldown.recordEvent(it, now) } }
        .sortedByDescending { it.priority.value }

    private fun snapshot(
        roadSafety: RoadSafetyState,
        obstacles: List<DetectedObstacle> = emptyList(),
        connectivity: WalkPathConnectivity = connectivity(),
        sceneElements: SceneElements = SceneElements(),
    ): SceneSnapshot {
        return SceneSnapshot(
            timestamp = 1L,
            obstacles = obstacles,
            bottomCoverage = 1f,
            connectivity = connectivity,
            sceneElements = sceneElements,
            roadSafety = roadSafety,
            groundTypeChange = null,
        )
    }

    private fun connectivity() = WalkPathConnectivity(
        isBlocked = false,
        isNarrowing = false,
        suggestedBias = null,
        blockageConfidence = 0f,
        narrowingConfidence = 0f,
        blockageSeverity = Severity.NONE,
        narrowingSeverity = Severity.NONE,
        verticalReachRatio = 1f,
        validLayers = 3,
        totalLayers = 3,
        widthRetentionAvg = 1f,
        widthRetentionP25 = 1f,
        widthSlope = 0f,
        floodReachRatio = 1f,
        floodWidthRetentionP25 = 1f,
        floodVisitedRatio = 1f,
    )

    private fun sceneEvent(
        category: EventCategory,
        messageKey: String,
    ) = com.sailens.guidance.model.scene.SceneEvent(
        timestamp = 1L,
        category = category,
        priority = com.sailens.guidance.model.common.EventPriority.HIGH,
        messageKey = messageKey,
        expiresAt = 3L,
        dedupeKey = category.name,
        relatedZones = if (category == EventCategory.OBSTACLE) {
            listOf(com.sailens.guidance.model.common.DirectionZone.CENTER)
        } else {
            emptyList()
        },
    )

    private fun personObstacle(boundingBox: NormalizedRect) = DetectedObstacle(
        boundingBox = boundingBox,
        category = ObstacleCategory.PERSON,
        className = "person",
        zone = DirectionZone.CENTER,
        distance = DistanceLevel.MEDIUM,
        urgency = UrgencyLevel.HIGH,
        confidence = 0.9f,
        stableFrames = 3,
        areaRatio = boundingBox.area,
        timestamp = 1L,
    )

    private fun vehicleObstacle(
        boundingBox: NormalizedRect,
        urgency: UrgencyLevel = UrgencyLevel.MEDIUM,
    ) = DetectedObstacle(
        boundingBox = boundingBox,
        category = ObstacleCategory.VEHICLE,
        className = "car",
        zone = DirectionZone.CENTER,
        distance = DistanceLevel.MEDIUM,
        urgency = urgency,
        confidence = 0.9f,
        stableFrames = 3,
        areaRatio = boundingBox.area,
        timestamp = 1L,
    )
}
