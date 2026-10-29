package com.sailens.guidance.processor.decision

import com.sailens.guidance.model.common.DirectionZone
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlin.random.Random

/**
 * The cooldown's revocation re-folds the remaining writes instead of restoring snapshots; its meaning
 * is unchanged.
 *
 * 1. Wherever today's snapshot revocation was right — each revocation takes back the last write that
 *    can still be revoked, before the next write — the new implementation is bit-for-bit today's
 *    ([LegacyCooldownManager], a verbatim copy), confirmations or not.
 * 2. Where it was wrong (interleaved revocations), the new one leaves no record of an event that never
 *    reached the user.
 * 3. Replaying only the recorded revocations, with no confirmations, reproduces a device that confirms.
 */
class CooldownRevocationTest {

    // ---- 1. Equivalence under the condition ----------------------------------------------------

    @Test
    fun `under the condition every record and the storm count are today's, step by step`() {
        for (seed in 1..20) {
            val random = Random(seed)
            val legacy = LegacyCooldownManager()
            val cooldown = CooldownManager()
            var now = 0L
            var revoked = 0
            var confirmed = 0
            // Writes the new implementation has been told were delivered later, as a device would.
            val confirmLater = ArrayDeque<Pair<Int, UUID>>()
            var lastWriteAt = 0L
            repeat(1_500) { step ->
                val at = "seed $seed step $step"
                now = when (random.nextInt(10)) {
                    0 -> now
                    // Exactly one base cooldown after the last write: where inheritance and expiry turn.
                    1 -> maxOf(now, lastWriteAt + BASE_COOLDOWNS.random(random))
                    else -> now + random.nextLong(1, 900)
                }
                val stationary = random.nextInt(5) == 0
                val events = List(random.nextInt(0, 4)) { randomEvent(now, random) }

                val probe = List(3) { randomEvent(now, random) }
                val legacyHolds = legacy.snapshot(now, stationary).let { s ->
                    probe.map { e -> s.holds(e)?.map { g -> g.keys to g.holds.map { it.key to it.recordedPriority } } }
                }
                val holds = cooldown.snapshot(now, stationary).let { s ->
                    probe.map { e -> s.holds(e)?.map { g -> g.keys to g.holds.map { it.key to it.recordedPriority } } }
                }
                assertEquals("$at: snapshot", legacyHolds, holds)

                val legacyPassed = legacy.decide {
                    setStationary(stationary)
                    filter(events, now).also { it.firstOrNull()?.let { e -> recordEvent(e, now) } }
                }
                val passed = cooldown.decide {
                    setStationary(stationary)
                    filter(events, now).also { it.firstOrNull()?.let { e -> recordEvent(e, now) } }
                }
                assertEquals("$at: filter", legacyPassed.map { it.id }, passed.map { it.id })
                assertEquals("$at: after the write", legacy.state(), cooldown.state())

                val written = passed.firstOrNull()
                if (written != null) lastWriteAt = now
                when {
                    // The condition: the last write, taken back before the next one.
                    written != null && random.nextInt(3) == 0 -> {
                        assertEquals("$at: revoke", legacy.revoke(written.id), cooldown.revoke(written.id))
                        revoked++
                    }
                    // Delivered: confirmed now or a few writes later, or never heard of again.
                    written != null && random.nextInt(4) != 0 -> confirmLater.addLast(step + random.nextInt(0, 6) to written.id)
                }
                // An id nobody ever recorded is a no-op in both.
                if (random.nextInt(20) == 0) assertEquals(legacy.revoke(UUID.randomUUID()), cooldown.revoke(UUID.randomUUID()))
                while (confirmLater.firstOrNull()?.first?.let { it <= step } == true) {
                    cooldown.confirm(confirmLater.removeFirst().second)
                    confirmed++
                }
                assertEquals("$at: after the outcome", legacy.state(), cooldown.state())

                if (random.nextInt(400) == 0) {
                    legacy.reset()
                    cooldown.reset()
                    confirmLater.clear()
                }
            }
            assertTrue("seed $seed must exercise revocations ($revoked) and confirmations ($confirmed)", revoked > 100 && confirmed > 100)
        }
    }

    @Test
    fun `the bound of sixteen open writes ends revocability exactly where today's undo log did`() {
        val legacy = LegacyCooldownManager()
        val cooldown = CooldownManager()
        val writes = List(17) { i -> obstacle(EventPriority.HIGH, "obstacle_K$i") }
        writes.forEachIndexed { i, event ->
            legacy.recordEvent(event, now = 1_000L + i)
            cooldown.recordEvent(event, now = 1_000L + i)
        }
        // The first write is the seventeenth from the end: no longer revocable in either.
        legacy.revoke(writes.first().id)
        cooldown.revoke(writes.first().id)
        assertEquals(legacy.state(), cooldown.state())
        assertTrue(cooldown.state().records.containsKey("obstacle_K0"))
        // The second still is, and in both it comes off.
        legacy.revoke(writes[1].id)
        cooldown.revoke(writes[1].id)
        assertEquals(legacy.state(), cooldown.state())
        assertTrue(!cooldown.state().records.containsKey("obstacle_K1"))
    }

    // ---- 2. The defect fixed on purpose ----------------------------------------------------------

    @Test
    fun `A then B written, A revoked, then B - no record of either is left`() {
        val a = obstacle(EventPriority.CRITICAL, KEY)
        val b = obstacle(EventPriority.HIGH, KEY)

        val legacy = LegacyCooldownManager()
        legacy.recordEvent(a, now = 10_000)
        legacy.recordEvent(b, now = 11_000)
        legacy.revoke(a.id)
        legacy.revoke(b.id)
        // Today: A's CRITICAL record comes back, although neither reached the user.
        assertEquals(Triple(10_000L, EventPriority.CRITICAL, a.id), legacy.state().records[KEY])

        val cooldown = CooldownManager()
        cooldown.recordEvent(a, now = 10_000)
        cooldown.recordEvent(b, now = 11_000)
        cooldown.revoke(a.id)
        // B alone: its own priority, not the CRITICAL it inherited from A.
        assertEquals(Triple(11_000L, EventPriority.HIGH, b.id), cooldown.state().records[KEY])
        cooldown.revoke(b.id)
        assertEquals(emptyMap<String, Any>(), cooldown.state().records)
        assertEquals(emptyList<Long>(), cooldown.state().emissions)
        // And a repeat at the lower priority is not held back by anything.
        val repeat = obstacle(EventPriority.MEDIUM, KEY)
        assertEquals(listOf(repeat), cooldown.filter(listOf(repeat), now = 11_100))
    }

    @Test
    fun `B confirmed, then A revoked - B keeps its own priority and can no longer be revoked`() {
        val a = obstacle(EventPriority.CRITICAL, KEY)
        val b = obstacle(EventPriority.HIGH, KEY)
        val cooldown = CooldownManager()
        cooldown.recordEvent(a, now = 10_000)
        cooldown.recordEvent(b, now = 11_000)
        cooldown.confirm(b.id)
        // B was delivered but A is still pending: B may not be folded past A.
        assertEquals(Triple(11_000L, EventPriority.CRITICAL, b.id), cooldown.state().records[KEY])

        cooldown.revoke(a.id)
        assertEquals(Triple(11_000L, EventPriority.HIGH, b.id), cooldown.state().records[KEY])
        assertEquals(listOf(11_000L), cooldown.state().emissions)

        cooldown.revoke(b.id)
        assertEquals("a confirmed write stays", Triple(11_000L, EventPriority.HIGH, b.id), cooldown.state().records[KEY])
        assertEquals(listOf(11_000L), cooldown.state().emissions)
        // B's record holds an equal repeat, and no longer an escalation to CRITICAL.
        assertEquals(emptyList<SceneEvent>(), cooldown.filter(listOf(obstacle(EventPriority.HIGH, KEY)), now = 11_500))
        val critical = obstacle(EventPriority.CRITICAL, KEY)
        assertEquals(listOf(critical), cooldown.filter(listOf(critical), now = 11_500))
    }

    @Test
    fun `revoked in the other order, B then A, nothing is left either`() {
        val a = obstacle(EventPriority.CRITICAL, KEY)
        val b = obstacle(EventPriority.HIGH, KEY)
        val cooldown = CooldownManager()
        cooldown.recordEvent(a, now = 10_000)
        cooldown.recordEvent(b, now = 11_000)
        cooldown.revoke(b.id)
        assertEquals(Triple(10_000L, EventPriority.CRITICAL, a.id), cooldown.state().records[KEY])
        cooldown.revoke(a.id)
        assertEquals(emptyMap<String, Any>(), cooldown.state().records)
    }

    @Test
    fun `revoking a pending write in the middle of an inheritance chain re-folds the confirmed writes around it`() {
        // Centre window 4 s; the chain of inheritance runs through confirmed and pending writes alike.
        val first = obstacle(EventPriority.CRITICAL, CENTER)
        val pending = obstacle(EventPriority.HIGH, CENTER)
        val last = obstacle(EventPriority.HIGH, CENTER)
        val cooldown = CooldownManager()
        cooldown.recordEvent(first, now = 0)
        cooldown.confirm(first.id)
        cooldown.recordEvent(pending, now = 3_000)
        cooldown.recordEvent(last, now = 6_000)
        cooldown.confirm(last.id)
        // first → pending → last inherits CRITICAL all the way (each within 4 s of the one before).
        assertEquals(EventPriority.CRITICAL, cooldown.state().records[CENTER]?.second)

        cooldown.revoke(pending.id)
        // Without the middle write, last is 6 s after first: outside the window, so its own HIGH.
        assertEquals(Triple(6_000L, EventPriority.HIGH, last.id), cooldown.state().records[CENTER])
    }

    @Test
    fun `merged events write every key of every group, and revoking one takes them all back`() {
        val left = setOf("obstacle_LEFT", "obstacle_PRIMARY_SIDE")
        val center = setOf("obstacle_CENTER", "obstacle_PRIMARY_CENTER")
        val merged = obstacle(EventPriority.CRITICAL, keys = left + center, groups = listOf(left, center))
        val single = obstacle(EventPriority.HIGH, keys = center)
        val cooldown = CooldownManager()
        cooldown.recordEvent(merged, now = 10_000)
        cooldown.recordEvent(single, now = 10_500)
        cooldown.revoke(merged.id)

        val records = cooldown.state().records
        assertEquals(center, records.keys)
        assertTrue(records.values.all { it.second == EventPriority.HIGH && it.third == single.id })
    }

    // ---- 3. Replay without confirmations ---------------------------------------------------------

    @Test
    fun `replaying only the revocations reproduces a device that also confirms, in any order`() {
        for (seed in 1..20) {
            val random = Random(seed * 7919)
            val device = CooldownManager()
            val replay = CooldownManager()
            var now = 0L
            // Each recorded write gets one outcome, after a random delay, or none at all.
            val outcomes = mutableListOf<Triple<Int, UUID, Boolean>>()
            var revokedEffective = 0
            repeat(1_500) { step ->
                now += random.nextLong(0, 700)
                val events = List(random.nextInt(0, 4)) { randomEvent(now, random) }
                val devicePassed = device.decide { filter(events, now).also { it.firstOrNull()?.let { e -> recordEvent(e, now) } } }
                val replayPassed = replay.decide { filter(events, now).also { it.firstOrNull()?.let { e -> recordEvent(e, now) } } }
                assertEquals("seed $seed step $step", devicePassed.map { it.id }, replayPassed.map { it.id })
                devicePassed.firstOrNull()?.let { written ->
                    if (random.nextInt(6) != 0) {
                        outcomes += Triple(step + random.nextInt(0, 24), written.id, random.nextInt(3) == 0)
                    }
                }
                outcomes.shuffle(random)
                val due = outcomes.filter { it.first <= step }
                outcomes.removeAll(due)
                for ((_, id, revoke) in due) {
                    if (revoke) {
                        val before = device.state()
                        device.revoke(id)
                        replay.revoke(id)
                        if (device.state() != before) revokedEffective++
                    } else {
                        device.confirm(id)
                    }
                }
                assertEquals("seed $seed step $step", device.state(), replay.state())
            }
            assertTrue("seed $seed must exercise revocations ($revokedEffective)", revokedEffective > 50)
        }
    }

    private fun randomEvent(now: Long, random: Random): SceneEvent {
        val zones = DirectionZone.entries.shuffled(random).take(random.nextInt(1, 3))
        val priority = EventPriority.entries.random(random)
        return when (random.nextInt(8)) {
            0 -> SceneEvent(
                timestamp = now, category = EventCategory.BLOCKED, priority = EventPriority.CRITICAL,
                messageKey = "event_blocked", expiresAt = now + 5_000, dedupeKey = "blocked",
            )
            1 -> SceneEvent(
                timestamp = now, category = EventCategory.SENSOR_QUALITY,
                priority = if (random.nextBoolean()) EventPriority.CRITICAL else EventPriority.HIGH,
                messageKey = "event_camera_blocked", expiresAt = now + 6_000,
                dedupeKey = if (random.nextBoolean()) "sensor_quality_obstructed" else "sensor_quality_too_dark",
            )
            2 -> SceneEvent(
                timestamp = now, category = EventCategory.PATH_COMPLEX, priority = EventPriority.MEDIUM,
                messageKey = "event_path_complex", expiresAt = now + 5_000, dedupeKey = "path_complex",
            )
            else -> {
                val groups = zones.map {
                    setOf("obstacle_${it.name}", if (it == DirectionZone.CENTER) "obstacle_PRIMARY_CENTER" else "obstacle_PRIMARY_SIDE")
                }
                SceneEvent(
                    timestamp = now, category = EventCategory.OBSTACLE, priority = priority,
                    messageKey = "event_obstacle_center", expiresAt = now + 3_000, dedupeKey = "obstacle",
                    cooldownKeys = groups.flatten().toSet(),
                    cooldownGroups = if (groups.size > 1) groups else emptyList(), relatedZones = zones,
                )
            }
        }
    }

    private fun obstacle(priority: EventPriority, vararg keys: String): SceneEvent =
        obstacle(priority, keys.toSet())

    private fun obstacle(priority: EventPriority, keys: Set<String>, groups: List<Set<String>> = emptyList()) = SceneEvent(
        timestamp = 0,
        category = EventCategory.OBSTACLE,
        priority = priority,
        messageKey = "event_obstacle_center",
        expiresAt = Long.MAX_VALUE,
        dedupeKey = "obstacle",
        cooldownKeys = keys,
        cooldownGroups = groups,
        relatedZones = listOf(DirectionZone.CENTER),
    )

    private companion object {
        /** A key with the obstacle category's 2 s base cooldown. */
        const val KEY = "obstacle_CENTER"

        /** A key with its own 4 s base cooldown. */
        const val CENTER = "obstacle_PRIMARY_CENTER"

        /** Every base cooldown [randomEvent]'s keys can have. */
        val BASE_COOLDOWNS = listOf(2_000L, 4_000L, 5_000L, 6_000L, 8_000L, 15_000L)
    }
}
