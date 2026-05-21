package com.friady.sailens.domain.processor.decision

import com.friady.sailens.domain.model.common.EventCategory
import com.friady.sailens.domain.model.scene.SceneEvent

/**
 * 冷却管理器
 */
class CooldownManager() {
    val cooldowns: Map<EventCategory, Long> = mapOf(
        EventCategory.BLOCKED to 5000,
        EventCategory.NARROWING to 4000,
        EventCategory.OBSTACLE to 2000,
        EventCategory.DIRECTION_ADVICE to 6000,
        EventCategory.INTERSECTION to 5000,
        EventCategory.ROAD_WARNING to 12000,
        EventCategory.ROAD_EXIT to 5000,
        EventCategory.GROUND_CHANGE to 8000
    )

    private val lastEventTimes = mutableMapOf<String, Long>()

    fun filter(events: List<SceneEvent>, now: Long): List<SceneEvent> {
        return events.filter { event ->
            val cooldown = cooldowns[event.category] ?: 3000
            event.cooldownKeys().all { key ->
                val lastTime = lastEventTimes[key]
                lastTime == null || now - lastTime >= cooldown
            }
        }
    }

    fun recordEvent(event: SceneEvent, now: Long) {
        event.cooldownKeys().forEach { key ->
            lastEventTimes[key] = now
        }
    }

    fun reset() {
        lastEventTimes.clear()
    }

    private fun SceneEvent.cooldownKeys(): Set<String> = cooldownKeys.ifEmpty {
        setOf(dedupeKey.ifBlank { category.name })
    }
}
