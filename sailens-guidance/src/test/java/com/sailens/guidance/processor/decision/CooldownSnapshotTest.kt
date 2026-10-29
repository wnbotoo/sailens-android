package com.sailens.guidance.processor.decision

import com.sailens.guidance.model.common.DirectionZone
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The prompt-policy evaluation reads the cooldown through [CooldownManager.snapshot]. It must say exactly
 * what [CooldownManager.filter] decides at the same moment, and change nothing.
 */
class CooldownSnapshotTest {

    @Test
    fun `the snapshot passes and holds exactly what the filter does, and leaves the cooldown as it was`() {
        val random = Random(1021)
        val cooldown = CooldownManager()
        val shadow = CooldownManager()
        var now = 0L
        var held = 0
        repeat(3_000) { step ->
            now += random.nextLong(50, 900)
            val stationary = random.nextInt(5) == 0
            val events = List(random.nextInt(1, 4)) { randomEvent(now, random) }

            val snapshot = cooldown.snapshot(now, stationary)
            val passed = cooldown.decide {
                setStationary(stationary)
                filter(events, now).also { it.firstOrNull()?.let { e -> recordEvent(e, now) } }
            }
            // The same decisions without any snapshot taken: identical outcomes prove it changes nothing.
            val shadowPassed = shadow.decide {
                setStationary(stationary)
                filter(events, now).also { it.firstOrNull()?.let { e -> recordEvent(e, now) } }
            }
            assertEquals("step $step", passed.map { it.id }, shadowPassed.map { it.id })
            for (e in events) {
                val holds = snapshot.holds(e)
                assertEquals("step $step: ${e.cooldownKeys} at ${e.priority}", e in passed, holds == null)
                if (holds != null) {
                    held++
                    assertTrue(holds.all { g -> g.holds.isNotEmpty() && g.holds.all { it.recordedPriority.value >= e.priority.value } })
                }
            }
            if (passed.isNotEmpty() && random.nextInt(3) == 0) {
                cooldown.revoke(passed.first().id)
                shadow.revoke(passed.first().id)
            }
        }
        assertTrue("the sequence must exercise holds: $held", held > 500)
    }

    private fun randomEvent(now: Long, random: Random): SceneEvent {
        val zones = DirectionZone.entries.shuffled(random).take(random.nextInt(1, 3))
        val priority = listOf(EventPriority.MEDIUM, EventPriority.HIGH, EventPriority.CRITICAL).random(random)
        return if (random.nextInt(6) == 0) {
            SceneEvent(timestamp = now, category = EventCategory.BLOCKED, priority = EventPriority.CRITICAL, messageKey = "event_blocked", expiresAt = now + 5_000, dedupeKey = "blocked")
        } else {
            val groups = zones.map { setOf("obstacle_${it.name}", if (it == DirectionZone.CENTER) "obstacle_PRIMARY_CENTER" else "obstacle_PRIMARY_SIDE") }
            SceneEvent(
                timestamp = now, category = EventCategory.OBSTACLE, priority = priority, messageKey = "event_obstacle_center",
                expiresAt = now + 3_000, dedupeKey = "obstacle", cooldownKeys = groups.flatten().toSet(),
                cooldownGroups = if (groups.size > 1) groups else emptyList(), relatedZones = zones,
            )
        }
    }
}
