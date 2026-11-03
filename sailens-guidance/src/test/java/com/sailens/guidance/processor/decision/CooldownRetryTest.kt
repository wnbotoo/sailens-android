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

class CooldownRetryTest {
    private val cooldown = CooldownManager(retryInterrupted = true)
    private val a = UUID(0, 1)
    private val b = UUID(0, 2)

    private fun id(vararg near: UUID) = near.toSet()

    private fun obstacle(t: Long, vararg hazards: UUID, near: Set<UUID> = emptySet()) = SceneEvent(
        timestamp = t,
        category = EventCategory.OBSTACLE,
        priority = EventPriority.HIGH,
        messageKey = SceneEventMessageKeys.obstacle(SceneEventMessageKeys.zonePart(DirectionZone.CENTER), SceneEventMessageKeys.SUFFIX_PERSON),
        expiresAt = t + 3_000,
        dedupeKey = "obstacle_CENTER",
        cooldownKeys = setOf("obstacle_CENTER"),
        relatedZones = listOf(DirectionZone.CENTER),
        distance = if (near.isEmpty()) DistanceLevel.MEDIUM else DistanceLevel.NEAR,
        components = listOf(
            EventComponent(
                DirectionZone.CENTER, EventPriority.HIGH, hazards.first(), setOf("obstacle_CENTER"),
                hazards.map { HazardRef(it, ObstacleCategory.PERSON, it in near, it in near, EventPriority.HIGH, ObstacleSource.DETECTION) },
            ),
        ),
    )

    private fun state(event: SceneEvent, track: UUID = a, stage: PromptCredit.Stage = PromptCredit.Stage.FIRST) =
        cooldown.canonicalState().retries[PromptStageKey(track, null, stage)]

    private fun passes(event: SceneEvent) = cooldown.filter(listOf(event), event.timestamp).isNotEmpty()

    private fun taken(event: SceneEvent): Boolean {
        cooldown.recordEvent(event, event.timestamp)
        return cooldown.promptAccepted(event.id)
    }

    @Test
    fun `cut off once it is taken back and offered again, heard off the second time it is not`() {
        val first = obstacle(0, a)
        assertTrue(taken(first))
        assertEquals(CutResult.REVOKED, cooldown.reportCut(first.id).result)
        assertEquals(RetryState.AWAITING_REGIVE, state(first))
        assertTrue("as if never said", passes(obstacle(100, a)))

        val again = obstacle(1_000, a)
        assertFalse("its rhythm played the first time", taken(again))
        assertEquals(RetryState.REGIVEN, state(again))
        assertEquals(CutResult.KEPT, cooldown.reportCut(again.id).result)
        assertEquals(RetryState.EXHAUSTED, state(again))
        assertFalse("kept, so its cooldown stands", passes(obstacle(1_100, a)))
    }

    @Test
    fun `a refusal between the cut and the retry does not use the retry up`() {
        val first = obstacle(0, a)
        taken(first)
        cooldown.reportCut(first.id)

        val refused = obstacle(500, a)
        cooldown.recordEvent(refused, 500)
        cooldown.revoke(refused.id)
        assertEquals(RetryState.AWAITING_REGIVE, state(refused))

        val again = obstacle(1_000, a)
        assertFalse(taken(again))
        assertEquals(RetryState.REGIVEN, state(again))
    }

    @Test
    fun `A, A and B, A and B with A the primary, one retry for A, and the second cut is kept`() {
        val one = obstacle(0, a)
        taken(one)
        assertEquals(CutResult.REVOKED, cooldown.reportCut(one.id).result)

        val two = obstacle(1_000, a, b)
        assertTrue("B is new, so its rhythm plays", taken(two))
        assertEquals(CutResult.KEPT, cooldown.reportCut(two.id).result)
        assertEquals(RetryState.EXHAUSTED, state(two, track = a))
        assertEquals(RetryState.EXHAUSTED, state(two, track = b))

        val three = obstacle(9_000, a, b)
        assertTrue(taken(three))
        assertEquals(CutResult.KEPT, cooldown.reportCut(three.id).result)
    }

    @Test
    fun `FIRST already said again and NEAR still new, not taken back, NEAR loses its retry, and today's rule says it later`() {
        val one = obstacle(0, a)
        taken(one)
        cooldown.reportCut(one.id)
        val again = obstacle(1_000, a)
        taken(again)
        cooldown.confirm(again.id)

        val near = obstacle(2_000, a, near = id(a))
        assertTrue(taken(near))
        assertEquals(CutResult.KEPT, cooldown.reportCut(near.id).result)
        assertEquals(RetryState.EXHAUSTED, state(near, stage = PromptCredit.Stage.NEAR))
        assertFalse(passes(obstacle(3_000, a, near = id(a))))
        assertTrue(passes(obstacle(10_000, a, near = id(a))))
    }

    @Test
    fun `the rhythm goes by the state before the change`() {
        val cut = obstacle(0, a)
        taken(cut)
        cooldown.reportCut(cut.id)
        assertFalse("every stage being said again", taken(obstacle(1_000, a)))

        val other = CooldownManager(retryInterrupted = true)
        val first = obstacle(0, a)
        other.recordEvent(first, 0)
        other.promptAccepted(first.id)
        other.reportCut(first.id)
        val mixed = obstacle(1_000, a, b)
        other.recordEvent(mixed, 1_000)
        assertTrue("B is new", other.promptAccepted(mixed.id))

        val fresh = CooldownManager(retryInterrupted = true)
        val new = obstacle(0, a)
        fresh.recordEvent(new, 0)
        assertTrue(fresh.promptAccepted(new.id))
    }

    @Test
    fun `a stage's state goes when its track leaves`() {
        val cut = obstacle(0, a)
        taken(cut)
        cooldown.reportCut(cut.id)
        cooldown.observeHazards(setOf(a), emptyList(), 100)
        assertEquals(RetryState.AWAITING_REGIVE, state(cut))
        cooldown.observeHazards(setOf(b), emptyList(), 200)
        assertEquals(null, state(cut))
    }

    private fun blocked(t: Long) = SceneEvent(
        timestamp = t, category = EventCategory.BLOCKED, priority = EventPriority.CRITICAL, messageKey = SceneEventMessageKeys.BLOCKED,
        expiresAt = t + 5_000, dedupeKey = "blocked",
    )

    @Test
    fun `a state's episode runs while the gaps stay within a second, and an outcome stays with the episode it was said in`() {
        cooldown.observeHazards(emptySet(), listOf(blocked(0)), 0)
        val old = blocked(0)
        taken(old)
        cooldown.observeHazards(emptySet(), listOf(blocked(900)), 900)
        cooldown.observeHazards(emptySet(), listOf(blocked(2_500)), 2_500)
        assertEquals(2, cooldown.canonicalState().episodes.getValue("blocked").number)

        // The old prompt's receipt comes in the new episode: it still speaks of the one it was said in.
        assertEquals(CutResult.REVOKED, cooldown.reportCut(old.id).result)
        assertEquals(RetryState.AWAITING_REGIVE, cooldown.canonicalState().retries[PromptStageKey(null, "blocked#1", PromptCredit.Stage.FIRST)])
        cooldown.observeHazards(emptySet(), listOf(blocked(2_600)), 2_600)
        assertEquals("ended, so it goes", emptyMap<PromptStageKey, RetryState>(), cooldown.canonicalState().retries)
    }

    @Test
    fun `a prompt that speaks for no stage is not said again`() {
        val merged = obstacle(0, a).copy(messageKey = SceneEventMessageKeys.obstacle(SceneEventMessageKeys.ZONES_MULTIPLE, null))
        taken(merged)
        assertEquals(CutResult.KEPT, cooldown.reportCut(merged.id).result)
    }

    @Test
    fun `a write forced out by the limit before its cut is kept, with its stages`() {
        val first = obstacle(0, a)
        taken(first)
        for (i in 1..20) cooldown.recordEvent(obstacle(i * 100L, b), i * 100L)
        assertEquals(CutResult.KEPT, cooldown.reportCut(first.id).result)
        assertEquals(RetryState.EXHAUSTED, state(first))
    }

    @Test
    fun `off, nothing here runs`() {
        val off = CooldownManager()
        val event = obstacle(0, a)
        off.recordEvent(event, 0)
        off.observeHazards(emptySet(), listOf(blocked(0)), 0)
        assertTrue(off.promptAccepted(event.id))
        assertEquals(emptyMap<String, CooldownManager.Episode>(), off.canonicalState().episodes)
        assertEquals(emptyMap<Long, List<PromptStageKey>>(), off.canonicalState().awaitingOutcome)
    }

    @Test
    fun `a copy goes on as the original does`() {
        val event = obstacle(0, a)
        taken(event)
        cooldown.reportCut(event.id)
        val copy = cooldown.copy()
        assertEquals(cooldown.canonicalState(), copy.canonicalState())
    }
}
