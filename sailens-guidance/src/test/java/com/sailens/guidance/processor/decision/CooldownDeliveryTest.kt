package com.sailens.guidance.processor.decision

import com.sailens.core.geometry.NormalizedRect
import com.sailens.guidance.model.analysis.RoadSafetyState
import com.sailens.guidance.model.analysis.SceneElements
import com.sailens.guidance.model.analysis.SceneSnapshot
import com.sailens.guidance.model.analysis.WalkPathConnectivity
import com.sailens.guidance.model.common.DirectionZone
import com.sailens.guidance.model.common.DistanceLevel
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.common.Severity
import com.sailens.guidance.model.common.UrgencyLevel
import com.sailens.guidance.model.perception.DetectedObstacle
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.scene.SceneEventMessageKeys
import com.sailens.guidance.repository.DeviceSensorRepository
import com.sailens.guidance.usecase.decision.DecideEventsUseCase
import com.sailens.guidance.usecase.decision.RevokeUndeliveredEventUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cooldown records only what reached the user, and a merged prompt is not muted by the zone it
 * already announced when it also carries a new one.
 */
class CooldownDeliveryTest {

    @Test
    fun `a merged prompt passes when one of its zones is new, even if another is cooling`() {
        val cooldown = CooldownManager()
        // t0: a HIGH person front-left is announced.
        val frontLeft = obstacle(DirectionZone.FRONT_LEFT, setOf("obstacle_FRONT_LEFT", "obstacle_PRIMARY_FRONT"))
        cooldown.recordEvent(frontLeft, now = 10_000)

        // t0+1s: the same person plus a new HIGH person dead ahead, merged into one prompt.
        val center = obstacle(DirectionZone.CENTER, setOf("obstacle_CENTER", "obstacle_PRIMARY_CENTER"))
        val merged = EventMerger().merge(
            listOf(
                obstacle(DirectionZone.FRONT_LEFT, frontLeft.cooldownKeys),
                center,
            )
        ).single()

        // Before: the flattened key set required obstacle_PRIMARY_FRONT (5 s) to have expired, so
        // the new person ahead was muted for ~4 s. Now the centre group is fresh, so it passes.
        assertEquals(listOf(merged), cooldown.filter(listOf(merged), now = 11_000))
    }

    @Test
    fun `a merged prompt whose every zone is cooling is still suppressed`() {
        val cooldown = CooldownManager()
        val left = obstacle(DirectionZone.LEFT, setOf("obstacle_LEFT", "obstacle_PRIMARY_SIDE"))
        val right = obstacle(DirectionZone.RIGHT, setOf("obstacle_RIGHT", "obstacle_PRIMARY_SIDE"))
        cooldown.recordEvent(left, now = 10_000)
        cooldown.recordEvent(right, now = 10_000)

        val merged = EventMerger().merge(listOf(left.copy(id = java.util.UUID.randomUUID()), right.copy(id = java.util.UUID.randomUUID()))).single()

        assertTrue(cooldown.filter(listOf(merged), now = 11_000).isEmpty())
    }

    @Test
    fun `revoking an undelivered event lets it through again`() {
        val cooldown = CooldownManager()
        val event = obstacle(DirectionZone.CENTER, setOf("obstacle_CENTER", "obstacle_PRIMARY_CENTER"))
        cooldown.recordEvent(event, now = 10_000)
        assertTrue(cooldown.filter(listOf(event), now = 10_100).isEmpty())

        cooldown.revoke(event.id)

        assertEquals(listOf(event), cooldown.filter(listOf(event), now = 10_100))
    }

    @Test
    fun `revoking restores the previous record rather than clearing the key`() {
        val cooldown = CooldownManager()
        val earlier = obstacle(DirectionZone.CENTER, setOf("obstacle_CENTER", "obstacle_PRIMARY_CENTER"))
        val later = obstacle(DirectionZone.CENTER, setOf("obstacle_CENTER", "obstacle_PRIMARY_CENTER"))
        cooldown.recordEvent(earlier, now = 10_000)
        // Recorded by escalation, then never delivered.
        cooldown.recordEvent(later.copy(priority = EventPriority.CRITICAL), now = 11_000)
        cooldown.revoke(later.id)

        // The earlier (delivered) record still holds: a repeat at the same priority stays muted.
        assertTrue(cooldown.filter(listOf(obstacle(DirectionZone.CENTER, earlier.cooldownKeys)), now = 11_500).isEmpty())
    }

    @Test
    fun `revoking does not undo a newer record another event wrote to the same key`() {
        val cooldown = CooldownManager()
        val undelivered = obstacle(DirectionZone.CENTER, setOf("obstacle_CENTER", "obstacle_PRIMARY_CENTER"))
        val delivered = obstacle(DirectionZone.CENTER, setOf("obstacle_CENTER", "obstacle_PRIMARY_CENTER"))
            .copy(priority = EventPriority.CRITICAL)
        cooldown.recordEvent(undelivered, now = 10_000)
        cooldown.recordEvent(delivered, now = 10_050)

        cooldown.revoke(undelivered.id)

        val repeat = obstacle(DirectionZone.CENTER, delivered.cooldownKeys).copy(priority = EventPriority.CRITICAL)
        assertTrue(cooldown.filter(listOf(repeat), now = 10_100).isEmpty())
    }

    @Test
    fun `decide records only the event it hands out first`() {
        val cooldown = CooldownManager()
        var now = 10_000L
        val decide = DecideEventsUseCase(
            eventGenerator = EventGenerator(),
            conflictResolver = EventConflictResolver(),
            eventMerger = EventMerger(),
            cooldownManager = cooldown,
            deviceSensorRepository = StillSensors,
            clock = { now },
        )
        // A forward vehicle (CRITICAL, kept separate by the merger) and a person on the right.
        val snapshot = snapshot(
            listOf(
                trackedObstacle(ObstacleCategory.VEHICLE, NormalizedRect(0.40f, 0.45f, 0.2f, 0.4f)),
                trackedObstacle(ObstacleCategory.PERSON, NormalizedRect(0.82f, 0.45f, 0.15f, 0.4f)),
            )
        )

        val first = decide(snapshot)
        assertTrue("expected two separate prompts, got ${first.map { it.messageKey }}", first.size == 2)
        assertEquals(EventPriority.CRITICAL, first.first().priority)

        // 100 ms later: the vehicle is cooling, but the person on the right was never spoken, so
        // it must not be muted.
        now += 100
        val second = decide(snapshot)
        assertEquals(listOf(first[1].messageKey), second.map { it.messageKey })
    }

    @Test
    fun `revoked first event is offered again on the next frame`() {
        val cooldown = CooldownManager()
        var now = 10_000L
        val decide = DecideEventsUseCase(
            eventGenerator = EventGenerator(),
            conflictResolver = EventConflictResolver(),
            eventMerger = EventMerger(),
            cooldownManager = cooldown,
            deviceSensorRepository = StillSensors,
            clock = { now },
        )
        val snapshot = snapshot(
            listOf(trackedObstacle(ObstacleCategory.PERSON, NormalizedRect(0.40f, 0.45f, 0.2f, 0.4f)))
        )

        val first = decide(snapshot).single()
        RevokeUndeliveredEventUseCase(cooldown)(first)
        now += 100

        assertEquals(listOf(first.messageKey), decide(snapshot).map { it.messageKey })
    }

    @Test
    fun `every key the generator and merger emit is in the closed vocabulary`() {
        val keys = listOf(
            SceneEventMessageKeys.obstacle("center", SceneEventMessageKeys.SUFFIX_PERSON),
            SceneEventMessageKeys.obstacle("left_center", null),
            SceneEventMessageKeys.obstacle("multiple", SceneEventMessageKeys.SUFFIX_VEHICLE),
        )
        assertTrue(SceneEventMessageKeys.all.containsAll(keys))
        DirectionZone.entries.forEach { zone ->
            assertTrue(SceneEventMessageKeys.all.contains(SceneEventMessageKeys.obstacle(SceneEventMessageKeys.zonePart(zone), null)))
        }
        assertEquals(42, SceneEventMessageKeys.all.size)
    }

    private object StillSensors : DeviceSensorRepository {
        override val deviceRotation: StateFlow<Int> = MutableStateFlow(0)
        override val deviceRotationValue: Int = 0
        override val deviceRotationDegree: Int = 0
        override val isStationary: StateFlow<Boolean> = MutableStateFlow(false)
        override fun startObserving() = Unit
        override fun stopObserving() = Unit
    }

    private fun obstacle(zone: DirectionZone, cooldownKeys: Set<String>): SceneEvent = SceneEvent(
        timestamp = 10_000,
        category = EventCategory.OBSTACLE,
        priority = EventPriority.HIGH,
        messageKey = SceneEventMessageKeys.obstacle(SceneEventMessageKeys.zonePart(zone), SceneEventMessageKeys.SUFFIX_PERSON),
        expiresAt = 13_000,
        dedupeKey = "obstacle_${zone.name}",
        cooldownKeys = cooldownKeys,
        relatedZones = listOf(zone),
    )

    private fun trackedObstacle(category: ObstacleCategory, box: NormalizedRect) = DetectedObstacle(
        boundingBox = box,
        category = category,
        zone = DirectionZone.fromNormalizedX(box.centerX),
        distance = DistanceLevel.NEAR,
        urgency = UrgencyLevel.CRITICAL,
        confidence = 0.9f,
        stableFrames = 5,
        areaRatio = box.area,
        timestamp = 10_000,
    )

    private fun snapshot(obstacles: List<DetectedObstacle>) = SceneSnapshot(
        timestamp = 10_000,
        obstacles = obstacles,
        bottomCoverage = 0.5f,
        connectivity = WalkPathConnectivity(
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
            floodVisitedRatio = 0.5f,
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
    )
}
