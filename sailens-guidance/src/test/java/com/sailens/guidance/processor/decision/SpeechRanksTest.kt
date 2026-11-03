package com.sailens.guidance.processor.decision

import com.sailens.guidance.config.DetFirstConfig
import com.sailens.guidance.model.common.DirectionZone
import com.sailens.guidance.model.common.DistanceLevel
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.perception.ObstacleSource
import com.sailens.guidance.model.scene.EventComponent
import com.sailens.guidance.model.scene.HazardRef
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.scene.SceneEventMessageKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SpeechRanksTest {
    private val on = DetFirstConfig(speechRank = true)
    private val off = DetFirstConfig()
    private val track = UUID(0, 1)

    private fun event(category: EventCategory, priority: EventPriority = EventPriority.HIGH, messageKey: String = SceneEventMessageKeys.BLOCKED) =
        SceneEvent(timestamp = 0, category = category, priority = priority, messageKey = messageKey, expiresAt = 1, dedupeKey = "k")

    private fun obstacle(near: Boolean, source: ObstacleSource = ObstacleSource.DETECTION, category: ObstacleCategory = ObstacleCategory.PERSON) = event(
        EventCategory.OBSTACLE,
        messageKey = SceneEventMessageKeys.obstacle(SceneEventMessageKeys.zonePart(DirectionZone.CENTER), SceneEventMessageKeys.SUFFIX_PERSON),
    ).copy(
        relatedZones = listOf(DirectionZone.CENTER),
        distance = if (near) DistanceLevel.NEAR else DistanceLevel.MEDIUM,
        components = listOf(
            EventComponent(
                DirectionZone.CENTER, EventPriority.HIGH, track, setOf("obstacle_CENTER"),
                listOf(HazardRef(track, category, todayNear = near, speechNear = near, ownPriority = EventPriority.HIGH, source = source)),
            ),
        ),
    )

    @Test
    fun `any band stands above every rank of the band below`() {
        val bands = EventPriority.entries.sortedBy { it.value }
        for ((lower, higher) in bands.zipWithNext()) {
            assertTrue(SpeechScale.of(lower, SpeechScale.MAX_RANK) < SpeechScale.of(higher, 0))
        }
    }

    @Test
    fun `with every rank 0 two sentences compare as their priorities do`() {
        for (a in EventPriority.entries) for (b in EventPriority.entries) {
            assertEquals(a.value.compareTo(b.value), SpeechScale.of(a).compareTo(SpeechScale.of(b)))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a rank outside the scale fails at once`() {
        SpeechScale.of(EventPriority.HIGH, SpeechScale.FACTOR)
    }

    @Test
    fun `the table`() {
        assertEquals(7, SpeechRanks.of(event(EventCategory.SENSOR_QUALITY), on))
        assertEquals(5, SpeechRanks.of(obstacle(near = true), on))
        assertEquals(4, SpeechRanks.of(obstacle(near = false), on))
        assertEquals(4, SpeechRanks.of(event(EventCategory.BLOCKED), on))
        assertEquals("a semantic-source obstacle stays level with BLOCKED", 4, SpeechRanks.of(obstacle(near = true, source = ObstacleSource.SEMANTIC), on))
        assertEquals(0, SpeechRanks.of(event(EventCategory.PATH_COMPLEX), on))
        assertEquals(0, SpeechRanks.of(event(EventCategory.GROUND_UNRECOGNIZED), on))
        assertEquals(0, SpeechRanks.of(event(EventCategory.TRAFFIC_LIGHT), on))
        assertEquals(7, SpeechRanks.ofNotice(SceneEventMessageKeys.CAMERA_BLOCKED, on))
        assertEquals(7, SpeechRanks.ofNotice(SceneEventMessageKeys.LOW_LIGHT, on))
        assertEquals(0, SpeechRanks.ofNotice(SceneEventMessageKeys.CAMERA_RESTORED, on))
        assertEquals(0, SpeechRanks.ofNotice(SceneEventMessageKeys.GROUND_UNRECOGNIZED, on))
        assertEquals(6, SpeechRanks.ofStalledNotice(on))
    }

    @Test
    fun `a NEAR prefix that speaks ambiguously for a hazard earns no rank`() {
        // The word says person, the NEAR one is a vehicle: the prefix is not credited to it.
        assertEquals(4, SpeechRanks.of(obstacle(near = true, category = ObstacleCategory.VEHICLE), on))
    }

    @Test
    fun `off, every rank is 0`() {
        assertEquals(0, SpeechRanks.of(event(EventCategory.SENSOR_QUALITY), off))
        assertEquals(0, SpeechRanks.of(obstacle(near = true), off))
        assertEquals(0, SpeechRanks.ofNotice(SceneEventMessageKeys.CAMERA_BLOCKED, off))
        assertEquals(0, SpeechRanks.ofVisionFaultRhythm(EventPriority.CRITICAL, off))
        assertEquals(0, SpeechRanks.ofStalledNotice(off))
    }

    @Test
    fun `a blocked path gives way to an obstacle saying NEAR, and leads one that does not by generation order`() {
        val blocked = event(EventCategory.BLOCKED, EventPriority.CRITICAL).let { it.copy(rank = SpeechRanks.of(it, on)) }
        val near = obstacle(near = true).copy(priority = EventPriority.CRITICAL).let { it.copy(rank = SpeechRanks.of(it, on)) }
        val notNear = obstacle(near = false).copy(priority = EventPriority.CRITICAL).let { it.copy(rank = SpeechRanks.of(it, on)) }
        assertEquals(listOf(near, blocked), listOf(blocked, near).sortedByDescending { SpeechScale.of(it) })
        assertEquals(listOf(blocked, notNear), listOf(blocked, notNear).sortedByDescending { SpeechScale.of(it) })
    }
}
