package com.sailens.guidance.processor.decision

import com.sailens.guidance.model.common.DirectionBias
import com.sailens.guidance.model.common.DirectionZone
import com.sailens.guidance.model.common.DistanceLevel
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EventMergerTest {
    private val merger = EventMerger()

    @Test
    fun `merged center obstacle uses stable primary center cooldown key`() {
        val merged = merger.merge(
            listOf(
                obstacle(DirectionZone.LEFT),
                obstacle(DirectionZone.CENTER),
            )
        ).single()

        assertEquals("event_obstacle_left_center", merged.messageKey)
        assertEquals("obstacle_PRIMARY_CENTER", merged.dedupeKey)
        assertTrue("obstacle_CENTER" in merged.cooldownKeys)
        assertTrue("obstacle_PRIMARY_CENTER" in merged.cooldownKeys)
    }

    @Test
    fun `merged same-category obstacle keeps category-specific message`() {
        val merged = merger.merge(
            listOf(
                obstacle(DirectionZone.CENTER, suffix = "person"),
                obstacle(DirectionZone.RIGHT, suffix = "person"),
            )
        ).single()

        assertEquals("event_obstacle_center_right_person", merged.messageKey)
        assertEquals("obstacle_PRIMARY_CENTER", merged.dedupeKey)
    }

    @Test
    fun `merged mixed non-vehicle category obstacle falls back to zone-only message`() {
        val merged = merger.merge(
            listOf(
                obstacle(DirectionZone.CENTER, suffix = "person"),
                // 静态障碍没有类别后缀。回归点：这里不能把"无后缀"当成不存在而只看到 person，
                // 否则会播成"前方和右侧有行人"，凭空把一个静止物体说成了人。
                obstacle(DirectionZone.RIGHT, suffix = null),
            )
        ).single()

        assertEquals("event_obstacle_center_right", merged.messageKey)
    }

    @Test
    fun `bicycle and vehicle merge into one vehicle message`() {
        val merged = merger.merge(
            listOf(
                obstacle(DirectionZone.CENTER, suffix = "vehicle"),
                obstacle(DirectionZone.RIGHT, suffix = "vehicle"),
            )
        ).single()

        // 自行车在 EventGenerator 就归到了 "vehicle"：两者的处置动作都是停下让行，
        // 而模型分不清一辆自行车是停着还是正骑过来。
        assertEquals("event_obstacle_center_right_vehicle", merged.messageKey)
    }

    @Test
    fun `mixed vehicle category obstacles remain category specific`() {
        val merged = merger.merge(
            listOf(
                obstacle(DirectionZone.CENTER, suffix = "person"),
                obstacle(DirectionZone.RIGHT, suffix = "vehicle"),
            )
        )

        assertEquals(
            setOf("event_obstacle_center_person", "event_obstacle_right_vehicle"),
            merged.map { it.messageKey }.toSet(),
        )
    }

    @Test
    fun `unsupported five-zone combinations fall back to multiple message`() {
        val merged = merger.merge(
            listOf(
                obstacle(DirectionZone.FRONT_LEFT),
                obstacle(DirectionZone.CENTER),
            )
        ).single()

        assertEquals("event_obstacle_multiple", merged.messageKey)
    }

    @Test
    fun `merge drops a direction hint that conflicts with the merged zones`() {
        val merged = merger.merge(
            listOf(
                // 各自合规：前方的障碍带"靠左"没问题，左侧的障碍本来就没挂 hint。
                obstacle(DirectionZone.CENTER, directionHint = DirectionBias.LEFT),
                obstacle(DirectionZone.LEFT),
            )
        ).single()

        // 合起来就成了"左侧和前方有障碍，靠左"——会把用户往左边那个障碍上引。
        assertEquals("event_obstacle_left_center", merged.messageKey)
        assertNull(merged.directionHint)
    }

    @Test
    fun `merge keeps a direction hint that stays clear of the merged zones`() {
        val merged = merger.merge(
            listOf(
                obstacle(DirectionZone.CENTER, directionHint = DirectionBias.LEFT),
                obstacle(DirectionZone.RIGHT, directionHint = DirectionBias.LEFT),
            )
        ).single()

        assertEquals(DirectionBias.LEFT, merged.directionHint)
    }

    @Test
    fun `merge reports the nearest distance across zones`() {
        val merged = merger.merge(
            listOf(
                obstacle(DirectionZone.CENTER, distance = DistanceLevel.MEDIUM),
                obstacle(DirectionZone.RIGHT, distance = DistanceLevel.NEAR),
            )
        ).single()

        // 任一方位上有近处障碍，整条提示就该带紧迫前缀。
        assertTrue(merged.isNear)
    }

    private fun obstacle(
        zone: DirectionZone,
        suffix: String? = null,
        distance: DistanceLevel? = null,
        directionHint: DirectionBias? = null,
    ): SceneEvent = SceneEvent(
        timestamp = 1L,
        category = EventCategory.OBSTACLE,
        priority = EventPriority.MEDIUM,
        messageKey = buildString {
            append("event_obstacle_${zone.name.lowercase()}")
            if (suffix != null) append("_$suffix")
        },
        expiresAt = 3L,
        dedupeKey = "obstacle_${zone.name}",
        cooldownKeys = setOf("obstacle_${zone.name}"),
        relatedZones = listOf(zone),
        distance = distance,
        directionHint = directionHint,
    )
}
