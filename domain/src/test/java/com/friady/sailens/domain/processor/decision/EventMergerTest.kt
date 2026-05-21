package com.friady.sailens.domain.processor.decision

import com.friady.sailens.domain.model.common.DirectionZone
import com.friady.sailens.domain.model.common.EventCategory
import com.friady.sailens.domain.model.common.EventPriority
import com.friady.sailens.domain.model.scene.SceneEvent
import org.junit.Assert.assertEquals
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
    fun `unsupported five-zone combinations fall back to multiple message`() {
        val merged = merger.merge(
            listOf(
                obstacle(DirectionZone.FRONT_LEFT),
                obstacle(DirectionZone.CENTER),
            )
        ).single()

        assertEquals("event_obstacle_multiple", merged.messageKey)
    }

    private fun obstacle(zone: DirectionZone): SceneEvent = SceneEvent(
        timestamp = 1L,
        category = EventCategory.OBSTACLE,
        priority = EventPriority.MEDIUM,
        messageKey = "event_obstacle_${zone.name.lowercase()}",
        expiresAt = 3L,
        dedupeKey = "obstacle_${zone.name}",
        cooldownKeys = setOf("obstacle_${zone.name}"),
        relatedZones = listOf(zone),
    )
}
