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

/**
 * The paired reading of M5 ([PairedBranchRun]) against stand-in arms, on real captures. None of the arms
 * is a real policy: they exist to show what the reading does with no change, with a change that adds
 * sentences, and with changes that must be caught. Skipped unless a path is given, as
 * [DetFirstRehearsalTest]; the report goes to `sailens-guidance/build/replay/detfirst-paired.txt`.
 */
class DetFirstPairedRehearsalTest {

    /** A stand-in for an experimental arm: where it differs from today's policy, and what it says instead. */
    private abstract class StandIn(val name: String) {
        /** This frame gets a different answer from some cooldown state. */
        abstract fun differs(scene: RegressionReplay.SceneFrame, generated: List<SceneEvent>): Boolean

        /** A sentence of its own for this frame, when today's policy has none. */
        open fun extra(scene: RegressionReplay.SceneFrame, durations: SpeakerDurations): SpeakerModel.Offer? = null

        /** Today's prompt is withheld. */
        open fun withholds(primary: SceneEvent): Boolean = false
    }

    private class NoChange : StandIn("no change") {
        override fun differs(scene: RegressionReplay.SceneFrame, generated: List<SceneEvent>) = false
    }

    /**
     * What the metric gate will do to the speaker: a person or vehicle ahead is named once, earlier than
     * today's bottom-of-box gate, at the priority its first prompt would have. It leaves no cooldown
     * record, which the real gate will.
     */
    private class EarlyAhead : StandIn("early first prompt for a person or vehicle ahead") {
        private val seen = mutableSetOf<String>()
        private val firing = HashMap<Long, Pair<String, Int>>()

        override fun differs(scene: RegressionReplay.SceneFrame, generated: List<SceneEvent>): Boolean {
            var fires = false
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
                fires = true
            }
            return fires
        }

        override fun extra(scene: RegressionReplay.SceneFrame, durations: SpeakerDurations): SpeakerModel.Offer? {
            val (key, priority) = firing[scene.record.seq] ?: return null
            return SpeakerModel.Offer(scene.decidedAt, "early|${scene.record.seq}", priority, scene.decidedAt + 3_000, durations.of(key to false).second)
        }
    }

    /** Must be caught: every vehicle prompt withheld. */
    private class MutedVehicles : StandIn("every vehicle prompt withheld") {
        private fun isVehicle(event: SceneEvent) = SceneEventMessageKeys.obstacleSuffix(event.messageKey) == SceneEventMessageKeys.SUFFIX_VEHICLE
        override fun differs(scene: RegressionReplay.SceneFrame, generated: List<SceneEvent>) = generated.any(::isVehicle)
        override fun withholds(primary: SceneEvent) = isVehicle(primary)
    }

    /** Must be caught: a top-priority sentence every ten seconds, cutting whatever is speaking. */
    private class Chatter : StandIn("a top-priority sentence every 10 s") {
        private var next = Long.MIN_VALUE
        private val firing = mutableSetOf<Long>()

        override fun differs(scene: RegressionReplay.SceneFrame, generated: List<SceneEvent>): Boolean {
            if (scene.decidedAt < next) return false
            next = scene.decidedAt + 10_000
            firing += scene.record.seq
            return true
        }

        override fun extra(scene: RegressionReplay.SceneFrame, durations: SpeakerDurations): SpeakerModel.Offer? {
            if (scene.record.seq !in firing) return null
            return SpeakerModel.Offer(scene.decidedAt, "chatter|${scene.record.seq}", EventPriority.CRITICAL.value, scene.decidedAt + 3_000, 1_500)
        }
    }

    private class ArmResult(val paired: PairedBranchRun.Result, val differenceFrames: Int, val legacy: ProtectedStages.Reproduction)

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
        val results = evaluated.map { (dir, result) ->
            val durations = SpeakerDurations.pooled(measured.filterKeys { it != result.id }.values.flatten(), OTHER_C)
            rehearse(CaptureSessionReader.read(dir).orThrow(), result, durations, preset)
        }

        val report = buildString {
            appendLine("Paired M5 over ${results.size} calibration captures, legacy output path, $PRESET_HEIGHT_M m, look-back ${LOOK_BACK_MS / 1000} s")
            val p = results.sumOf { it.values.first().legacy.p.size }
            appendLine("M5 limit: at most ${maxOf(2, p / 100)} of |P| = $p; ahead CRITICAL and blocked: 0")
            for (name in results.first().keys) {
                appendLine()
                appendLine(name)
                val rows = results.map { it.getValue(name) }
                val paired = rows.map { it.paired }
                val frames = paired.sumOf { it.frames }
                appendLine(
                    "  highest branches ${paired.maxOf { it.maxBranches }}, captures at the limit ${paired.count { it.capHit }} of ${paired.size}; " +
                        "difference frames ${rows.sumOf { it.differenceFrames }}, frames in a window ${paired.sumOf { it.framesInWindow }} of $frames (${String.format(Locale.ROOT, "%.1f", 100.0 * paired.sumOf { it.framesInWindow } / frames)}%)",
                )
                appendLine(
                    "  kept ${paired.sumOf { it.kept.size }}, regressions ${paired.sumOf { it.regressions.size }}, both lost near a difference ${paired.sumOf { it.bothLostNearDifference.size }}, " +
                        "not kept with the same inputs ${paired.sumOf { it.sameInputs.size }}, undeterminable past the limit ${paired.sumOf { it.undeterminable.size }}",
                )
                val regressions = rows.map { it.legacy.m5Of(it.paired.regressions) }.reduce(ProtectedStages.M5Count::plus)
                val both = rows.map { it.legacy.m5Of(it.paired.bothLostNearDifference) }.reduce(ProtectedStages.M5Count::plus)
                val cap = rows.map { it.legacy.m5Of(it.paired.undeterminable) }.reduce(ProtectedStages.M5Count::plus)
                val totalFront = regressions.regressionsFront + regressions.undeterminableFront + both.undeterminableFront + cap.regressionsFront + cap.undeterminableFront
                val total = regressions.regressions + regressions.undeterminable + both.undeterminable + cap.regressions + cap.undeterminable
                appendLine("  paired M5: counted $total (ahead CRITICAL and blocked $totalFront)")
                appendLine("    paired regressions: $regressions")
                appendLine("    both lost, baseline did not reproduce (counted): ${both.undeterminable} (ahead ${both.undeterminableFront}); baseline reproduced (fragile, reported only): ${both.regressions} (ahead ${both.regressionsFront})")
                appendLine("    past the limit (counted): $cap")
                val everyBranch = rows.map { it.legacy.m5Of(it.paired.armLostAnyBranch + it.paired.undeterminable) }.reduce(ProtectedStages.M5Count::plus)
                appendLine("  for comparison, today's every-branch rule inside the same windows: $everyBranch")
                val full = name.substringBefore(" | ") + " | $FULL"
                val settled = results.filter { !it.getValue(full).paired.capHit }
                val exact = settled.sumOf { it.getValue(full).paired.regressions.size }
                val found = settled.sumOf { (it.getValue(full).paired.regressions intersect it.getValue(name).paired.regressions).size }
                val extra = settled.sumOf { (it.getValue(name).paired.regressions - it.getValue(full).paired.regressions).size }
                appendLine("  against the whole-session run on the ${settled.size} captures it settled: its regressions $exact, found here $found, here only $extra")
                if (name.endsWith("another seed")) {
                    val first = name.removeSuffix(", another seed")
                    val both = results.sumOf { (it.getValue(first).paired.regressions intersect it.getValue(name).paired.regressions).size }
                    appendLine("  regressions in both seeds $both, this seed ${paired.sumOf { it.regressions.size }}, the first seed ${results.sumOf { it.getValue(first).paired.regressions.size }}")
                }
            }
        }
        File(out, "detfirst-paired.txt").writeText(report)
        println(report)
        assertTrue(results.isNotEmpty())
    }

    private fun rehearse(
        session: CaptureSession,
        result: PromptPolicyCaptureEvaluation.Result,
        durations: SpeakerDurations,
        preset: PhoneGeometrySettings,
    ): Map<String, ArmResult> {
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
        val differences = arms.associateWith { sortedSetOf<Long>() }
        RegressionReplay(perception, analysis, semantics).replayScenes(session.regressionFrames, ::classMap, lost, geometry.evaluation, BirthOrderTrackIds()) { scene ->
            val generated = generator.generate(scene.snapshot, scene.decidedAt)
            for (arm in arms) if (arm.differs(scene, generated)) differences.getValue(arm) += scene.decidedAt
        }

        val targets = ProtectedStages.of(device).map { BranchLoadMeasure.Target(StageKey(it.hazard, it.stage), emptySet(), it.completedAtNanos!! / 1_000_000 + 500) }
        val wordings = HashMap<String, Wording>()
        val epsilon = { id: String ->
            val measuredWording = wordings[id]?.let { durations.of(it).first == OTHER_C } ?: (id.startsWith("early").not() && id.startsWith("chatter").not())
            if (measuredWording) SpeakerModel.END_ERROR_BOUND_MS else 2 * SpeakerModel.END_ERROR_BOUND_MS
        }
        val production = ClosedLoopSpeakerBranches(epsilon, heldAfterDone = true, frameSkewMs = 50)
        val runs = LinkedHashMap<String, Pair<StandIn, PairedBranchRun>>()
        val sampled = LinkedHashMap<StandIn, SampledPairedRun>()
        val otherSeed = LinkedHashMap<StandIn, SampledPairedRun>()
        for (arm in arms) {
            val times = differences.getValue(arm)
            runs["${arm.name} | $FULL"] = arm to PairedBranchRun(
                production, targets, inputs.queued,
                inWindow = { t -> times.subSet(t, t + LOOK_BACK_MS + 1).isNotEmpty() },
                differsNear = { t -> times.subSet(t - LOOK_BACK_MS, t + 1).isNotEmpty() },
                liveCooldown = ::liveCooldown,
                maxBranches = 4_096,
            )
            sampled[arm] = SampledPairedRun(
                production, targets, inputs.queued,
                differs = { t -> t in times },
                differsNear = { t -> times.subSet(t - LOOK_BACK_MS, t + 1).isNotEmpty() },
                samples = SAMPLES.max(),
                frameSkewMs = ClosedLoopSpeakerBranches.FRAME_SKEW_MS,
            )
            otherSeed[arm] = SampledPairedRun(
                production, targets, inputs.queued,
                differs = { t -> t in times },
                differsNear = { t -> times.subSet(t - LOOK_BACK_MS, t + 1).isNotEmpty() },
                samples = SAMPLES.max(),
                frameSkewMs = ClosedLoopSpeakerBranches.FRAME_SKEW_MS,
                seed = OTHER_SEED,
            )
        }

        val merger = EventMerger()
        val resolver = EventConflictResolver()
        val episodes = StateEpisodes()
        val legacy = ClosedLoopRun(analysis, durations, inputs.queued, ClosedLoopRun.Protocol.LEGACY)
        val births = BirthOrderTrackIds()
        RegressionReplay(perception, analysis, semantics).replayScenes(session.regressionFrames, ::classMap, lost, geometry.evaluation, births) { scene ->
            legacy.onScene(scene, geometry.evaluation(scene.record)?.pitchDownDegrees)
            val t = scene.decidedAt
            val generated = generator.generate(scene.snapshot, t)
            for (event in generated) {
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
            fun today(cooldown: com.sailens.guidance.processor.decision.CooldownManager, arm: StandIn?): PairedBranchRun.Decision? {
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
            for ((arm, run) in runs.values) run.onFrame(t, { today(it, null) }, { today(it, arm) })
            for ((arm, run) in sampled) run.onFrame(t, { today(it, null) }, { today(it, arm) })
            for ((arm, run) in otherSeed) run.onFrame(t, { today(it, null) }, { today(it, arm) })
        }
        val legacyResult = ProtectedStages.Reproduction.of(device, legacy.finish(births))
        return runs.mapValues { (_, value) -> ArmResult(value.second.finish(), differences.getValue(value.first).size, legacyResult) } +
            sampled.onEach { it.value.finish() }.entries.flatMap { (arm, run) ->
                SAMPLES.map { n -> "${arm.name} | $n samples" to ArmResult(run.result(n), differences.getValue(arm).size, legacyResult) }
            } + otherSeed.onEach { it.value.finish() }.entries.map { (arm, run) ->
                "${arm.name} | ${SAMPLES.max()} samples, another seed" to ArmResult(run.result(SAMPLES.max()), differences.getValue(arm).size, legacyResult)
            }
    }

    private companion object {
        const val PRESET_HEIGHT_M = 1.15f
        const val OTHER_C = "other C captures"
        const val LOOK_BACK_MS = 8_000L
        const val EARLY_BOTTOM_Y = 0.45f

        const val FULL = "whole session, limit 4096"
        /** 0 is the nominal run alone. */
        val SAMPLES = listOf(0, 8, 16, 32)
        const val OTHER_SEED = 0x7E57C0DEL
    }
}
