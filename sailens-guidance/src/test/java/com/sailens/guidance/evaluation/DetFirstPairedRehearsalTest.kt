package com.sailens.guidance.evaluation

import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.config.PerceptionConfig
import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.model.common.DirectionZone
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.scene.SceneEventMessageKeys
import com.sailens.guidance.processor.decision.CooldownManager
import com.sailens.guidance.processor.decision.EventConflictResolver
import com.sailens.guidance.processor.decision.EventGenerator
import com.sailens.guidance.processor.decision.EventMerger
import com.sailens.guidance.repository.DeviceSensorRepository
import com.sailens.guidance.semantics.DefaultNavigationSemanticsProvider
import com.sailens.guidance.trace.capture.CaptureSchema
import com.sailens.guidance.trace.capture.CaptureSession
import com.sailens.guidance.trace.capture.CaptureSessionReader
import com.sailens.guidance.trace.capture.RegressionFrameRecord
import com.sailens.guidance.trace.capture.orThrow
import com.sailens.guidance.trace.regression.GroundContactEvaluation
import com.sailens.guidance.trace.regression.RegressionReplay
import com.sailens.guidance.usecase.decision.DecideEventsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * The paired reading of M5 ([PairedBranchRun]) against stand-in arms, on real captures.
 * None of the arms is a real policy: they exist to show what the reading does with no change, with a
 * change that adds sentences, and with changes that must be caught. Skipped unless a path is given, as
 * [DetFirstRehearsalTest]; the report goes to `sailens-guidance/build/replay/detfirst-paired.txt`.
 */
class DetFirstPairedRehearsalTest {

    /** A stand-in for an experimental arm. Nothing downstream is told where it differs from today's policy. */
    private abstract class StandIn(val name: String) {
        /** Sees every frame once, before any run. */
        open fun prepare(scene: RegressionReplay.SceneFrame) = Unit

        /** A sentence of its own for this frame, when today's policy has none. */
        open fun extra(scene: RegressionReplay.SceneFrame, durations: SpeakerDurations): SpeakerModel.Offer? = null

        /** Today's prompt is withheld. */
        open fun withholds(primary: SceneEvent): Boolean = false
    }

    private class NoChange : StandIn("no change")

    /**
     * What the metric gate will do to the speaker: a person or vehicle ahead is named once, earlier than
     * today's bottom-of-box gate, at the priority its first prompt would have. It leaves no cooldown
     * record and credits no stage, both of which the real gate will.
     */
    private class EarlyAhead : StandIn("early first prompt for a person or vehicle ahead") {
        private val seen = mutableSetOf<String>()
        private val firing = HashMap<Long, Pair<String, Int>>()

        override fun prepare(scene: RegressionReplay.SceneFrame) {
            for (o in scene.snapshot.obstacles) {
                if (o.zone != DirectionZone.CENTER || !o.isStable(3) || o.confidence < 0.55f) continue
                val gate = when (o.category) {
                    ObstacleCategory.PERSON -> 0.56f
                    ObstacleCategory.VEHICLE -> 0.55f
                    else -> continue
                }
                if (o.boundingBox.maxY !in EARLY_BOTTOM_Y..gate || !seen.add(o.id.toString())) continue
                val person = o.category == ObstacleCategory.PERSON
                val suffix = if (person) SceneEventMessageKeys.SUFFIX_PERSON else SceneEventMessageKeys.SUFFIX_VEHICLE
                val priority = if (person) EventPriority.HIGH else EventPriority.CRITICAL
                firing[scene.record.seq] = SceneEventMessageKeys.obstacle(SceneEventMessageKeys.zonePart(DirectionZone.CENTER), suffix) to priority.value
            }
        }

        override fun extra(scene: RegressionReplay.SceneFrame, durations: SpeakerDurations): SpeakerModel.Offer? {
            val (key, priority) = firing[scene.record.seq] ?: return null
            return SpeakerModel.Offer(scene.decidedAt, "early|${scene.record.seq}", priority, scene.decidedAt + 3_000, durations.of(key to false).second)
        }
    }

    /** Must be caught: every vehicle prompt withheld. */
    private class MutedVehicles : StandIn("every vehicle prompt withheld") {
        override fun withholds(primary: SceneEvent) = SceneEventMessageKeys.obstacleSuffix(primary.messageKey) == SceneEventMessageKeys.SUFFIX_VEHICLE
    }

    /** Must be caught: a top-priority sentence every ten seconds, cutting whatever is speaking. */
    private class Chatter : StandIn("a top-priority sentence every 10 s") {
        private var next = Long.MIN_VALUE
        private val firing = mutableSetOf<Long>()

        override fun prepare(scene: RegressionReplay.SceneFrame) {
            if (scene.decidedAt < next) return
            next = scene.decidedAt + 10_000
            firing += scene.record.seq
        }

        override fun extra(scene: RegressionReplay.SceneFrame, durations: SpeakerDurations): SpeakerModel.Offer? {
            if (scene.record.seq !in firing) return null
            return SpeakerModel.Offer(scene.decidedAt, "chatter|${scene.record.seq}", EventPriority.CRITICAL.value, scene.decidedAt + 3_000, 1_500)
        }
    }

    private class ArmResult(val nominal: PairedBranchRun.Result, val full: PairedBranchRun.Result, val nanos: Long)

    private class CaptureResult(
        val id: String,
        val legacy: ProtectedStages.Reproduction,
        val device: PromptPolicyEvaluation.Result,
        val deadlines: Map<StageKey, Long>,
        /** First completion of each stage of P with no error, on the path the branch run itself takes. */
        val nominalFirst: PairedBranchRun.Completions,
        val samples: Map<String, List<LeadTime.Sample>>,
        val arms: Map<String, ArmResult>,
    )

    private data class Counted(val regressions: Int, val regressionsFront: Int, val undeterminable: Int, val undeterminableFront: Int, val fragile: Int, val fragileFront: Int, val same: Int) {
        val total get() = regressions + undeterminable
        val totalFront get() = regressionsFront + undeterminableFront

        operator fun plus(o: Counted) = Counted(regressions + o.regressions, regressionsFront + o.regressionsFront, undeterminable + o.undeterminable, undeterminableFront + o.undeterminableFront, fragile + o.fragile, fragileFront + o.fragileFront, same + o.same)

        override fun toString() = "counted $total (ahead $totalFront): regressions $regressions (ahead $regressionsFront), " +
            "undeterminable $undeterminable (ahead $undeterminableFront); reported only: fragile $fragile (ahead $fragileFront), same inputs $same"
    }

    private fun counted(result: PairedBranchRun.Result, legacy: ProtectedStages.Reproduction): Counted {
        val regressions = legacy.m5Of(result.regressions)
        val apart = legacy.m5Of(result.bothLostApart)
        val stopped = legacy.m5Of(result.undeterminable)
        return Counted(
            regressions.regressions + regressions.undeterminable,
            regressions.regressionsFront + regressions.undeterminableFront,
            apart.undeterminable + stopped.regressions + stopped.undeterminable,
            apart.undeterminableFront + stopped.regressionsFront + stopped.undeterminableFront,
            apart.regressions,
            apart.regressionsFront,
            result.sameInputs.size,
        )
    }

    /** How one side of one arm does on the M1, M2 and B5 lines, over the reference queue (R9). */
    private data class Lines(val spoken: Map<String, Int>, val of: Map<String, Int>) {
        operator fun plus(o: Lines) = Lines(
            (spoken.keys + o.spoken.keys).associateWith { (spoken[it] ?: 0) + (o.spoken[it] ?: 0) },
            (of.keys + o.of.keys).associateWith { (of[it] ?: 0) + (o.of[it] ?: 0) },
        )

        fun render(): String = of.keys.sorted().joinToString("; ") { "$it ${spoken[it] ?: 0}/${of[it]}" }
    }

    private fun lines(r: CaptureResult, side: PairedBranchRun.Completions): Lines {
        val spoken = HashMap<String, Int>()
        val of = HashMap<String, Int>()
        fun count(line: String, hit: Boolean) {
            of.merge(line, 1, Int::plus)
            if (hit) spoken.merge(line, 1, Int::plus)
        }
        for (row in r.device.stages) {
            if (row.ambiguousOnly) continue
            val key = StageKey(row.hazard, row.stage)
            if (row.stage == Stage.NEAR && !row.trackless) {
                count("B3 near all", key in side.everywhere)
                if (row.hazardClass == "person/front") count("B1 near person", key in side.everywhere)
                if (row.hazardClass == "vehicle/front") count("B2 near vehicle", key in side.everywhere)
            }
            if (row.stage == Stage.CRITICAL && row.hazardClass == "person/front") count("B5 critical person", key in side.everywhere)
            if (row.stage == Stage.CRITICAL && row.hazardClass == "vehicle/front") count("B5 critical vehicle", key in side.everywhere)
        }
        for (lead in r.device.leads) {
            if (lead.stage != Stage.FIRST || lead.thresholdM != M1_THRESHOLD_M) continue
            val name = when (lead.hazardClass) {
                "person/front" -> "person"
                "vehicle/front" -> "vehicle"
                else -> continue
            }
            val completion = side.completionMs(StageKey(lead.hazard, Stage.FIRST))
            val outcome = LeadTime.classify(r.samples[lead.hazard].orEmpty(), M1_THRESHOLD_M, completion?.let { it * 1_000_000 }).outcome
            if (outcome == LeadTime.Outcome.UNKNOWABLE) continue
            count("C1-2 before, $name", outcome == LeadTime.Outcome.BEFORE)
            count("C3 clearly missed, $name", outcome == LeadTime.Outcome.CLEARLY_MISSED)
        }
        return Lines(spoken, of)
    }

    @Test
    fun `the paired reading of M5 on recorded captures`() {
        val path = System.getProperty("sailens.replay.capture").orEmpty()
        assumeTrue("set -PreplayCapture=<capture dir> to rehearse on captures", path.isNotBlank())
        val out = File(System.getProperty("sailens.replay.out").orEmpty().ifBlank { "build/replay" }).apply { mkdirs() }
        val split = SealedCaptures.forMetrics(File(path))
        assumeTrue(SealedCaptures.refusalMessage(path, split.sealed.size), split.open.isNotEmpty())

        val preset = PhoneGeometrySettings(PRESET_HEIGHT_M, HeightSource.PRESET)
        val frozen = PromptPolicyCaptureEvaluation(preset)
        val evaluated = split.open.mapNotNull { dir ->
            frozen.evaluate(CaptureSessionReader.read(dir).orThrow())?.takeIf { D18Sets.of(it.id) == D18Sets.Group.CALIBRATION }?.let { dir to it }
        }
        val measured = evaluated.associate { (_, r) -> r.id to r.speaker?.measured.orEmpty() }
        val results = mutableListOf<CaptureResult>()
        val started = System.currentTimeMillis()
        for ((dir, result) in evaluated) {
            val durations = SpeakerDurations.pooled(measured.filterKeys { it != result.id }.values.flatten(), OTHER_C)
            results += rehearse(CaptureSessionReader.read(dir).orThrow(), result, durations, preset)
            File(out, "detfirst-paired.txt").writeText("${results.size} of ${evaluated.size} captures, ${(System.currentTimeMillis() - started) / 60_000} min\n" + report(results))
        }
        println(report(results))
        assertTrue(results.isNotEmpty())
        val failed = standingChecks(results)
        assertTrue("standing checks failed:\n${failed.joinToString("\n")}", failed.isEmpty())
    }

    /** Stages of P kept by one and not the other, and kept by both more than 1 ms apart. */
    private fun nominalAgainstFrozen(results: List<CaptureResult>): Pair<Int, Int> {
        var disagree = 0
        var offByMoreThan1Ms = 0
        for (r in results) {
            for ((key, frozenNs) in r.legacy.modelCompletionNs()) {
                val deadline = r.deadlines.getValue(key)
                val frozenKept = frozenNs != null && frozenNs / 1_000_000 <= deadline
                val nominal = r.nominalFirst.completionMs(key)
                if (frozenKept != (nominal != null && nominal <= deadline)) disagree++
                if (frozenNs != null && nominal != null && abs(frozenNs / 1_000_000 - nominal) > 1) offByMoreThan1Ms++
            }
        }
        return disagree to offByMoreThan1Ms
    }

    /** What must hold for this rehearsal to say anything at all. A passing test means these held. */
    private fun standingChecks(results: List<CaptureResult>): List<String> = buildList {
        val (disagree, off) = nominalAgainstFrozen(results)
        if (disagree != 0 || off != 0) add("the nominal path differs from the frozen nominal model: $disagree stages, $off beyond 1 ms")
        val limit = maxOf(2, results.sumOf { it.legacy.p.size } / 100)
        fun total(name: String) = results.map { counted(it.arms.getValue(name).full, it.legacy) }.reduce(Counted::plus)
        val noChange = NoChange().name
        val unchanged = total(noChange)
        if (unchanged.total + unchanged.fragile != 0) add("an arm that changes nothing scored: $unchanged")
        for (r in results) {
            val full = r.arms.getValue(noChange).full
            if (full.capHit) add("${r.id}: an arm that changes nothing did not settle")
            if (full.baseline.everywhere != full.arm.everywhere || full.baseline.latestEndMs != full.arm.latestEndMs) add("${r.id}: an arm that changes nothing answers its stages differently from the baseline")
            if (r.arms.values.any { it.nominal.maxBranches != 1 }) add("${r.id}: with no error there is more than one branch")
            val late = r.arms.values.sumOf { it.full.creditedAfterClose.size + it.nominal.creditedAfterClose.size }
            if (late != 0) add("${r.id}: $late stages were offered for after their watches closed")
            for ((name, other) in r.arms) {
                if (other.full.capHit) continue
                if (other.full.baseline.everywhere != full.baseline.everywhere || other.full.baseline.latestEndMs != full.baseline.latestEndMs) add("${r.id}: the baseline answers its stages differently when paired with $name")
            }
        }
        for (name in listOf(MutedVehicles().name, Chatter().name)) {
            val caught = total(name)
            if (caught.total <= limit) add("$name must be caught, but scored: $caught")
        }
    }

    private fun report(results: List<CaptureResult>): String {
        return buildString {
            val p = results.sumOf { it.legacy.p.size }
            val limit = maxOf(2, p / 100)
            appendLine("Paired M5 over ${results.size} calibration captures, legacy output path, $PRESET_HEIGHT_M m; both arms decide for themselves on every frame")
            appendLine("M5 limit: at most $limit of |P| = $p; ahead CRITICAL and blocked: 0. Stop at $MAX_BRANCHES branches or $MAX_BRANCH_FRAMES branch-frames.")
            appendLine()
            appendLine("The path with no error against the frozen nominal model (stages of P):")
            val (disagree, offByMoreThan1Ms) = nominalAgainstFrozen(results)
            appendLine("  stages kept by one and not the other: $disagree; kept by both with first completion more than 1 ms apart: $offByMoreThan1Ms")
            for (name in results.first().arms.keys) {
                appendLine()
                appendLine(name)
                val rows = results.map { it to it.arms.getValue(name) }
                val settled = rows.count { !it.second.full.capHit }
                appendLine("  settled $settled of ${rows.size}, highest branches ${rows.maxOf { it.second.full.maxBranches }}, branch-frames ${rows.sumOf { it.second.full.branchFrames }}, slowest capture ${rows.maxOf { it.second.nanos } / 1_000_000_000} s, all ${rows.sumOf { it.second.nanos } / 1_000_000_000} s")
                appendLine("  no error:      ${rows.map { (r, a) -> counted(a.nominal, r.legacy) }.reduce(Counted::plus)}")
                val full = rows.map { (r, a) -> counted(a.full, r.legacy) }.reduce(Counted::plus)
                appendLine("  within epsilon: $full")
                appendLine("  M5 ${if (full.total <= limit && full.totalFront == 0) "passes" else "fails"} (reading only: this arm is a stand-in)")
                appendLine("  by capture:")
                for ((r, a) in rows) {
                    val c = counted(a.full, r.legacy)
                    appendLine("    ${r.id.take(6)}: ${if (a.full.capHit) "stopped" else "settled"}, highest ${a.full.maxBranches}, branch-frames ${a.full.branchFrames}, ${a.nanos / 1_000_000_000} s; counted ${c.total} (ahead ${c.totalFront}), of those not settled ${a.full.undeterminable.size}")
                }
                appendLine("  every realization, baseline: ${rows.map { (r, a) -> lines(r, a.full.baseline) }.reduce(Lines::plus).render()}")
                appendLine("  every realization, this arm: ${rows.map { (r, a) -> lines(r, a.full.arm) }.reduce(Lines::plus).render()}")
                appendLine("  no error, baseline: ${rows.map { (r, a) -> lines(r, a.nominal.baseline) }.reduce(Lines::plus).render()}")
                appendLine("  no error, this arm: ${rows.map { (r, a) -> lines(r, a.nominal.arm) }.reduce(Lines::plus).render()}")
                appendLine("  stages not answered because the run stopped: baseline ${rows.sumOf { it.second.full.baseline.unresolved.size }}, this arm ${rows.sumOf { it.second.full.arm.unresolved.size }}")
            }
            appendLine()
            appendLine(String.format(Locale.ROOT, "heap at the end: %.1f GB of %.1f GB", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1e9, Runtime.getRuntime().maxMemory() / 1e9))
        }
    }

    private fun rehearse(
        session: CaptureSession,
        result: PromptPolicyCaptureEvaluation.Result,
        durations: SpeakerDurations,
        preset: PhoneGeometrySettings,
    ): CaptureResult {
        val device = checkNotNull(result.evaluation) { "${result.id}: not evaluable" }
        val inputs = FrozenSpeakerInputs(checkNotNull(result.speaker))
        val block = checkNotNull(session.manifest.regression)
        val perception = CaptureSchema.json.decodeFromJsonElement(PerceptionConfig.serializer(), block.perceptionConfig!!)
        val analysis = CaptureSchema.json.decodeFromJsonElement(AnalysisConfig.serializer(), block.analysisConfig!!)
        val semantics = DefaultNavigationSemanticsProvider().semanticSegmentationSemantics()
        val geometry = GroundContactEvaluation(preset).geometrySource(session, preset) as GroundContactEvaluation.GeometrySource.Available
        val lost = block.stats.firstLostSeq
        fun classMap(record: RegressionFrameRecord) = CaptureSessionReader.classMap(session, record)
        val generator = EventGenerator(analysis)

        val arms = listOf(NoChange(), EarlyAhead(), MutedVehicles(), Chatter())
        RegressionReplay(perception, analysis, semantics).replayScenes(session.regressionFrames, ::classMap, lost, geometry.evaluation, BirthOrderTrackIds()) { scene ->
            arms.forEach { it.prepare(scene) }
        }

        val targets = ProtectedStages.of(device).map { PairedBranchRun.Target(StageKey(it.hazard, it.stage), it.completedAtNanos!! / 1_000_000 + 500, it.appearedAt) }
        val watches = device.stages.filter { !it.ambiguousOnly && (it.stage != Stage.FIRST || !it.trackless) }.map { PairedBranchRun.Watch(StageKey(it.hazard, it.stage), it.endAt) }
        val wordings = HashMap<String, Wording>()
        val epsilon = { id: String ->
            val measuredWording = wordings[id]?.let { durations.of(it).first == OTHER_C } ?: (!id.startsWith("early") && !id.startsWith("chatter"))
            if (measuredWording) SpeakerModel.END_ERROR_BOUND_MS else 2 * SpeakerModel.END_ERROR_BOUND_MS
        }
        fun noError() = ClosedLoopSpeakerBranches({ 0 }, heldAfterDone = false, frameSkewMs = 0)
        val nominalFirst = PairedBranchRun(noError(), emptyList(), inputs.queued, targets.map { PairedBranchRun.Watch(it.key, Long.MAX_VALUE) })
        val nominals = arms.associateWith { PairedBranchRun(noError(), targets, inputs.queued, watches) }
        val fulls = arms.associateWith {
            PairedBranchRun(
                ClosedLoopSpeakerBranches(epsilon, heldAfterDone = true, frameSkewMs = ClosedLoopSpeakerBranches.FRAME_SKEW_MS),
                targets, inputs.queued, watches, maxBranches = MAX_BRANCHES, maxBranchFrames = MAX_BRANCH_FRAMES,
            )
        }
        val spent = HashMap<StandIn, Long>()

        val merger = EventMerger()
        val resolver = EventConflictResolver()
        val episodes = StateEpisodes()
        val legacy = ClosedLoopRun(analysis, durations, inputs.queued, ClosedLoopRun.Protocol.LEGACY)
        val births = BirthOrderTrackIds()
        val samples = HashMap<String, MutableList<LeadTime.Sample>>()
        RegressionReplay(perception, analysis, semantics).replayScenes(session.regressionFrames, ::classMap, lost, geometry.evaluation, births) { scene ->
            val pitch = geometry.evaluation(scene.record)?.pitchDownDegrees
            legacy.onScene(scene, pitch)
            val t = scene.decidedAt
            for (o in scene.snapshot.obstacles) LeadTime.freshSample(o, scene.record.seq, pitch)?.let { samples.getOrPut(o.id.toString()) { mutableListOf() } += it }
            for (event in generator.generate(scene.snapshot, t)) {
                if (event.category in StateEpisodes.TRACKLESS) episodes.observe(StateEpisodes.keyOf(event), t, scene.record.seq)
            }
            val groups = generator.announcedObstacleGroups(scene.snapshot.obstacles)
            val sensors = object : DeviceSensorRepository {
                override val deviceRotation: StateFlow<Int> = MutableStateFlow(0)
                override val deviceRotationValue: Int = 0
                override val deviceRotationDegree: Int = 0
                override val isStationary: StateFlow<Boolean> = MutableStateFlow(scene.stationary)
                override fun startObserving() = Unit
                override fun stopObserving() = Unit
            }
            fun decide(cooldown: CooldownManager, arm: StandIn?): PairedBranchRun.Decision? {
                val primary = DecideEventsUseCase(generator, resolver, merger, cooldown, sensors, clock = { t })(scene.snapshot).firstOrNull()
                if (primary == null || arm?.withholds(primary) == true) {
                    primary?.let { cooldown.revoke(it.id) }
                    return arm?.extra(scene, durations)?.let { PairedBranchRun.Decision(it, emptyList(), onAccepted = {}, onRefused = {}) }
                }
                val wording = primary.messageKey to primary.isNear
                val id = "${scene.record.seq}|${primary.messageKey}|${primary.isNear}|${primary.priority.value}|${primary.relatedZones}"
                wordings[id] = wording
                val credits = if (primary.category in StateEpisodes.TRACKLESS) {
                    listOfNotNull(episodes.current(StateEpisodes.keyOf(primary), t)?.let { StageKey(it.id, Stage.FIRST) })
                } else {
                    StageCredit.credits(StageCredit.spoken(primary), groups, generator).filter { it.counts }.map { StageKey(it.trackId.toString(), it.stage) }
                }
                return PairedBranchRun.Decision(
                    SpeakerModel.Offer(t, id, primary.priority.value, primary.expiresAt, durations.of(wording).second),
                    credits,
                    onAccepted = { it.confirm(primary.id) },
                    onRefused = { it.revoke(primary.id) },
                )
            }
            nominalFirst.onFrame(t, { decide(it, null) }, { decide(it, null) })
            for ((arm, run) in nominals) run.onFrame(t, { decide(it, null) }, { decide(it, arm) })
            for ((arm, run) in fulls) {
                val before = System.nanoTime()
                run.onFrame(t, { decide(it, null) }, { decide(it, arm) })
                spent.merge(arm, System.nanoTime() - before, Long::plus)
            }
        }
        return CaptureResult(
            result.id,
            ProtectedStages.Reproduction.of(device, legacy.finish(births)),
            device,
            targets.associate { it.key to it.deadlineMs },
            nominalFirst.finish().baseline,
            samples,
            arms.associate { it.name to ArmResult(nominals.getValue(it).finish(), fulls.getValue(it).finish(), spent[it] ?: 0) },
        )
    }

    private companion object {
        const val PRESET_HEIGHT_M = 1.15f
        const val OTHER_C = "other C captures"
        const val EARLY_BOTTOM_Y = 0.45f
        const val M1_THRESHOLD_M = 2.0f

        const val MAX_BRANCHES = 4_000_000
        const val MAX_BRANCH_FRAMES = 2_000_000_000L
    }
}
