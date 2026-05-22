package com.friady.sailens.domain.processor.decision

import com.friady.sailens.domain.model.analysis.RoadSafetyState
import com.friady.sailens.domain.model.analysis.SceneElements
import com.friady.sailens.domain.model.analysis.SceneSnapshot
import com.friady.sailens.domain.model.analysis.WalkPathConnectivity
import com.friady.sailens.domain.model.common.DirectionBias
import com.friady.sailens.domain.model.common.EventCategory
import com.friady.sailens.domain.model.common.Severity
import org.junit.Assert.assertEquals
import org.junit.Test

class DecisionPipelineRegressionTest {
    private val generator = EventGenerator()
    private val resolver = EventConflictResolver()
    private val merger = EventMerger()
    private val cooldown = CooldownManager()

    @Test
    fun `road area warning does not block later vehicle road warning`() {
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
    ) = generator.generate(snapshot, now)
        .let(resolver::resolve)
        .let(merger::merge)
        .let { cooldown.filter(it, now) }
        .also { events -> events.forEach { cooldown.recordEvent(it, now) } }
        .sortedByDescending { it.priority.value }

    private fun snapshot(roadSafety: RoadSafetyState): SceneSnapshot {
        return SceneSnapshot(
            timestamp = 1L,
            obstacles = emptyList(),
            bottomCoverage = 1f,
            connectivity = connectivity(),
            sceneElements = SceneElements(),
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
    ) = com.friady.sailens.domain.model.scene.SceneEvent(
        timestamp = 1L,
        category = category,
        priority = com.friady.sailens.domain.model.common.EventPriority.HIGH,
        messageKey = messageKey,
        expiresAt = 3L,
        dedupeKey = category.name,
        relatedZones = if (category == EventCategory.OBSTACLE) {
            listOf(com.friady.sailens.domain.model.common.DirectionZone.CENTER)
        } else {
            emptyList()
        },
    )
}
