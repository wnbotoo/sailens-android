package com.friady.sailens.domain.processor.decision

import com.friady.sailens.domain.model.common.DirectionZone
import com.friady.sailens.domain.model.common.EventCategory
import com.friady.sailens.domain.model.common.EventPriority
import com.friady.sailens.domain.model.scene.SceneEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CooldownManagerTest {
    private val cooldownManager = CooldownManager()

    @Test
    fun `cooldown suppresses repeated event with same dedupe key`() {
        val first = obstacleEvent(
            messageKey = "event_obstacle_left",
            dedupeKey = "obstacle_LEFT",
            zone = DirectionZone.LEFT,
        )
        val repeated = obstacleEvent(
            messageKey = "event_obstacle_left",
            dedupeKey = "obstacle_LEFT",
            zone = DirectionZone.LEFT,
        )

        cooldownManager.recordEvent(first, now = 10_000)

        val filtered = cooldownManager.filter(listOf(repeated), now = 11_000)

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `cooldown allows same category event when dedupe key changes`() {
        val left = obstacleEvent(
            messageKey = "event_obstacle_left",
            dedupeKey = "obstacle_LEFT",
            zone = DirectionZone.LEFT,
        )
        val center = obstacleEvent(
            messageKey = "event_obstacle_center",
            dedupeKey = "obstacle_CENTER",
            zone = DirectionZone.CENTER,
        )

        cooldownManager.recordEvent(left, now = 10_000)

        val filtered = cooldownManager.filter(listOf(center), now = 11_000)

        assertEquals(listOf(center), filtered)
    }

    @Test
    fun `cooldown suppresses obstacle when any shared cooldown key is active`() {
        val mergedLeftCenter = obstacleEvent(
            messageKey = "event_obstacle_left_center",
            dedupeKey = "obstacle_PRIMARY_CENTER",
            zone = DirectionZone.CENTER,
            cooldownKeys = setOf("obstacle_LEFT", "obstacle_CENTER", "obstacle_PRIMARY_CENTER"),
        )
        val center = obstacleEvent(
            messageKey = "event_obstacle_center",
            dedupeKey = "obstacle_CENTER",
            zone = DirectionZone.CENTER,
            cooldownKeys = setOf("obstacle_CENTER", "obstacle_PRIMARY_CENTER"),
        )

        cooldownManager.recordEvent(mergedLeftCenter, now = 10_000)

        val filtered = cooldownManager.filter(listOf(center), now = 11_000)

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `vehicle road warning uses shorter cooldown`() {
        val first = roadWarningEvent(
            messageKey = "event_road_warning_vehicle",
            dedupeKey = "road_warning_vehicle",
        )
        val repeated = roadWarningEvent(
            messageKey = "event_road_warning_vehicle",
            dedupeKey = "road_warning_vehicle",
        )

        cooldownManager.recordEvent(first, now = 10_000)

        assertTrue(cooldownManager.filter(listOf(repeated), now = 14_999).isEmpty())
        assertEquals(listOf(repeated), cooldownManager.filter(listOf(repeated), now = 15_000))
    }

    @Test
    fun `non vehicle road warning keeps category cooldown`() {
        val first = roadWarningEvent(
            messageKey = "event_road_warning",
            dedupeKey = "road_warning_area",
        )
        val repeated = roadWarningEvent(
            messageKey = "event_road_warning",
            dedupeKey = "road_warning_area",
        )

        cooldownManager.recordEvent(first, now = 10_000)

        assertTrue(cooldownManager.filter(listOf(repeated), now = 15_000).isEmpty())
        assertEquals(listOf(repeated), cooldownManager.filter(listOf(repeated), now = 22_000))
    }

    private fun obstacleEvent(
        messageKey: String,
        dedupeKey: String,
        zone: DirectionZone,
        cooldownKeys: Set<String> = emptySet(),
    ): SceneEvent = SceneEvent(
        timestamp = 10_000,
        category = EventCategory.OBSTACLE,
        priority = EventPriority.MEDIUM,
        messageKey = messageKey,
        expiresAt = 13_000,
        dedupeKey = dedupeKey,
        cooldownKeys = cooldownKeys,
        relatedZones = listOf(zone),
    )

    private fun roadWarningEvent(
        messageKey: String,
        dedupeKey: String,
    ): SceneEvent = SceneEvent(
        timestamp = 10_000,
        category = EventCategory.ROAD_WARNING,
        priority = EventPriority.HIGH,
        messageKey = messageKey,
        expiresAt = 15_000,
        dedupeKey = dedupeKey,
    )
}
