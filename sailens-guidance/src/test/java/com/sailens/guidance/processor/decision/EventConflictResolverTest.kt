package com.sailens.guidance.processor.decision

import com.sailens.guidance.model.common.DirectionZone
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.common.Severity
import com.sailens.guidance.model.perception.ObstacleSource
import com.sailens.guidance.model.scene.EventComponent
import com.sailens.guidance.model.scene.HazardRef
import com.sailens.guidance.model.scene.SceneEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

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

    private fun centerObstacle(source: ObstacleSource) = sceneEvent(
        category = EventCategory.OBSTACLE,
        messageKey = "event_obstacle_center",
        relatedZones = listOf(DirectionZone.CENTER),
    ).copy(
        components = listOf(
            EventComponent(
                DirectionZone.CENTER, EventPriority.HIGH, UUID(0, 1), setOf("obstacle_CENTER"),
                listOf(HazardRef(UUID(0, 1), ObstacleCategory.PERSON, todayNear = false, speechNear = false, ownPriority = EventPriority.HIGH, source = source)),
            ),
        ),
    )

    @Test
    fun `a blocked path keeps a detected obstacle ahead only when told to`() {
        val blocked = sceneEvent(category = EventCategory.BLOCKED, messageKey = "event_blocked")
        val events = listOf(blocked, centerObstacle(ObstacleSource.DETECTION))
        assertEquals(listOf(EventCategory.BLOCKED), resolver.resolve(events).map { it.category })
        assertEquals(listOf(EventCategory.BLOCKED, EventCategory.OBSTACLE), EventConflictResolver(keepBlockedWithObstacle = true).resolve(events).map { it.category })
    }

    @Test
    fun `a semantic-source obstacle ahead stays suppressed, one mask saying one thing`() {
        val events = listOf(sceneEvent(category = EventCategory.BLOCKED, messageKey = "event_blocked"), centerObstacle(ObstacleSource.SEMANTIC))
        assertEquals(listOf(EventCategory.BLOCKED), EventConflictResolver(keepBlockedWithObstacle = true).resolve(events).map { it.category })
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
