package com.sailens.guidance.processor.decision

import com.sailens.guidance.model.common.DirectionBias
import com.sailens.guidance.model.common.DirectionZone
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.scene.SceneEvent
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
                        result.addAll(mergeObstacleEventGroup(obstacleEvents))
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

    private fun mergeObstacleEventGroup(events: List<SceneEvent>): List<SceneEvent> {
        if (shouldKeepVehicleEventsSeparate(events)) {
            return events
        }
        return listOf(mergeObstacleEvents(events))
    }

    private fun shouldKeepVehicleEventsSeparate(events: List<SceneEvent>): Boolean {
        val suffixes = events
            .map { obstacleCategorySuffix(it.messageKey) ?: "unknown" }
            .distinct()
        return "vehicle" in suffixes && suffixes.size > 1
    }

    private fun mergeObstacleEvents(events: List<SceneEvent>): SceneEvent {
        val allZones = events.flatMap { it.relatedZones }.distinct().sortedBy { it.value }
        val maxPriority = events.maxOf { it.priority }
        val maxConfidence = events.maxOf { it.confidence }
        val maxSeverity = events.maxOf { it.severity }
        val firstEvent = events.first()
        // 合并后取最近的距离：一旦任一方位上有近处障碍，整条提示都该带紧迫前缀。
        val nearestDistance = events.mapNotNull { it.distance }.minOrNull()
        // 方向后缀必须按**合并后**的方位集合重新校验。单条事件各自合规不代表合起来合规：
        // "前方有人（靠左）" 和 "左侧有人（无后缀）" 合并后是"左侧和前方有人"，
        // 此时再说"靠左"就是把用户往左边那个人身上引。
        val directionHint = events.mapNotNull { it.directionHint }
            .distinct()
            .singleOrNull()
            ?.takeUnless { hint -> allZones.any { it in conflictingZones(hint) } }

        val messageKey = mergedObstacleMessageKey(
            zones = allZones,
            events = events,
        )
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
            relatedZones = allZones,
            distance = nearestDistance,
            directionHint = directionHint,
        )
    }

    private fun mergedObstacleMessageKey(
        zones: List<DirectionZone>,
        events: List<SceneEvent>,
    ): String {
        val zoneKey = when (zones) {
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

        val suffix = sharedObstacleCategorySuffix(events) ?: return zoneKey
        return "${zoneKey}_$suffix"
    }

    /**
     * 只有全部事件同属一个类别时才保留类别后缀，否则退回只带方位的泛化文案。
     *
     * 这里必须保留 null（无后缀 = 静态/未知障碍）参与比较，不能用 mapNotNull 过滤掉：
     * 把"行人 + 静态障碍"过滤成只剩 person，会播成"左侧和前方有行人"，等于凭空把一个
     * 静止物体说成了人。
     */
    private fun sharedObstacleCategorySuffix(events: List<SceneEvent>): String? {
        val suffixes = events
            .map { obstacleCategorySuffix(it.messageKey) }
            .distinct()
        return suffixes.singleOrNull()
    }

    /** 与 EventGenerator.obstacleCategorySuffix 对应的反向解析；null 表示无类别后缀。 */
    private fun obstacleCategorySuffix(messageKey: String): String? {
        return when {
            messageKey.endsWith("_person") -> "person"
            messageKey.endsWith("_vehicle") -> "vehicle"
            else -> null
        }
    }

    private fun conflictingZones(bias: DirectionBias): Set<DirectionZone> = when (bias) {
        DirectionBias.LEFT -> setOf(DirectionZone.LEFT, DirectionZone.FRONT_LEFT)
        DirectionBias.RIGHT -> setOf(DirectionZone.RIGHT, DirectionZone.FRONT_RIGHT)
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
