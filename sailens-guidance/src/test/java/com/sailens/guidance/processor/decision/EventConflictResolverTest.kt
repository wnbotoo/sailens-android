package com.sailens.guidance.processor.decision

import com.sailens.guidance.model.common.DirectionZone
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.common.Severity
import com.sailens.guidance.model.scene.SceneEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventConflictResolverTest {
    private val resolver = EventConflictResolver()

    @Test
    fun `blocked suppresses all center obstacles but keeps non center obstacles`() {
        val events = listOf(
            sceneEvent(category = EventCategory.BLOCKED, messageKey = "event_blocked"),
            sceneEvent(
                category = EventCategory.OBSTACLE,
                messageKey = "event_obstacle_center",
                relatedZones = listOf(DirectionZone.CENTER)
            ),
            sceneEvent(
                category = EventCategory.OBSTACLE,
                messageKey = "event_obstacle_center",
                relatedZones = listOf(DirectionZone.CENTER)
            ),
            sceneEvent(
                category = EventCategory.OBSTACLE,
                messageKey = "event_obstacle_left",
                relatedZones = listOf(DirectionZone.LEFT)
            )
        )

        val resolved = resolver.resolve(events)

        assertEquals(2, resolved.size)
        assertTrue(resolved.any { it.category == EventCategory.BLOCKED })
        assertTrue(
            resolved.any {
                it.category == EventCategory.OBSTACLE && it.relatedZones == listOf(DirectionZone.LEFT)
            }
        )
    }

    @Test
    fun `blocked suppresses narrowing`() {
        val events = listOf(
            sceneEvent(category = EventCategory.BLOCKED, messageKey = "event_blocked"),
            sceneEvent(category = EventCategory.NARROWING, messageKey = "event_narrowing"),
        )

        val resolved = resolver.resolve(events)

        assertEquals(listOf(EventCategory.BLOCKED), resolved.map { it.category })
    }

    @Test
    fun `path complex keeps center obstacle but suppresses generic narrowing`() {
        val events = listOf(
            sceneEvent(category = EventCategory.PATH_COMPLEX, messageKey = "event_path_complex"),
            sceneEvent(
                category = EventCategory.OBSTACLE,
                messageKey = "event_obstacle_center_person",
                relatedZones = listOf(DirectionZone.CENTER)
            ),
            sceneEvent(category = EventCategory.NARROWING, messageKey = "event_narrowing"),
        )

        val resolved = resolver.resolve(events)

        assertEquals(
            listOf(EventCategory.PATH_COMPLEX, EventCategory.OBSTACLE),
            resolved.map { it.category },
        )
    }

    @Test
    fun `sensor quality suppresses every other event`() {
        val events = listOf(
            sceneEvent(
                category = EventCategory.OBSTACLE,
                messageKey = "event_obstacle_center",
                relatedZones = listOf(DirectionZone.CENTER),
            ),
            sceneEvent(category = EventCategory.BLOCKED, messageKey = "event_blocked"),
            sceneEvent(category = EventCategory.SENSOR_QUALITY, messageKey = "event_camera_blocked"),
            sceneEvent(category = EventCategory.INTERSECTION, messageKey = "event_intersection"),
        )

        val resolved = resolver.resolve(events)

        // 镜头看不见时，其余事件全部建立在一张不可信的画面上。播出去比不播更危险：
        // 用户会把"有提示、没说危险"理解成"前方安全"。
        assertEquals(1, resolved.size)
        assertEquals(EventCategory.SENSOR_QUALITY, resolved.single().category)
    }

    @Test
    fun `resolution is unchanged when frame quality is fine`() {
        val events = listOf(
            sceneEvent(category = EventCategory.INTERSECTION, messageKey = "event_intersection"),
        )

        assertEquals(events, resolver.resolve(events))
    }

    private fun sceneEvent(
        category: EventCategory,
        messageKey: String,
        relatedZones: List<DirectionZone> = emptyList(),
    ) = SceneEvent(
        timestamp = 1L,
        category = category,
        priority = EventPriority.HIGH,
        messageKey = messageKey,
        expiresAt = 2L,
        dedupeKey = "${category.name}_$messageKey",
        severity = Severity.MODERATE,
        relatedZones = relatedZones
    )
}
