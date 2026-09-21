package com.sailens.guidance.processor.decision

import com.sailens.guidance.model.common.DirectionZone
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.scene.SceneEvent

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

        // BLOCKED 抑制 NARROWING
        ConflictRule(
            dominant = EventCategory.BLOCKED,
            suppressed = EventCategory.NARROWING
        ),

        // 复杂路况保留具体障碍物提示，但抑制更泛的收窄提示
        ConflictRule(
            dominant = EventCategory.PATH_COMPLEX,
            suppressed = EventCategory.NARROWING
        ),
    )

    fun resolve(events: List<SceneEvent>): List<SceneEvent> {
        // 输入质量问题优先于一切：镜头被挡或环境过暗时，其余事件全部建立在一张不可信的画面上，
        // 播出去比不播更危险——用户会以为"没提示 = 没危险"。这里直接整组丢弃，而不是走成对规则，
        // 因为要抑制的是**所有**类别。
        events.firstOrNull { it.category == EventCategory.SENSOR_QUALITY }?.let { sensorEvent ->
            return listOf(sensorEvent)
        }

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
