package com.sailens.shell.sim

import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.trace.PromptDeliveryChannels
import com.sailens.guidance.safety.BaselineQualification
import com.sailens.guidance.safety.CapabilityStatus
import com.sailens.guidance.safety.DegradeReason
import com.sailens.guidance.safety.pausedEventCategories
import com.sailens.guidance.sim.Pose
import com.sailens.guidance.sim.Scenario
import com.sailens.guidance.sim.ScenarioFrame
import com.sailens.guidance.sim.Scenarios
import com.sailens.guidance.sim.SimulatedDepthModel
import com.sailens.guidance.sim.SimulatedMetricDepth
import com.sailens.guidance.sim.World
import com.sailens.guidance.world.CurrentCellQuery
import com.sailens.guidance.world.SurfaceEvidence
import com.sailens.shell.guidance.settings.GuidanceFeedbackSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.HexFormat

class LocalWorldSessionTest {
    @Test
    fun `depth cannot change a readable world and delivered risk`() {
        val script = SessionScenarios.personStaysAhead
        val without = readSession(script)
        assertTrue(without.frames.values.any { it.surface })
        assertTrue(without.frames.values.any { it.rawConflict })
        assertRepeatedPersonPrompt(without.session)

        for (model in listOf(SimulatedDepthModel(), SimulatedDepthModel(metric = SimulatedMetricDepth()))) {
            val with = readSession(script, model)
            assertTrue(with.session.depthInferences > 0)
            assertTrue("no fitted reading reached the live world policy for $model", with.session.rejectedAvailableDepth.isNotEmpty())
            assertRepeatedPersonPrompt(with.session)
            assertEquals(without.session.lines, with.session.lines)
            assertEquals(without.session.ledger, with.session.ledger)
            assertEquals(without.frames, with.frames)
        }
    }

    @Test
    fun `a rejected reading cannot mask the same track moving into higher urgency or its delivery`() {
        val script = SessionScript(
            name = "person_moves_from_side_to_centre",
            description = "A stable person moves from the side into the central corridor.",
            scenario = Scenario(
                "person_moves_from_side_to_centre", "",
                List(50) { i ->
                    val x = -1.2 + ((i - 10).coerceIn(0, 30) * .04)
                    ScenarioFrame(Pose(Scenarios.HEIGHT, Scenarios.PITCH), World(Scenarios.street(),
                        listOf(Scenarios.personAt(x, 1.8))))
                },
                stepMs = 100L, alignClockToFrames = true,
            ),
            actions = SessionScript.defaultActions(ScriptAction.Stop(6_100)),
            endAtMs = 6_300,
        )
        val without = readSession(script)
        val with = readSession(script, SimulatedDepthModel())
        for (run in listOf(without.session, with.session)) {
            val decisions = run.entries.filterIsInstance<TimelineEntry.Frame>()
                .mapNotNull { frame -> frame.events.firstOrNull()?.takeIf { it.category == EventCategory.OBSTACLE }?.let { frame to it } }
            val medium = decisions.singleOrNull { it.first.seq == 14L && it.second.priority == EventPriority.MEDIUM }
            val high = decisions.singleOrNull { it.first.seq == 19L && it.second.priority >= EventPriority.HIGH }
            val events = decisions.map { (frame, event) -> "${frame.atMs} ${event.priority} ${event.messageKey}" }
            checkNotNull(medium) { "no medium primary: $events" }
            checkNotNull(high) { "designated frame must upgrade the same track: $events" }
            val track = checkNotNull(medium.second.components.single().primaryTrackId)
            assertEquals(track, high.second.components.single().primaryTrackId)
            if (run === with.session) {
                assertTrue("a fitted depth key must be rejected just before the upgrade", high.first.seq - 1 in run.rejectedAvailableDepth)
                assertTrue("a fitted depth key must be rejected on the upgrade", high.first.seq in run.rejectedAvailableDepth)
            }
            val eventId = high.second.id.toString()
            val outcome = checkNotNull(
                run.entries.filterIsInstance<TimelineEntry.Outcome>().singleOrNull { it.trace.eventId == eventId },
            ) { "decided high-risk event did not reach output" }
            assertEquals(high.first.seq, outcome.trace.sourceSequenceNumber)
            assertTrue(outcome.trace.deliveredVia.isNotEmpty())
            assertTrue(run.entries.filterIsInstance<TimelineEntry.Receipt>().any { it.kind == "confirm" && it.eventId == eventId })
        }
        assertEquals(without.session.lines, with.session.lines)
        assertEquals(without.frames, with.frames)
    }

    @Test
    fun `reacquired analysis needs its own proof during real monitor recovery`() {
        val script = SessionScenarios.backgroundAndBack
        val result = readSession(script)
        val before = result.frames.filterKeys { script.frameAtMs((it - 1).toInt()) in 2000..7500 }
        assertTrue(before.values.any { it.surface })
        val first = result.frames.entries.first { script.frameAtMs((it.key - 1).toInt()) >= 13300 }
        assertTrue(first.value.observed)
        assertTrue(!first.value.surface)
        assertTrue(result.frames.entries.any {
            script.frameAtMs((it.key - 1).toInt()) >= 13800 && it.value.surface
        })
        val withDepth = readSession(script, SimulatedDepthModel(metric = SimulatedMetricDepth()))
        assertTrue(first.key in withDepth.session.rejectedAvailableDepth)
        for (run in listOf(result.session, withDepth.session)) {
            val held = run.entries.filterIsInstance<TimelineEntry.State>().single { it.atMs == 13_350L && it.trace.cause == "result" }
            assertTrue(held.trace.hold != null && held.trace.holdReleased == null)
            val release = run.entries.filterIsInstance<TimelineEntry.State>().single { it.trace.holdReleased != null }
            assertEquals("the real monitor must release on the same recovery frame", 13_850L, release.atMs)
        }
        assertEquals(result.frames, withDepth.frames)
        assertEquals(result.session.lines, withDepth.session.lines)
    }

    @Test
    fun `a rejected measurement preserves queued risk expiry and motor receipts`() {
        val script = SessionScenarios.personStaysAhead.copy(
            settings = GuidanceFeedbackSettings(speechEnabled = false, hapticsEnabled = true),
        )
        for (expiresIn in listOf(149L, 150L, 151L)) {
            val without = readSession(script, queuedRiskExpiryMs = expiresIn)
            val with = readSession(script, SimulatedDepthModel(), queuedRiskExpiryMs = expiresIn)
            assertTrue(3L in with.session.rejectedAvailableDepth)
            assertTrue(4L in with.session.rejectedAvailableDepth)
            for (run in listOf(without.session, with.session)) {
                val queued = run.entries.filterIsInstance<TimelineEntry.Motor>().first { it.kind == "queue" }
                assertEquals(1_250L, queued.atMs)
                assertEquals("queued", queued.result)
                val first = run.entries.filterIsInstance<TimelineEntry.Frame>().single { it.seq == 3L }.events.first()
                val outcome = run.entries.filterIsInstance<TimelineEntry.Outcome>().single { it.trace.eventId == first.id.toString() }
                assertEquals("queued risk changed expiry", 1_400L, outcome.atMs)
                val receipts = run.entries.filterIsInstance<TimelineEntry.Receipt>()
                if (expiresIn < 150L) {
                    assertTrue(outcome.trace.deliveredVia.isEmpty())
                    assertTrue(receipts.any { it.eventId == first.id.toString() && it.kind == "revoke" })
                    val reoffered = run.entries.filterIsInstance<TimelineEntry.Outcome>().first { it.atMs > outcome.atMs }
                    assertEquals(first.messageKey, reoffered.trace.messageKey)
                    assertTrue(receipts.any { it.eventId == reoffered.trace.eventId && it.kind == "confirm" })
                } else {
                    assertEquals("live queued risk must reach the motor", listOf(PromptDeliveryChannels.HAPTICS), outcome.trace.deliveredVia)
                    assertTrue(receipts.any { it.eventId == first.id.toString() && it.kind == "confirm" })
                }
            }
            assertEquals(without.session.lines, with.session.lines)
        }
    }

    @Test
    fun `route pause and delivery survive real depth expiry and recovery`() {
        val script = SessionScenarios.descriptionHoldsFloor
        val baseline = readSession(script)
        val with = readSession(script, SimulatedDepthModel(metric = SimulatedMetricDepth(holdFrames = 5),
            omittedDeliveries = 30L..64L))
        val keyHistory = with.session.rejectedAvailableDepth
        val before = keyHistory.filterKeys { it < 30 }
        val after = keyHistory.filterKeys { it > 64 }
        assertTrue(before.entries.zipWithNext().any { (first, next) -> next.key == first.key + 1 && next.value == first.value })
        val stale = with.session.worldDepthStatus[56L]
        assertTrue("the real lane must expire its last measurement", stale is CapabilityStatus.Unavailable && "stale" in stale.issues)
        assertTrue(56L !in keyHistory)
        val initialKey = before.values.last()
        val recoveredKey = after.values.first()
        assertEquals(initialKey.sourceEpoch, recoveredKey.sourceEpoch)
        assertTrue(recoveredKey.measurement > initialKey.measurement)
        assertEquals(CapabilityStatus.Available, with.session.worldDepthStatus[after.keys.first()])
        for (run in listOf(baseline.session, with.session)) {
            val frames = run.entries.filterIsInstance<TimelineEntry.Frame>()
            val ground = DegradeReason.GROUND_UNRECOGNIZED
            val paused = BaselineQualification.Degraded(setOf(ground))
            val firstPause = frames.first { it.baseline == paused }
            assertEquals("the unrecognized floor must pause route judgement", 6_550L, firstPause.atMs)
            assertTrue(frames.filter { it.baseline == paused }.all { frame ->
                frame.events.none { it.category in ground.pausedEventCategories }
            })
            val delivered = run.entries.filterIsInstance<TimelineEntry.Outcome>()
                .first { it.trace.messageKey == "event_obstacle_center_person" && it.trace.deliveredVia.isNotEmpty() }
            assertTrue(delivered.atMs > firstPause.atMs)
            assertTrue(run.entries.filterIsInstance<TimelineEntry.Receipt>().any {
                it.kind == "confirm" && it.eventId == delivered.trace.eventId
            })
        }
        assertEquals(baseline.session.lines, with.session.lines)
        assertEquals(baseline.frames, with.frames)
    }

    private fun assertRepeatedPersonPrompt(run: SessionRun) {
        val key = "event_obstacle_center_person"
        val offers = run.entries.filterIsInstance<TimelineEntry.Outcome>().filter { it.trace.messageKey == key }
        assertEquals(listOf(1_250L, 9_250L), offers.map { it.atMs })
        val first = run.entries.filterIsInstance<TimelineEntry.Frame>().first { frame -> frame.events.any { it.messageKey == key } }
        val event = first.events.single { it.messageKey == key }
        assertEquals(4_250L, first.atMs + event.expiresAt - event.timestamp)
        val confirmed = run.entries.filterIsInstance<TimelineEntry.Receipt>().filter { it.kind == "confirm" }.map { it.eventId }
        assertTrue(offers.all { it.trace.deliveredVia.isNotEmpty() && it.trace.eventId in confirmed })
    }

    private fun readSession(
        script: SessionScript,
        depth: SimulatedDepthModel? = null,
        queuedRiskExpiryMs: Long? = null,
    ): Observations {
        val frames = linkedMapOf<Long, FrameCells>()
        val ids = HashMap<String, Int>()
        val hash = MessageDigest.getInstance("SHA-256")
        val session = SessionSimulator.run(script, depth = depth, queuedRiskExpiryMs = queuedRiskExpiryMs) { seq, reader ->
            var observed = false
            var surface = false
            var rawConflict = false
            for (index in 0 until 2400) {
                val cell = reader.currentCell(index / 40, index % 40)
                if (cell is CurrentCellQuery.Observed) {
                    observed = true
                    surface = surface || cell.evidence.surface == SurfaceEvidence.SEM_SURFACE
                    rawConflict = rawConflict || cell.conflicts.imageDetections.isNotEmpty()
                }
                val trace = UUID.replace(cell.toString()) { value -> "id" + ids.getOrPut(value.value) { ids.size } }
                hash.update(trace.toByteArray(Charsets.UTF_8))
                hash.update(0.toByte())
            }
            frames[seq] = FrameCells(observed, surface, rawConflict, HexFormat.of().formatHex(hash.digest()))
        }
        return Observations(session, frames)
    }

    private data class FrameCells(val observed: Boolean, val surface: Boolean, val rawConflict: Boolean, val fingerprint: String)

    private class Observations(val session: SessionRun, val frames: Map<Long, FrameCells>)

    private companion object {
        val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
