package com.friady.sailens.domain.processor.decision

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

        val messageKey = when (allZones.size) {
            1 -> "event_obstacle_${allZones[0].name.lowercase()}"
            2 -> "event_obstacle_${allZones[0].name.lowercase()}_${allZones[1].name.lowercase()}"
            else -> "event_obstacle_multiple"
        }

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
            dedupeKey = "obstacle_${allZones.joinToString("_") { it.name }}",
            confidence = maxConfidence,
            severity = maxSeverity,
            relatedZones = allZones
        )
    }
}