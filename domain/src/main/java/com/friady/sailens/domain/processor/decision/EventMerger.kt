package com.friady.sailens.domain.processor.decision

import com.friady.sailens.domain.model.common.DirectionZone
import com.friady.sailens.domain.model.common.EventCategory
import com.friady.sailens.domain.model.scene.SceneEvent
import java.util.UUID

/**
 * 事件合并器
 */
class EventMerger {

    fun merge(events: List<SceneEvent>): List<SceneEvent> {
        val result = mutableListOf<SceneEvent>()
        val processed = mutableSetOf<UUID>()

        for (event in events) {
            if (event.id in processed) continue

            when (event.category) {
                EventCategory.OBSTACLE -> {
                    val obstacleEvents = events.filter {
                        it.category == EventCategory.OBSTACLE && it.id !in processed
                    }
                    if (obstacleEvents.size > 1) {
                        result.add(mergeObstacleEvents(obstacleEvents))
                        obstacleEvents.forEach { processed.add(it.id) }
                    } else {
                        result.add(event)
                        processed.add(event.id)
                    }
                }

                else -> {
                    result.add(event)
                    processed.add(event.id)
                }
            }
        }

        return result
    }

    private fun mergeObstacleEvents(events: List<SceneEvent>): SceneEvent {
        val allZones = events.flatMap { it.relatedZones }.distinct().sortedBy { it.value }
        val maxPriority = events.maxOf { it.priority }
        val maxConfidence = events.maxOf { it.confidence }
        val maxSeverity = events.maxOf { it.severity }
        val firstEvent = events.first()

        val messageKey = when (allZones) {
            listOf(DirectionZone.LEFT) -> "event_obstacle_left"
            listOf(DirectionZone.CENTER) -> "event_obstacle_center"
            listOf(DirectionZone.RIGHT) -> "event_obstacle_right"
            listOf(DirectionZone.FRONT_LEFT) -> "event_obstacle_front_left"
            listOf(DirectionZone.FRONT_RIGHT) -> "event_obstacle_front_right"
            listOf(DirectionZone.LEFT, DirectionZone.CENTER) -> "event_obstacle_left_center"
            listOf(DirectionZone.CENTER, DirectionZone.RIGHT) -> "event_obstacle_center_right"
            listOf(DirectionZone.LEFT, DirectionZone.RIGHT) -> "event_obstacle_left_right"
            else -> "event_obstacle_multiple"
        }
        val cooldownKeys = events
            .flatMap { it.cooldownKeys.ifEmpty { setOf(it.dedupeKey) } }
            .toMutableSet()
            .apply {
                allZones.forEach { add("obstacle_${it.name}") }
                add(primaryObstacleCooldownKey(allZones))
            }
        val dedupeKey = primaryObstacleCooldownKey(allZones)

        return SceneEvent(
            id = UUID.randomUUID(),
            timestamp = firstEvent.timestamp,
            category = EventCategory.OBSTACLE,
            priority = maxPriority,
            messageKey = messageKey,
            messageParams = mapOf(
                "zones" to allZones.joinToString(",") { it.name.lowercase() },
                "count" to events.size.toString()
            ),
            expiresAt = firstEvent.expiresAt,
            dedupeKey = dedupeKey,
            cooldownKeys = cooldownKeys,
            confidence = maxConfidence,
            severity = maxSeverity,
            relatedZones = allZones
        )
    }

    private fun primaryObstacleCooldownKey(zones: List<DirectionZone>): String {
        return when {
            DirectionZone.CENTER in zones -> "obstacle_PRIMARY_CENTER"
            DirectionZone.FRONT_LEFT in zones || DirectionZone.FRONT_RIGHT in zones -> "obstacle_PRIMARY_FRONT"
            zones.size > 1 -> "obstacle_PRIMARY_SIDE"
            else -> "obstacle_${zones.first().name}"
        }
    }
}
