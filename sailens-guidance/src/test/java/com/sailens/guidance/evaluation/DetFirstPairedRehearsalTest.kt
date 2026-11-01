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
import kotlin.math.abs

/**
 * The paired reading of M5 ([PairedEvaluation], [PairedBranchRun]) against stand-in arms, on real captures.
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

    private class ArmResult(
        val evaluation: PairedEvaluation,
        val otherSeed: PairedEvaluation?,
        val reference: PairedBranchRun.Result,
    )

    private class CaptureResult(val id: String, val legacy: ProtectedStages.Reproduction, val deadlines: Map<StageKey, Long>, val arms: Map<String, ArmResult>)

    private data class Counted(val regressions: Int, val regressionsFront: Int, val undeterminable: Int, val undeterminableFront: Int, val fragile: Int, val fragileFront: Int, val same: Int) {
        operator fun plus(o: Counted) = Counted(regressions + o.regressions, regressionsFront + o.regressionsFront, undeterminable + o.undeterminable, undeterminableFront + o.undeterminableFront, fragile + o.fragile, fragileFront + o.fragileFront, same + o.same)

        override fun toString() = "counted ${regressions + undeterminable} (ahead ${regressionsFront + undeterminableFront}): regressions $regressions (ahead $regressionsFront), " +
            "undeterminable $undeterminable (ahead $undeterminableFront); reported only: fragile $fragile (ahead $fragileFront), same inputs $same"
    }

    private fun counted(result: PairedBranchRun.Result, legacy: ProtectedStages.Reproduction): Counted {
        val regressions = legacy.m5Of(result.regressions)
        val apart = legacy.m5Of(result.bothLostApart)
        return Counted(
            regressions.regressions + regressions.undeterminable,
            regressions.regressionsFront + regressions.undeterminableFront,
            apart.undeterminable,
            apart.undeterminableFront,
            apart.regressions,
            apart.regressionsFront,
            result.sameInputs.size,
        )
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
    }

    private fun report(results: List<CaptureResult>): String {
        return buildString {
            val p = results.sumOf { it.legacy.p.size }
            appendLine("Paired M5 over ${results.size} calibration captures, legacy output path, $PRESET_HEIGHT_M m; both arms decide for themselves on every frame")
            appendLine("M5 limit: at most ${maxOf(2, p / 100)} of |P| = $p; ahead CRITICAL and blocked: 0")
            appendLine()
            appendLine("The nominal path against the frozen nominal model (the arm that changes nothing, baseline side; stages of P):")
            var disagree = 0
            var offByMoreThan1Ms = 0
            for (r in results) {
                val nominal = r.arms.getValue(NoChange().name).evaluation.nominalBaseKept()
                for ((key, frozenNs) in r.legacy.modelCompletionNs()) {
                    val deadline = r.deadlines.getValue(key)
                    val frozenKept = frozenNs != null && frozenNs / 1_000_000 <= deadline
                    if (frozenKept != (key in nominal)) disagree++
                    if (frozenNs != null && key in nominal && abs(frozenNs / 1_000_000 - nominal.getValue(key)) > 1) offByMoreThan1Ms++
                }
            }
            appendLine("  stages kept by one and not the other: $disagree; kept by both with first completion more than 1 ms apart: $offByMoreThan1Ms")
            for (name in results.first().arms.keys) {
                appendLine()
                appendLine(name)
                val rows = results.map { it.arms.getValue(name) to it.legacy }
                fun line(label: String, of: (PairedEvaluation) -> PairedBranchRun.Result) =
                    appendLine("  $label: ${rows.map { (a, legacy) -> counted(of(a.evaluation), legacy) }.reduce(Counted::plus)}")
                appendLine("  single faults run ${rows.sumOf { it.first.evaluation.faultsRun }}, pairs run ${rows.sumOf { it.first.evaluation.pairsRun }}, captures with pairs cut ${rows.count { it.first.evaluation.pairsCut }}, most alive at once ${rows.maxOf { it.first.evaluation.mostFaultsAtOnce }}")
                line("nominal only") { it.result(0, withFaults = false) }
                line("nominal and every single fault") { it.result(0, withPairs = false) }
                line("and every pair") { it.result(0) }
                for (n in SAMPLES) line("and $n samples") { it.result(n) }
                line("$MAX_SAMPLES samples alone, no single faults") { it.result(MAX_SAMPLES, withFaults = false) }
                val other = rows.mapNotNull { (a, legacy) -> a.otherSeed?.let { Triple(a, it, legacy) } }
                if (other.isNotEmpty()) {
                    appendLine("  another seed, $MAX_SAMPLES samples: ${other.map { (_, o, legacy) -> counted(o.result(MAX_SAMPLES), legacy) }.reduce(Counted::plus)}")
                    val first = other.sumOf { (a, _, _) -> a.evaluation.result(MAX_SAMPLES, withPairs = false).regressions.size }
                    val second = other.sumOf { (_, o, _) -> o.result(MAX_SAMPLES).regressions.size }
                    val both = other.sumOf { (a, o, _) -> (a.evaluation.result(MAX_SAMPLES, withPairs = false).regressions intersect o.result(MAX_SAMPLES).regressions).size }
                    appendLine("    regressions, first seed $first, this seed $second, in both $both")
                }
                val settled = rows.filter { !it.first.reference.capHit }
                appendLine("  exact reference: settled ${settled.size} of ${rows.size} captures, highest branches ${rows.maxOf { it.first.reference.maxBranches }}")
                fun against(label: String, of: (PairedEvaluation) -> PairedBranchRun.Result) {
                    val exact = settled.sumOf { it.first.reference.regressions.size }
                    val found = settled.sumOf { (a, _) -> (a.reference.regressions intersect of(a.evaluation).regressions).size }
                    val invented = settled.sumOf { (a, _) -> (of(a.evaluation).regressions - a.reference.regressions).size }
                    appendLine("    $label: of its $exact regressions found $found, not in it $invented")
                }
                against("nominal only") { it.result(0, withFaults = false) }
                against("nominal and every single fault") { it.result(0, withPairs = false) }
                against("and every pair") { it.result(0) }
                against("and $MAX_SAMPLES samples") { it.result(MAX_SAMPLES) }
                for ((a, legacy) in settled) {
                    val missed = a.reference.regressions - a.evaluation.result(MAX_SAMPLES).regressions
                    for (key in missed) {
                        val front = legacy.m5Of(setOf(key)).let { it.regressionsFront + it.undeterminableFront } > 0
                        val witness = a.reference.witnesses[key].orEmpty()
                        appendLine("    missed: stage ${key.stage}, ahead CRITICAL or blocked $front, reference's witness has ${witness.size} choices; the last: ${witness.takeLast(8)}")
                    }
                }
            }
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
        val frameTimes = mutableListOf<Long>()
        RegressionReplay(perception, analysis, semantics).replayScenes(session.regressionFrames, ::classMap, lost, geometry.evaluation, BirthOrderTrackIds()) { scene ->
            frameTimes += scene.decidedAt
            arms.forEach { it.prepare(scene) }
        }

        val targets = ProtectedStages.of(device).map { PairedEvaluation.Target(StageKey(it.hazard, it.stage), it.completedAtNanos!! / 1_000_000 + 500, it.appearedAt) }
        val wordings = HashMap<String, Wording>()
        val epsilon = { id: String ->
            val measuredWording = wordings[id]?.let { durations.of(it).first == OTHER_C } ?: (!id.startsWith("early") && !id.startsWith("chatter"))
            if (measuredWording) SpeakerModel.END_ERROR_BOUND_MS else 2 * SpeakerModel.END_ERROR_BOUND_MS
        }
        val frames = frameTimes.toLongArray().also { it.sort() }
        fun evaluation(seed: Long, pairing: Boolean) = PairedEvaluation(
            epsilon, targets, inputs.queued, frames, MAX_SAMPLES,
            heldAfterDone = true, frameSkewMs = ClosedLoopSpeakerBranches.FRAME_SKEW_MS, pairing = pairing, seed = seed,
        )
        val first = arms.associateWith { evaluation(FIRST_SEED, pairing = true) }
        val second = arms.filter { it is EarlyAhead || it is Chatter }.associateWith { evaluation(OTHER_SEED, pairing = false) }
        val references = arms.associateWith { PairedBranchRun(ClosedLoopSpeakerBranches(epsilon, heldAfterDone = true, frameSkewMs = ClosedLoopSpeakerBranches.FRAME_SKEW_MS), targets, inputs.queued) }

        val merger = EventMerger()
        val resolver = EventConflictResolver()
        val episodes = StateEpisodes()
        val legacy = ClosedLoopRun(analysis, durations, inputs.queued, ClosedLoopRun.Protocol.LEGACY)
        val births = BirthOrderTrackIds()
        RegressionReplay(perception, analysis, semantics).replayScenes(session.regressionFrames, ::classMap, lost, geometry.evaluation, births) { scene ->
            legacy.onScene(scene, geometry.evaluation(scene.record)?.pitchDownDegrees)
            val t = scene.decidedAt
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
            for ((arm, run) in first) run.onFrame(t, { decide(it, null) }, { decide(it, arm) })
            for ((arm, run) in second) run.onFrame(t, { decide(it, null) }, { decide(it, arm) })
            for ((arm, run) in references) run.onFrame(t, { decide(it, null) }, { decide(it, arm) })
        }
        first.values.forEach { it.finish() }
        second.values.forEach { it.finish() }
        return CaptureResult(
            result.id,
            ProtectedStages.Reproduction.of(device, legacy.finish(births)),
            targets.associate { it.key to it.deadlineMs },
            arms.associate { it.name to ArmResult(first.getValue(it), second[it], references.getValue(it).finish()) },
        )
    }

    private companion object {
        const val PRESET_HEIGHT_M = 1.15f
        const val OTHER_C = "other C captures"
        const val EARLY_BOTTOM_Y = 0.45f
        val SAMPLES = listOf(8, 16, 32)
        val MAX_SAMPLES = SAMPLES.max()
        const val FIRST_SEED = 0x5A11E25L
        const val OTHER_SEED = 0x7E57C0DEL
    }
}
