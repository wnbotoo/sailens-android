package com.sailens.guidance.processor.decision

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CooldownEscalationTest {
    private val person = ObstacleCategory.PERSON
    private val vehicle = ObstacleCategory.VEHICLE

    private fun id(n: Int) = UUID(0, n.toLong())

    private class Hazard(val n: Int, val category: ObstacleCategory, val near: Boolean = false, val own: EventPriority = EventPriority.HIGH, val source: ObstacleSource = ObstacleSource.DETECTION)

    private fun zoneEvent(t: Long, zone: DirectionZone, priority: EventPriority, primary: Hazard, others: List<Hazard> = emptyList()): SceneEvent {
        val hazards = listOf(primary) + others
        val word = obstacleSpeechWord(primary.category)
        val keys = buildSet {
            add("obstacle_${zone.name}")
            add(if (zone == DirectionZone.CENTER) "obstacle_PRIMARY_CENTER" else "obstacle_PRIMARY_SIDE")
        }
        return SceneEvent(
            timestamp = t,
            category = EventCategory.OBSTACLE,
            priority = priority,
            messageKey = SceneEventMessageKeys.obstacle(SceneEventMessageKeys.zonePart(zone), word),
            expiresAt = t + 3_000,
            dedupeKey = "obstacle_${zone.name}",
            cooldownKeys = keys,
            relatedZones = listOf(zone),
            distance = if (hazards.any { it.near }) DistanceLevel.NEAR else DistanceLevel.MEDIUM,
            components = listOf(
                EventComponent(
                    zone, priority, id(primary.n), keys,
                    hazards.map { HazardRef(id(it.n), it.category, it.near, it.near, it.own, it.source) },
                ),
            ),
        )
    }

    private fun center(t: Long, priority: EventPriority, primary: Hazard, others: List<Hazard> = emptyList()) = zoneEvent(t, DirectionZone.CENTER, priority, primary, others)

    private fun CooldownManager.passes(event: SceneEvent, t: Long) = filter(listOf(event), t).isNotEmpty()

    /** Says [event] if it passes: heard at once. */
    private fun CooldownManager.say(event: SceneEvent, t: Long): Boolean {
        if (!passes(event, t)) return false
        recordEvent(event, t)
        confirm(event.id)
        return true
    }

    private val on get() = CooldownManager(proximityEscalation = true)

    @Test
    fun `a scene that does not change gets exactly what today gives it`() {
        val critical = Hazard(1, person, own = EventPriority.CRITICAL)
        val side = Hazard(2, person)
        fun merged(t: Long): SceneEvent {
            val a = center(t, EventPriority.CRITICAL, critical)
            val b = zoneEvent(t, DirectionZone.LEFT, EventPriority.HIGH, side)
            return EventMerger().merge(listOf(a, b)).single().let { if (it.components.size == 2) it else error("not merged") }
        }
        val withEscalation = on
        val today = CooldownManager()
        for (t in 0L..60_000L step 100) {
            assertEquals("t $t", today.say(merged(t), t), withEscalation.say(merged(t), t))
        }
    }

    @Test
    fun `a NEAR or CRITICAL that stays passes at most once every 8 s, and otherwise as today`() {
        for ((hazard, priority) in listOf(Hazard(1, person, near = true) to EventPriority.HIGH, Hazard(1, vehicle, own = EventPriority.CRITICAL) to EventPriority.CRITICAL)) {
            val withEscalation = on
            val today = CooldownManager()
            var extra = 0
            var last = Long.MIN_VALUE / 2
            for (t in 0L..60_000L step 100) {
                val mine = withEscalation.say(center(t, priority, hazard), t)
                val todays = today.passes(center(t, priority, hazard), t)
                if (mine) {
                    if (!todays) {
                        extra++
                        assertTrue("t $t", t - last >= 8_000)
                    }
                    last = t
                }
                if (todays) today.say(center(t, priority, hazard), t)
            }
            assertEquals(0, extra)
        }
    }

    @Test
    fun `another hazard's escalation is not held by the first's refresh of the key`() {
        // h1 CRITICAL at 0, h1 NEAR at 1.5 s refreshes the key, h2 turns CRITICAL at 4.1 s.
        val cooldown = on
        val h1 = Hazard(1, person, own = EventPriority.CRITICAL)
        assertTrue(cooldown.say(center(0, EventPriority.CRITICAL, h1), 0))
        assertTrue(cooldown.say(center(1_500, EventPriority.CRITICAL, Hazard(1, person, near = true, own = EventPriority.CRITICAL)), 1_500))
        assertTrue(cooldown.say(center(4_100, EventPriority.CRITICAL, Hazard(2, person, own = EventPriority.CRITICAL)), 4_100))
    }

    @Test
    fun `what was not said of a hazard does not hold what can be said of it`() {
        // V, a vehicle of its own priority CRITICAL, is only under "a person ahead" at 1.5 s: nothing said of it.
        val cooldown = on
        val person1 = Hazard(1, person, own = EventPriority.CRITICAL)
        val v = Hazard(2, vehicle, own = EventPriority.CRITICAL)
        assertTrue(cooldown.say(center(0, EventPriority.CRITICAL, person1), 0))
        cooldown.say(center(1_500, EventPriority.HIGH, Hazard(3, person), listOf(v)), 1_500)
        assertTrue(cooldown.say(center(3_000, EventPriority.CRITICAL, Hazard(4, person, own = EventPriority.CRITICAL)), 3_000))
        val sameWritesToday = CooldownManager().apply {
            say(center(0, EventPriority.CRITICAL, person1), 0)
            recordEvent(center(3_000, EventPriority.CRITICAL, Hazard(4, person, own = EventPriority.CRITICAL)), 3_000)
        }
        assertFalse("the refreshed key holds it", sameWritesToday.passes(center(4_100, EventPriority.CRITICAL, v), 4_100))
        assertTrue(cooldown.say(center(4_100, EventPriority.CRITICAL, v), 4_100))
    }

    @Test
    fun `a primary that cannot say a covered hazard's CRITICAL never passes for it, and leaves no record`() {
        val cooldown = on
        val car = Hazard(2, vehicle, own = EventPriority.CRITICAL)
        assertTrue(cooldown.say(center(0, EventPriority.HIGH, Hazard(1, person), listOf(car)), 0))
        for (t in 100L..1_900L step 100) {
            assertFalse("t $t", cooldown.passes(center(t, EventPriority.HIGH, Hazard(1, person), listOf(car)), t))
        }
        assertTrue("and what can say it passes", cooldown.passes(center(2_000, EventPriority.CRITICAL, car), 2_000))
    }

    @Test
    fun `two writes taken back, in either order, leave no record`() {
        for (firstBack in listOf(true, false)) {
            val cooldown = on
            val a = center(0, EventPriority.HIGH, Hazard(1, person, near = true))
            val b = center(500, EventPriority.HIGH, Hazard(2, person, near = true))
            cooldown.recordEvent(a, 0)
            cooldown.recordEvent(b, 500)
            for (e in if (firstBack) listOf(a, b) else listOf(b, a)) cooldown.revoke(e.id)
            assertEquals(emptyMap<UUID, CooldownManager.EscalationRecord>(), cooldown.canonicalState().escalation)
            assertTrue(cooldown.passes(center(600, EventPriority.HIGH, Hazard(1, person, near = true)), 600))
        }
    }

    @Test
    fun `in a merged event only the hazard turning NEAR passes for itself`() {
        val cooldown = on
        val a = Hazard(1, person)
        val b = Hazard(2, person)
        fun merged(t: Long, nearA: Boolean) = EventMerger().merge(
            listOf(
                center(t, EventPriority.HIGH, Hazard(1, person, near = nearA)),
                zoneEvent(t, DirectionZone.LEFT, EventPriority.HIGH, b),
            ),
        ).single()
        assertTrue(cooldown.say(merged(0, nearA = false), 0))
        assertFalse(cooldown.passes(merged(500, nearA = false), 500))
        assertTrue(cooldown.say(merged(1_000, nearA = true), 1_000))
        assertFalse("said", cooldown.passes(merged(1_500, nearA = true), 1_500))
        assertEquals(1_000L, cooldown.canonicalState().escalation[id(a.n)]?.nearAt)
        assertEquals(null, cooldown.canonicalState().escalation[id(b.n)]?.nearAt)
    }

    @Test
    fun `a write over an older one, taken back, is as if it never happened`() {
        val cooldown = on
        val h = Hazard(1, person)
        assertTrue(cooldown.say(center(0, EventPriority.HIGH, h), 0))
        val before = cooldown.canonicalState()
        val over = center(1_000, EventPriority.CRITICAL, Hazard(1, person, near = true, own = EventPriority.CRITICAL))
        cooldown.recordEvent(over, 1_000)
        assertTrue(cooldown.canonicalState().escalation != before.escalation)
        cooldown.revoke(over.id)
        assertEquals(before.escalation, cooldown.canonicalState().escalation)
        assertEquals(before.records, cooldown.canonicalState().records)
    }

    @Test
    fun `flickering NEAR or CRITICAL passes for it at most once every 8 s`() {
        for (kind in listOf("near", "critical")) {
            val cooldown = on
            val today = CooldownManager()
            var extra = 0
            for (t in 0L..20_000L step 100) {
                val flick = (t / 100) % 2 == 0L
                val event = when (kind) {
                    "near" -> center(t, EventPriority.HIGH, Hazard(1, person, near = flick))
                    else -> center(t, if (flick) EventPriority.CRITICAL else EventPriority.HIGH, Hazard(1, person, own = if (flick) EventPriority.CRITICAL else EventPriority.HIGH))
                }
                val todays = today.passes(event, t)
                val said = cooldown.say(event, t)
                if (todays) today.say(event, t)
                if (said && !todays) extra++
            }
            assertTrue("$kind passed $extra times beyond today", extra <= 3)
        }
    }

    @Test
    fun `a hazard said at FIRST turning NEAR at the same priority passes, where today it is held`() {
        val cooldown = on
        val today = CooldownManager()
        for (c in listOf(cooldown, today)) assertTrue(c.say(center(0, EventPriority.HIGH, Hazard(1, person)), 0))
        val near = center(2_000, EventPriority.HIGH, Hazard(1, person, near = true))
        assertFalse(today.passes(near, 2_000))
        assertTrue(cooldown.passes(near, 2_000))
    }

    @Test
    fun `a hazard's own priority rising passes though the key holds a higher one`() {
        val cooldown = on
        assertTrue(cooldown.say(center(0, EventPriority.CRITICAL, Hazard(1, vehicle, own = EventPriority.CRITICAL), listOf(Hazard(2, vehicle, own = EventPriority.MEDIUM))), 0))
        val risen = center(1_000, EventPriority.HIGH, Hazard(2, vehicle, own = EventPriority.HIGH))
        assertFalse(CooldownManager().apply { say(center(0, EventPriority.CRITICAL, Hazard(1, vehicle, own = EventPriority.CRITICAL)), 0) }.passes(risen, 1_000))
        assertTrue(cooldown.passes(risen, 1_000))
    }

    @Test
    fun `ambiguous NEAR, and a CRITICAL track under a lower prompt, do not pass`() {
        val cooldown = on
        assertTrue(cooldown.say(center(0, EventPriority.HIGH, Hazard(1, person)), 0))
        assertFalse("the prefix is not true of the vehicle by the word person", cooldown.passes(center(500, EventPriority.HIGH, Hazard(1, person), listOf(Hazard(2, vehicle, near = true))), 500))
        assertFalse(cooldown.passes(center(600, EventPriority.HIGH, Hazard(1, person), listOf(Hazard(3, person, own = EventPriority.CRITICAL))), 600))
    }

    @Test
    fun `a semantic-source obstacle keeps today's rule`() {
        val cooldown = on
        assertTrue(cooldown.say(center(0, EventPriority.HIGH, Hazard(1, person, source = ObstacleSource.SEMANTIC)), 0))
        assertFalse(cooldown.passes(center(1_000, EventPriority.HIGH, Hazard(1, person, near = true, source = ObstacleSource.SEMANTIC)), 1_000))
    }

    @Test
    fun `off, nothing is recorded and nothing passes for a hazard`() {
        val cooldown = CooldownManager()
        assertTrue(cooldown.say(center(0, EventPriority.HIGH, Hazard(1, person)), 0))
        assertEquals(emptyMap<UUID, CooldownManager.EscalationRecord>(), cooldown.canonicalState().escalation)
        assertFalse(cooldown.passes(center(1_000, EventPriority.HIGH, Hazard(1, person, near = true)), 1_000))
    }

    @Test
    fun `a copy goes on as the original does`() {
        val cooldown = on
        assertTrue(cooldown.say(center(0, EventPriority.HIGH, Hazard(1, person, near = true)), 0))
        val copy = cooldown.copy()
        assertEquals(cooldown.canonicalState(), copy.canonicalState())
        assertFalse(copy.passes(center(1_000, EventPriority.HIGH, Hazard(1, person, near = true)), 1_000))
        assertTrue(copy.passes(center(9_000, EventPriority.HIGH, Hazard(1, person, near = true)), 9_000))
    }
}
