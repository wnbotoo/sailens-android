package com.sailens.guidance.evaluation

import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.evaluation.PromptPolicyEvaluation.LifecycleEvidence
import com.sailens.guidance.evaluation.PromptPolicyEvaluation.NearEntry
import com.sailens.guidance.model.common.DistanceLevel
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.common.UrgencyLevel
import com.sailens.guidance.model.perception.DetectedObstacle
import com.sailens.guidance.processor.decision.CooldownManager
import com.sailens.guidance.processor.decision.EventConflictResolver
import com.sailens.guidance.processor.decision.EventGenerator
import com.sailens.guidance.processor.decision.EventMerger
import com.sailens.guidance.trace.capture.RegressionUtteranceRecord
import com.sailens.guidance.trace.capture.UtteranceRecordEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * det-first plan D1.2 / D1.5 / D1.6 on hand-built frames: stage timing with failures counted,
 * ambiguous credit kept apart, what the lifecycle can and cannot say, and the NEAR entries a
 * same-priority cooldown holds.
 */
class PromptPolicyEvaluationTest {

    private val analysis = AnalysisConfig()
    private val evaluation = PromptPolicyEvaluation(analysis)
    private val generator = EventGenerator(analysis)
    private val o = EvaluationFixtures

    /**
     * One frame at [at] ms, decided as DecideEventsUseCase decides: by [cooldown] when given (a copy
     * of it taken first, as the replay does), else everything passes when [passes]. The offered event
     * is received unless [revoked], and a revoked one is taken back from the cooldown.
     */
    private fun frame(
        seq: Long,
        at: Long,
        obstacles: List<DetectedObstacle>,
        passes: Boolean = true,
        revoked: Boolean = false,
        blocked: Boolean = false,
        cooldown: CooldownManager? = null,
    ): PromptPolicyEvaluation.Frame {
        val snapshot = o.snapshot(obstacles, blocked = blocked)
        val generated = generator.generate(snapshot, at)
        val resolved = EventConflictResolver().resolve(generated)
        val merged = EventMerger().merge(resolved)
        val before = cooldown?.snapshot(at, stationary = false)
        val decided = when {
            cooldown != null -> cooldown.decide {
                setStationary(false)
                filter(merged, at).sortedByDescending { it.priority.value }.also { d -> d.firstOrNull()?.let { recordEvent(it, at) } }
            }
            passes -> merged.sortedByDescending { it.priority.value }
            else -> emptyList()
        }
        if (revoked) decided.firstOrNull()?.let { cooldown?.revoke(it.id) }
        val offeredId = decided.firstOrNull()?.id?.toString()
        return PromptPolicyEvaluation.Frame(
            seq = seq,
            decidedAt = at,
            obstacles = obstacles,
            groups = generator.announcedObstacleGroups(obstacles),
            generated = generated,
            resolved = resolved,
            merged = merged,
            decided = decided,
            cooldown = before,
            offeredId = offeredId,
            offeredReceived = offeredId != null && !revoked,
            pitchDownDegrees = 15f,
        )
    }

    private fun row(result: PromptPolicyEvaluation.Result, hazard: Int, stage: Stage) =
        result.stages.single { it.hazard == o.id(hazard).toString() && it.stage == stage }

    @Test
    fun `a stage appears before the cooldown, is received later, and a never-received one stays a failure`() {
        val person = o.obstacle(1, ObstacleCategory.PERSON)
        val other = o.obstacle(2, ObstacleCategory.PERSON, zone = com.sailens.guidance.model.common.DirectionZone.LEFT, box = com.sailens.core.geometry.NormalizedRect(0.02f, 0.5f, 0.2f, 0.4f))
        val frames = listOf(
            frame(1, 0, listOf(person), revoked = true),
            frame(2, 100, listOf(person), passes = false),
            frame(3, 200, listOf(person)),
            frame(4, 300, listOf(other), revoked = true),
        )

        val result = evaluation.evaluate(frames, LifecycleEvidence.NONE, credit = null, cameraRealtime = true)

        assertEquals(0L, row(result, 1, Stage.FIRST).appearedAt)
        assertEquals(200L, row(result, 1, Stage.FIRST).receivedAt)
        assertNull("offered once, revoked: a failure, not a gap", row(result, 2, Stage.FIRST).receivedAt)
        val text = PromptPolicyReport.render(listOf(result))
        assertTrue(text, text.contains("UNDETERMINABLE, not \"no failure\""))
        assertTrue(text, Regex("person/front\\s+FIRST\\s+1\\s+1 ").containsMatchIn(text))
        assertTrue(text, Regex("person/side\\s+FIRST\\s+1\\s+0 .*never/never").containsMatchIn(text))
    }

    @Test
    fun `ambiguous credit is listed apart and never counts`() {
        val person = o.obstacle(1, ObstacleCategory.PERSON, urgency = UrgencyLevel.HIGH)
        val chair = o.obstacle(
            2, ObstacleCategory.STATIC_OBSTACLE, urgency = UrgencyLevel.MEDIUM,
            box = com.sailens.core.geometry.NormalizedRect(0.4f, 0.6f, 0.3f, 0.35f),
        )

        val result = evaluation.evaluate(listOf(frame(1, 0, listOf(person, chair))), LifecycleEvidence.NONE, null, true)

        val chairFirst = row(result, 2, Stage.FIRST)
        assertTrue(chairFirst.ambiguousOnly)
        assertTrue(chairFirst.ambiguousReceived)
        assertNull(chairFirst.receivedAt)
        val text = PromptPolicyReport.render(listOf(result))
        assertTrue(text, Regex("static/front\\s+FIRST\\s+0\\s+0\\s+1 ").containsMatchIn(text))
    }

    @Test
    fun `completion comes from the lifecycle of the offered prompt, the earliest valid done`() {
        val person = o.obstacle(1, ObstacleCategory.PERSON)
        val f1 = frame(1, 0, listOf(person))
        val f2 = frame(2, 4_500, listOf(person))
        val records = listOf(
            utterance(UtteranceRecordEvents.SUBMITTED, f1.offeredId!!, 10_000_000),
            utterance(UtteranceRecordEvents.STOPPED, f1.offeredId!!, 400_000_000),
            utterance(UtteranceRecordEvents.SUBMITTED, f2.offeredId!!, 4_500_000_000),
            utterance(UtteranceRecordEvents.DONE, f2.offeredId!!, 5_900_000_000),
        )

        val result = evaluation.evaluate(listOf(f1, f2), LifecycleEvidence.RECORDED, CompletionCredit.of(records), true)

        assertEquals(0L, row(result, 1, Stage.FIRST).receivedAt)
        assertEquals("the interrupted first offer did not complete it", 5_900_000_000L, row(result, 1, Stage.FIRST).completedAtNanos)
        assertEquals(1, result.offersCompleted)
    }

    @Test
    fun `lead time uses completion when recorded, a labelled receipt proxy without, and nothing for a screen reader`() {
        fun approaching(seq: Long, at: Long, m: Float) = o.obstacle(1, ObstacleCategory.PERSON, measuredOn = seq, cameraNanos = at * 1_000_000, contactPointM = m)
        val frames = listOf(
            frame(1, 0, listOf(approaching(1, 0, 3.6f))),
            frame(2, 100, listOf(approaching(2, 100, 3.2f))),
            frame(3, 200, listOf(approaching(3, 200, 2.8f))),
        )

        val proxy = evaluation.evaluate(frames, LifecycleEvidence.NONE, null, cameraRealtime = true)
        val first3 = proxy.leads.single { it.stage == Stage.FIRST && it.thresholdM == 3.0f }.result
        assertEquals("received at 0 ms, crossing in [100, 200] ms", LeadTime.Outcome.BEFORE, first3.outcome)
        assertTrue(PromptPolicyReport.render(listOf(proxy)).contains("RECEIPT PROXY"))

        assertTrue(evaluation.evaluate(frames, LifecycleEvidence.NOT_MODELLABLE, null, true).leads.isEmpty())
        assertTrue("camera clock not realtime", evaluation.evaluate(frames, LifecycleEvidence.NONE, null, cameraRealtime = false).leads.isEmpty())

        val never = evaluation.evaluate(frames, LifecycleEvidence.RECORDED, CompletionCredit.of(emptyList()), true)
        assertEquals(
            "recorded, and never spoken to the end",
            LeadTime.Outcome.CLEARLY_MISSED,
            never.leads.single { it.stage == Stage.FIRST && it.thresholdM == 3.0f }.result.outcome,
        )
    }

    @Test
    fun `a NEAR entry held by a same-priority cooldown is counted, with what came of it`() {
        val cooldown = CooldownManager()
        val medium = o.obstacle(1, ObstacleCategory.PERSON, distance = DistanceLevel.MEDIUM, urgency = UrgencyLevel.HIGH)
        val near = medium.copy(distance = DistanceLevel.NEAR)
        val frames = listOf(
            frame(1, 0, listOf(medium), cooldown = cooldown), // "a person ahead", received
            frame(2, 1_000, listOf(near), cooldown = cooldown), // NEAR, same HIGH priority, still in the cooldown
            frame(3, 2_000, listOf(near), cooldown = cooldown),
        )
        assertTrue("the cooldown really held it", frames[1].decided.isEmpty())

        val result = evaluation.evaluate(frames, LifecycleEvidence.NONE, null, true)

        val entry = result.nearEntries.single()
        assertEquals(NearEntry.HELD_SAME_PRIORITY, entry.entry)
        assertFalse(entry.receivedLater)
        assertNull("no lifecycle: completion unknown, not false", entry.completedLater)
        assertTrue(PromptPolicyReport.render(listOf(result)).contains("held by a same-priority cooldown 1 (NEAR never received afterwards: 1)"))
    }

    @Test
    fun `a hold under a higher recorded priority is not counted as same-priority`() {
        // A CRITICAL car ahead is received; a person appears on the left, so a merged HIGH prompt passes
        // through the new zone; the person ahead turns NEAR while the centre key still records CRITICAL.
        val cooldown = CooldownManager()
        val car = o.obstacle(1, ObstacleCategory.VEHICLE, distance = DistanceLevel.MEDIUM, urgency = UrgencyLevel.HIGH)
        val person = o.obstacle(2, ObstacleCategory.PERSON, distance = DistanceLevel.MEDIUM, urgency = UrgencyLevel.HIGH)
        val left = o.obstacle(
            3, ObstacleCategory.PERSON, zone = com.sailens.guidance.model.common.DirectionZone.LEFT, urgency = UrgencyLevel.HIGH,
            box = com.sailens.core.geometry.NormalizedRect(0.02f, 0.5f, 0.2f, 0.4f),
        )
        val frames = listOf(
            frame(1, 0, listOf(car), cooldown = cooldown),
            frame(2, 500, listOf(person, left), cooldown = cooldown),
            frame(3, 1_000, listOf(person.copy(distance = DistanceLevel.NEAR), left), cooldown = cooldown),
        )
        assertEquals("the merged HIGH prompt passed through the new zone", 1, frames[1].decided.size)
        assertTrue("and the NEAR frame was held", frames[2].decided.isEmpty())

        val entry = evaluation.evaluate(frames, LifecycleEvidence.NONE, null, true).nearEntries.single { it.hazard == o.id(2).toString() }
        assertEquals(NearEntry.HELD_HIGHER_PRIORITY, entry.entry)
    }

    @Test
    fun `when a stage appears does not depend on what was offered or received`() {
        // The person is the primary ahead; a chair ahead is NEAR; a chair on the left makes the merged
        // prompt generic, so only the merged candidate speaks for the near chair.
        val person = o.obstacle(1, ObstacleCategory.PERSON, urgency = UrgencyLevel.CRITICAL)
        val chair = o.obstacle(
            2, ObstacleCategory.STATIC_OBSTACLE, distance = DistanceLevel.NEAR, urgency = UrgencyLevel.HIGH,
            box = com.sailens.core.geometry.NormalizedRect(0.4f, 0.6f, 0.3f, 0.35f),
        )
        val leftChair = o.obstacle(
            3, ObstacleCategory.STATIC_OBSTACLE, zone = com.sailens.guidance.model.common.DirectionZone.LEFT, urgency = UrgencyLevel.HIGH,
            box = com.sailens.core.geometry.NormalizedRect(0.02f, 0.5f, 0.25f, 0.45f),
        )
        val scene = listOf(person, chair, leftChair)
        fun run(vararg offeredAt: Boolean) = evaluation.evaluate(
            offeredAt.mapIndexed { i, offered -> frame(i + 1L, i * 1_000L, scene, passes = offered) },
            LifecycleEvidence.NONE, null, true,
        )
        fun appearances(result: PromptPolicyEvaluation.Result) =
            result.stages.map { Triple(it.hazard, it.stage, it.appearedAt) to it.ambiguousOnly }.toSet()

        val offeredFirst = run(true, true)
        val heldThenOffered = run(false, true)
        val neverOffered = run(false, false)

        assertEquals(appearances(offeredFirst), appearances(heldThenOffered))
        assertEquals(appearances(offeredFirst), appearances(neverOffered))
        assertEquals(0L, row(neverOffered, 2, Stage.NEAR).appearedAt)
        assertEquals("the same NEAR entries, whatever was offered", offeredFirst.nearEntries.map { it.hazard }, neverOffered.nearEntries.map { it.hazard })
    }

    @Test
    fun `results with different lifecycle evidence are reported apart, never pooled`() {
        fun approaching(seq: Long, at: Long, m: Float) = o.obstacle(1, ObstacleCategory.PERSON, measuredOn = seq, cameraNanos = at * 1_000_000, contactPointM = m)
        val frames = listOf(frame(1, 0, listOf(approaching(1, 0, 3.6f))), frame(2, 100, listOf(approaching(2, 100, 2.8f))))
        val proxy = evaluation.evaluate(frames, LifecycleEvidence.NONE, null, true)
        val recorded = evaluation.evaluate(frames, LifecycleEvidence.RECORDED, CompletionCredit.of(emptyList()), true)
        val incomplete = evaluation.evaluate(frames, LifecycleEvidence.INCOMPLETE, CompletionCredit.of(emptyList()), true)

        val text = PromptPolicyReport.render(listOf(proxy, recorded, incomplete))

        assertEquals(1, Regex("basis: completion\n").findAll(text).count())
        assertEquals(1, Regex("basis: RECEIPT PROXY").findAll(text).count())
        assertEquals(1, Regex("basis: completion, from an INCOMPLETE lifecycle").findAll(text).count())
        assertTrue(text, text.contains("REPORTED ONLY, never for acceptance"))
        assertTrue("each section counts its own hazards", Regex("person/front FIRST <=3.0m: n 1,").findAll(text).count() == 3)
    }

    @Test
    fun `NEAR entries that were offered or suppressed are told apart`() {
        val near = o.obstacle(1, ObstacleCategory.PERSON, distance = DistanceLevel.NEAR, urgency = UrgencyLevel.HIGH)
        assertEquals(NearEntry.OFFERED, evaluation.evaluate(listOf(frame(1, 0, listOf(near))), LifecycleEvidence.NONE, null, true).nearEntries.single().entry)
        assertEquals(
            "BLOCKED suppresses a central obstacle",
            NearEntry.SUPPRESSED,
            evaluation.evaluate(listOf(frame(1, 0, listOf(near), blocked = true)), LifecycleEvidence.NONE, null, true).nearEntries.single().entry,
        )
    }

    @Test
    fun `a trackless hazard is one episode across frames, received through any of its offers`() {
        val frames = listOf(
            frame(1, 0, emptyList(), blocked = true, revoked = true),
            frame(2, 500, emptyList(), blocked = true),
            frame(3, 3_000, emptyList(), blocked = true, revoked = true),
        )

        val result = evaluation.evaluate(frames, LifecycleEvidence.NONE, null, true)

        val rows = result.stages.filter { it.trackless }
        assertEquals(listOf("blocked#1", "blocked#2"), rows.map { it.hazard })
        assertEquals(500L, rows[0].receivedAt)
        assertNull(rows[1].receivedAt)
    }

    private fun utterance(event: String, id: String, at: Long) =
        RegressionUtteranceRecord(event = event, attemptId = id, logicalId = id, engineGeneration = 1, elapsedRealtimeNanos = at)
}
