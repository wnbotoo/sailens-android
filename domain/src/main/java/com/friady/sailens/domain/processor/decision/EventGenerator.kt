package com.friady.sailens.domain.processor.decision

import com.friady.sailens.domain.model.analysis.GroundTypeChange
import com.friady.sailens.domain.model.analysis.RoadSafetyState
import com.friady.sailens.domain.model.analysis.SceneSnapshot
import com.friady.sailens.domain.model.common.DirectionBias
import com.friady.sailens.domain.model.common.DirectionZone
import com.friady.sailens.domain.model.common.EventCategory
import com.friady.sailens.domain.model.common.EventPriority
import com.friady.sailens.domain.model.common.GroundType
import com.friady.sailens.domain.model.common.Severity
import com.friady.sailens.domain.model.common.UrgencyLevel
import com.friady.sailens.domain.model.scene.SceneEvent
import com.friady.sailens.domain.model.perception.DetectedObstacle

/**
 * 事件生成器
 */
class EventGenerator {
    private var wasOnRoad = false

    fun generate(snapshot: SceneSnapshot, now: Long): List<SceneEvent> {
        val events = mutableListOf<SceneEvent>()

        // 1. 阻塞事件
        if (snapshot.connectivity.isBlocked) {
            events.add(createBlockedEvent(snapshot, now))
        }

        // 2. 收窄事件
        if (snapshot.connectivity.isNarrowing && !snapshot.connectivity.isBlocked) {
            events.add(createNarrowingEvent(snapshot, now))
        }

        // 3. 方向建议
        snapshot.connectivity.suggestedBias?.let {
            events.add(createDirectionAdviceEvent(it, now))
        }

        // 4.  障碍物事件
        val significantObstacles = snapshot.obstacles.filter {
            it.urgency >= UrgencyLevel.MEDIUM && it.isStable()
        }
        if (significantObstacles.isNotEmpty()) {
            events.addAll(createObstacleEvents(significantObstacles, now))
        }

        // 5. 路口事件
        if (snapshot.sceneElements.hasIntersection) {
            events.add(createIntersectionEvent(now))
        }

        // 6.  道路安全事件
        if (snapshot.roadSafety.isDangerous) {
            events.add(createRoadWarningEvent(snapshot.roadSafety, now))
        }

        if (wasOnRoad && !snapshot.roadSafety.isOnRoad) {
            events.add(createRoadExitEvent(now))
        }
        wasOnRoad = snapshot.roadSafety.isOnRoad

        // 7. 地面变化事件
        snapshot.groundTypeChange?.let {
            events.add(createGroundChangeEvent(it, now))
        }

        return events
    }

    private fun createBlockedEvent(snapshot: SceneSnapshot, now: Long): SceneEvent {
        val priority = when (snapshot.connectivity.blockageSeverity) {
            Severity.SEVERE -> EventPriority.CRITICAL
            Severity.MODERATE -> EventPriority.HIGH
            else -> EventPriority.MEDIUM
        }

        return SceneEvent(
            timestamp = now,
            category = EventCategory.BLOCKED,
            priority = priority,
            messageKey = "event_blocked",
            expiresAt = now + 5000,
            dedupeKey = "blocked",
            confidence = snapshot.connectivity.blockageConfidence,
            severity = snapshot.connectivity.blockageSeverity
        )
    }

    private fun createNarrowingEvent(snapshot: SceneSnapshot, now: Long): SceneEvent {
        return SceneEvent(
            timestamp = now,
            category = EventCategory.NARROWING,
            priority = EventPriority.MEDIUM,
            messageKey = "event_narrowing",
            expiresAt = now + 4000,
            dedupeKey = "narrowing",
            confidence = snapshot.connectivity.narrowingConfidence,
            severity = snapshot.connectivity.narrowingSeverity
        )
    }

    private fun createDirectionAdviceEvent(bias: DirectionBias, now: Long): SceneEvent {
        val messageKey = when (bias) {
            DirectionBias.LEFT -> "event_suggest_left"
            DirectionBias.RIGHT -> "event_suggest_right"
        }

        return SceneEvent(
            timestamp = now,
            category = EventCategory.DIRECTION_ADVICE,
            priority = EventPriority.MEDIUM,
            messageKey = messageKey,
            expiresAt = now + 5000,
            dedupeKey = "direction_$bias"
        )
    }

    private fun createObstacleEvents(
        obstacles: List<DetectedObstacle>,
        now: Long,
    ): List<SceneEvent> {
        val byZone = obstacles.groupBy { it.zone }

        return byZone.map { (zone, zoneObstacles) ->
            val maxUrgency = zoneObstacles.maxOf { it.urgency }
            val priority = when (maxUrgency) {
                UrgencyLevel.CRITICAL -> EventPriority.CRITICAL
                UrgencyLevel.HIGH -> EventPriority.HIGH
                else -> EventPriority.MEDIUM
            }

            SceneEvent(
                timestamp = now,
                category = EventCategory.OBSTACLE,
                priority = priority,
                messageKey = "event_obstacle_${zone.name.lowercase()}",
                messageParams = mapOf("count" to zoneObstacles.size.toString()),
                expiresAt = now + 3000,
                dedupeKey = "obstacle_${zone.name}",
                cooldownKeys = obstacleCooldownKeys(listOf(zone)),
                relatedZones = listOf(zone)
            )
        }
    }

    private fun createIntersectionEvent(now: Long): SceneEvent {
        return SceneEvent(
            timestamp = now,
            category = EventCategory.INTERSECTION,
            priority = EventPriority.MEDIUM,
            messageKey = "event_intersection",
            expiresAt = now + 5000,
            dedupeKey = "intersection"
        )
    }

    private fun createRoadWarningEvent(roadSafety: RoadSafetyState, now: Long): SceneEvent {
        val messageKey = if (roadSafety.hasVehicleOnRoad) {
            "event_road_warning_vehicle"
        } else {
            "event_road_warning"
        }

        return SceneEvent(
            timestamp = now,
            category = EventCategory.ROAD_WARNING,
            priority = EventPriority.HIGH,
            messageKey = messageKey,
            expiresAt = now + 5000,
            dedupeKey = if (roadSafety.hasVehicleOnRoad) {
                "road_warning_vehicle"
            } else {
                "road_warning_area"
            },
            confidence = roadSafety.dangerConfidence
        )
    }

    private fun createRoadExitEvent(now: Long): SceneEvent {
        return SceneEvent(
            timestamp = now,
            category = EventCategory.ROAD_EXIT,
            priority = EventPriority.LOW,
            messageKey = "event_road_exit",
            expiresAt = now + 3000,
            dedupeKey = "road_exit"
        )
    }

    private fun createGroundChangeEvent(change: GroundTypeChange, now: Long): SceneEvent {
        val messageKey = when (change.to) {
            GroundType.ROAD -> "event_ground_to_road"
            GroundType.TERRAIN -> "event_ground_to_terrain"
            GroundType.SIDEWALK -> "event_ground_to_sidewalk"
            GroundType.INDOOR -> "event_ground_to_indoor"
            else -> "event_ground_change"
        }

        return SceneEvent(
            timestamp = now,
            category = EventCategory.GROUND_CHANGE,
            priority = EventPriority.MEDIUM,
            messageKey = messageKey,
            messageParams = mapOf(
                "from" to change.from.name.lowercase(),
                "to" to change.to.name.lowercase()
            ),
            expiresAt = now + 4000,
            dedupeKey = "ground_change_${change.to.name}"
        )
    }

    fun reset() {
        wasOnRoad = false
    }

    private fun obstacleCooldownKeys(zones: List<DirectionZone>): Set<String> {
        val keys = zones.mapTo(mutableSetOf()) { "obstacle_${it.name}" }
        if (zones.any { it == DirectionZone.CENTER }) {
            keys.add("obstacle_PRIMARY_CENTER")
        }
        if (zones.any {
                it == DirectionZone.FRONT_LEFT ||
                    it == DirectionZone.FRONT_RIGHT
            }
        ) {
            keys.add("obstacle_PRIMARY_FRONT")
        }
        return keys
    }
}
