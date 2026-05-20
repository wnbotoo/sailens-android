package com.friady.sailens.domain.processor.decision

import com.friady.sailens.domain.model.common.DirectionZone
import com.friady.sailens.domain.model.common.EventCategory
import com.friady.sailens.domain.model.scene.SceneEvent

/**
 * 事件冲突解决器
 */
class EventConflictResolver {

    private data class ConflictRule(
        val dominant: EventCategory,
        val suppressed: EventCategory,
        val condition: (SceneEvent, SceneEvent) -> Boolean = { _, _ -> true },
    )

    private val rules = listOf(
        // BLOCKED 抑制中心方向的 OBSTACLE
        ConflictRule(
            dominant = EventCategory.BLOCKED,
            suppressed = EventCategory.OBSTACLE,
            condition = { _, obstacle ->
                obstacle.relatedZones.contains(DirectionZone.CENTER)
            }
        ),

        // ROAD_WARNING 抑制进入道路的 GROUND_CHANGE
        ConflictRule(
            dominant = EventCategory.ROAD_WARNING,
            suppressed = EventCategory.GROUND_CHANGE,
            condition = { _, ground ->
                ground.messageKey == "event_ground_to_road"
            }
        ),

        // ROAD_EXIT 抑制离开道路的 GROUND_CHANGE
        ConflictRule(
            dominant = EventCategory.ROAD_EXIT,
            suppressed = EventCategory.GROUND_CHANGE,
            condition = { _, ground ->
                ground.messageParams["from"] == "road"
            }
        ),

        // BLOCKED 抑制 NARROWING
        ConflictRule(
            dominant = EventCategory.BLOCKED,
            suppressed = EventCategory.NARROWING
        ),

        // BLOCKED 抑制 DIRECTION_ADVICE
        ConflictRule(
            dominant = EventCategory.BLOCKED,
            suppressed = EventCategory.DIRECTION_ADVICE
        )
    )

    fun resolve(events: List<SceneEvent>): List<SceneEvent> {
        val result = events.toMutableList()

        for (rule in rules) {
            val dominant = result.find { it.category == rule.dominant } ?: continue
            result.removeAll { suppressed ->
                suppressed.category == rule.suppressed && rule.condition(dominant, suppressed)
            }
        }

        return result
    }
}