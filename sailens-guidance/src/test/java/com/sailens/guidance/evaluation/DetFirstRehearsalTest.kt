package com.sailens.guidance.evaluation

import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.config.PerceptionConfig
import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.processor.decision.EventGenerator
import com.sailens.guidance.semantics.DefaultNavigationSemanticsProvider
import com.sailens.guidance.trace.capture.CaptureSchema
import com.sailens.guidance.trace.capture.CaptureSession
import com.sailens.guidance.trace.capture.CaptureSessionReader
import com.sailens.guidance.trace.capture.RegressionFrameRecord
import com.sailens.guidance.trace.capture.orThrow
import com.sailens.guidance.trace.regression.GroundContactEvaluation
import com.sailens.guidance.trace.regression.RegressionReplay
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The closed-loop rehearsal on real model-regression captures. Skipped unless a path is given:
 * captures contain faces and places and never enter the repository or CI.
 *
 * ```
 * gradlew :sailens-guidance:testDebugUnitTest --tests "*DetFirstRehearsalTest*" -PreplayCapture=<dir>
 * ```
 *
 * A sealed capture is refused ([SealedCaptures.forMetrics]) unless `-PunsealCapture` is given, and the run reads one whole
 * frozen set or nothing ([AcceptanceSet]). The report goes to `sailens-guidance/build/replay/detfirst-rehearsal.txt`; numbers
 * only. The test fails on a replay problem, not on a threshold: a threshold is a verdict for the report.
 */
class DetFirstRehearsalTest {

    private class CaptureResult(
        val id: String,
        val a12: ProtectedStages.Reproduction,
        val productionAtPreset: ProtectedStages.Reproduction,
        val legacyAtPreset: ProtectedStages.Reproduction,
        val repeats: ProtectedStages.Repeats,
        val branchLoad: BranchLoadMeasure.Result,
        /** The kernel over the fixed stream, by (ε, held after done, frame skew), for attribution. */
        val openLoopMatrix: Map<String, BranchLoadMeasure.Result>,
        /** Today's policy through the branches with its own cooldown, by (held after done, frame skew). */
        val closedLoop: Map<String, ClosedLoopBranchMeasure.Result>,
        val problems: List<String>,
        val durationSources: Map<String, Int>,
    )

    @Test
    fun `closed-loop rehearsal on recorded captures`() {
        val path = System.getProperty("sailens.replay.capture").orEmpty()
        assumeTrue("set -PreplayCapture=<capture dir> to rehearse on captures", path.isNotBlank())
        val out = File(System.getProperty("sailens.replay.out").orEmpty().ifBlank { "build/replay" }).apply { mkdirs() }
        val split = SealedCaptures.forMetrics(File(path))
        assumeTrue(SealedCaptures.refusalMessage(path, split.sealed.size), split.open.isNotEmpty())

        val preset = PhoneGeometrySettings(PRESET_HEIGHT_M, HeightSource.PRESET)
        val frozen = PromptPolicyCaptureEvaluation(preset)
        val found = split.open.map { dir -> dir to frozen.evaluate(CaptureSessionReader.read(dir).orThrow()) }
        val setProblems = AcceptanceSet.problems(found.map { (dir, r) -> AcceptanceSet.of(dir, r) })
        assertTrue("not one whole frozen set (R7), nothing computed:\n${setProblems.joinToString("\n")}", setProblems.isEmpty())
        val evaluated = found.map { (dir, r) -> dir to r!! }
        val group = checkNotNull(D18Sets.of(evaluated.first().second.id))
        // Durations are read in one pass, then each capture is scored without its own (threshold note §3.2): a calibration
        // capture by the others, a sealed one by the table frozen from all of C.
        val measured = evaluated.associate { (_, r) -> r.id to r.speaker?.measured.orEmpty() }

        val results = evaluated.map { (dir, result) ->
            val (durations, label) = when (group) {
                D18Sets.Group.SEALED -> SpeakerDurations.FROZEN_C to C_TABLE
                D18Sets.Group.CALIBRATION -> SpeakerDurations.pooled(measured.filterKeys { it != result.id }.values.flatten(), OTHER_C) to OTHER_C
            }
            rehearse(CaptureSessionReader.read(dir).orThrow(), result, durations, label, preset)
        }

        val problems = results.flatMap { it.problems }
        val report = buildString {
            appendLine("det-first closed-loop rehearsal over ${results.size} ${group.name.lowercase()} captures; problems ${problems.size}; sealed and skipped ${split.sealed.size}")
            problems.forEach { appendLine("  $it") }
            appendLine()
            val a12 = results.map { it.a12 }.reduce(ProtectedStages.Reproduction::plus)
            appendLine("A12: today's policy, legacy output path, recorded 1.2 m, nominal model, ${if (group == D18Sets.Group.SEALED) "the frozen C table" else "leave-one-out durations"}")
            append(a12.render("all captures"))
            appendLine("    A12a ${verdict(a12.a12a)}, A12b ${verdict(a12.a12b)}, A12c ${verdict(a12.a12c)}, A12d ${verdict(a12.a12d)}: A12 ${verdict(a12.pass)}")
            appendLine()
            appendLine("Early measurement (a): today's policy at the arms' ${PRESET_HEIGHT_M} m preset; a stage of P the baseline arm does not reproduce is undeterminable unless the experimental arm keeps it")
            append(results.map { it.productionAtPreset }.reduce(ProtectedStages.Reproduction::plus).render("production protocol (the baseline arm)"))
            append(results.map { it.legacyAtPreset }.reduce(ProtectedStages.Reproduction::plus).render("for attribution: legacy output path at the same height"))
            val p = a12.p.size
            appendLine("    M5 limit, all stages: at most ${maxOf(2, p / 100)} of |P| = $p; ahead CRITICAL and blocked: 0")
            appendLine()
            appendLine("Early measurement (b): branch kernel (held after done, frame skew ${ClosedLoopSpeakerBranches.FRAME_SKEW_MS} ms) over today's offers, P's stages as targets; open loop, a lower bound")
            val load = results.map { it.branchLoad }.reduce(BranchLoadMeasure.Result::plus)
            appendLine("  all captures: highest branches ${load.maxBranches}, captures that hit the limit ${results.count { it.branchLoad.capHit }}; targets kept ${load.kept}, lost ${load.lost}, undeterminable after the limit ${load.undeterminable}")
            appendLine("  attribution over the fixed stream (M5 as if an arm offered exactly today's prompts, baseline: legacy at the preset):")
            for (name in results.first().openLoopMatrix.keys) {
                val r = results.map { it.openLoopMatrix.getValue(name) }.reduce(BranchLoadMeasure.Result::plus)
                val m5 = results.map { it.legacyAtPreset.m5Of(it.openLoopMatrix.getValue(name).lostKeys) }.reduce(ProtectedStages.M5Count::plus)
                appendLine("    $name: highest ${r.maxBranches}, kept ${r.kept}, lost ${r.lost}; $m5")
            }
            appendLine()
            appendLine("Null experiment: today's policy through R10's branches with its own cooldown (a refusal revokes and is offered again), legacy output path, ${PRESET_HEIGHT_M} m")
            for (name in results.first().closedLoop.keys) {
                val rs = results.map { it.closedLoop.getValue(name) }
                appendLine(
                    "  $name: highest branches ${rs.maxOf { it.maxBranches }}, captures at the limit ${rs.count { it.capHit }} of ${rs.size}; " +
                        "kept ${rs.sumOf { it.kept.size }}, lost ${rs.sumOf { it.lost.size }}, undeterminable after the limit ${rs.sumOf { it.undeterminable.size }}",
                )
                val onLost = results.map { it.legacyAtPreset.m5Of(it.closedLoop.getValue(name).lost) }.reduce(ProtectedStages.M5Count::plus)
                val onBoth = results.map { r -> r.closedLoop.getValue(name).let { r.legacyAtPreset.m5Of(it.lost + it.undeterminable) } }.reduce(ProtectedStages.M5Count::plus)
                appendLine("    M5 on what was lost: $onLost")
                appendLine("    M5 on lost and past the limit: $onBoth")
            }
            appendLine()
            appendLine("Evidence for the C6 revision: today's policy on the device, the same hazard's same stage spoken to the end more than once")
            append(results.map { it.repeats }.reduce(ProtectedStages.Repeats::plus).render())
            appendLine()
            appendLine("Durations of the baseline arm's offers, by source: ${results.flatMap { it.durationSources.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }.toSortedMap()}")
            appendLine()
            for (r in results) {
                appendLine("capture ${r.id}")
                append(r.a12.render("A12"))
                append(r.productionAtPreset.render("baseline arm, ${PRESET_HEIGHT_M} m"))
                appendLine("  branch kernel: highest ${r.branchLoad.maxBranches}, limit hit ${r.branchLoad.capHit}; kept ${r.branchLoad.kept}, lost ${r.branchLoad.lost}, undeterminable ${r.branchLoad.undeterminable}")
            }
        }
        File(out, "detfirst-rehearsal.txt").writeText(report)
        println(report)
        assertTrue("rehearsal problems: $problems", problems.isEmpty())
    }

    private fun rehearse(
        session: CaptureSession,
        result: PromptPolicyCaptureEvaluation.Result,
        durations: SpeakerDurations,
        measuredLabel: String,
        preset: PhoneGeometrySettings,
    ): CaptureResult {
        val problems = mutableListOf<String>()
        val device = checkNotNull(result.evaluation) { "${result.id}: not evaluable" }
        if (device.evidence != PromptPolicyEvaluation.LifecycleEvidence.RECORDED) problems += "${result.id}: lifecycle is ${device.evidence.label} (X1)"
        val inputs = FrozenSpeakerInputs(checkNotNull(result.speaker))
        // X3 and X5 stay out of the closed loop until their recovery rule says otherwise; C has none.
        if (inputs.windows.isNotEmpty()) problems += "${result.id}: ${inputs.windows.size} X3/X5 windows, and this run does not apply their recovery yet"

        val block = checkNotNull(session.manifest.regression)
        val perception = CaptureSchema.json.decodeFromJsonElement(PerceptionConfig.serializer(), block.perceptionConfig!!)
        val analysis = CaptureSchema.json.decodeFromJsonElement(AnalysisConfig.serializer(), block.analysisConfig!!)
        val semantics = DefaultNavigationSemanticsProvider().semanticSegmentationSemantics()
        val geometry = GroundContactEvaluation(preset).geometrySource(session, preset) as GroundContactEvaluation.GeometrySource.Available
        val lost = block.stats.firstLostSeq
        fun classMap(record: RegressionFrameRecord) = CaptureSessionReader.classMap(session, record)
        val generator = EventGenerator(analysis)
        val revoked = session.revokes.map { it.eventId }.toSet()

        // The device's run and A12 share one replay: A12 reads the scenes the device's decisions were made on.
        val evaluation = PromptPolicyEvaluation(analysis)
        val deviceFrames = mutableListOf<PromptPolicyEvaluation.Frame>()
        val deviceBirths = BirthOrderTrackIds()
        val legacyAtRecorded = ClosedLoopRun(analysis, durations, inputs.queued, ClosedLoopRun.Protocol.LEGACY)
        val exact = RegressionReplay(perception, analysis, semantics).replay(
            session.regressionFrames, session.revokes, ::classMap, lost, geometry.exact,
            onFrame = { record, outcome ->
                val pitch = geometry.exact(record)?.pitchDownDegrees
                deviceFrames += evaluation.frame(record, outcome, revoked, pitch)
                val snapshot = checkNotNull(outcome.snapshot)
                legacyAtRecorded.onScene(
                    RegressionReplay.SceneFrame(
                        record, snapshot, outcome.obstacles, generator.visionFaults(snapshot),
                        checkNotNull(outcome.decidedAt), record.stationary ?: false,
                    ),
                    pitch,
                )
            },
            trackIds = deviceBirths,
        )
        if (!exact.exact) problems += "${result.id}: the device's run is not exact: ${exact.firstMismatch}"
        val a12 = ProtectedStages.Reproduction.of(device, legacyAtRecorded.finish(deviceBirths))

        // The arms' height: the same scenes for both protocols. A second set of births must pair with the device's (R3).
        val production = ClosedLoopRun(analysis, durations, inputs.queued, ClosedLoopRun.Protocol.PRODUCTION)
        val legacy = ClosedLoopRun(analysis, durations, inputs.queued, ClosedLoopRun.Protocol.LEGACY)
        val presetBirths = BirthOrderTrackIds()
        val creditedByForTargets = ProtectedStages.of(device).map { row ->
            BranchLoadMeasure.Target(StageKey(row.hazard, row.stage), emptySet(), row.completedAtNanos!! / 1_000_000 + 500)
        }
        val closedLoop = linkedMapOf(
            "frozen semantics (no skew, no held)" to ClosedLoopBranchMeasure(analysis, durations, measuredLabel, creditedByForTargets, inputs.queued, heldAfterDone = false, frameSkewMs = 0),
            "held after done, frame skew 50 ms" to ClosedLoopBranchMeasure(analysis, durations, measuredLabel, creditedByForTargets, inputs.queued, heldAfterDone = true, frameSkewMs = 50),
            "frozen semantics, dead cooldown records ignored when merging" to ClosedLoopBranchMeasure(analysis, durations, measuredLabel, creditedByForTargets, inputs.queued, heldAfterDone = false, frameSkewMs = 0, ignoreDeadRecords = true),
            "held after done, frame skew 50 ms, dead cooldown records ignored when merging" to ClosedLoopBranchMeasure(analysis, durations, measuredLabel, creditedByForTargets, inputs.queued, heldAfterDone = true, frameSkewMs = 50, ignoreDeadRecords = true),
            "frozen semantics, dead records ignored, no branch limit" to ClosedLoopBranchMeasure(analysis, durations, measuredLabel, creditedByForTargets, inputs.queued, heldAfterDone = false, frameSkewMs = 0, ignoreDeadRecords = true, maxBranches = 4_096),
        )
        val scenes = RegressionReplay(perception, analysis, semantics).replayScenes(
            session.regressionFrames, ::classMap, lost, geometry.evaluation, presetBirths,
        ) { scene ->
            val pitch = geometry.evaluation(scene.record)?.pitchDownDegrees
            production.onScene(scene, pitch)
            legacy.onScene(scene, pitch)
            closedLoop.values.forEach { it.onScene(scene) }
        }
        if (scenes.firstMismatch != null) problems += "${result.id}: the scenes at the preset diverged: ${scenes.firstMismatch}"
        if (!BirthOrderTrackIds.sameBirths(deviceBirths, presetBirths)) problems += "${result.id}: track births differ at the preset height"
        val productionResult = production.finish(presetBirths)

        val generatorCredits = ProtectedStages.creditsOf(deviceFrames, generator)
        val credit = CompletionCredit.of(session.utterances)
        val repeats = ProtectedStages.Repeats.of(generatorCredits) { credit[it]?.completedAtNanos }

        // The kernel over the device's own offers.
        val offers = inputs.offers.map { o -> SpeakerModel.Offer(o.atMs, o.id, o.priority, o.expiresAtMs, durations.of(o.wording).second) }
        val wordings = inputs.offers.associate { it.id to it.wording }
        val epsilon = { id: String ->
            val measuredWording = wordings[id]?.let { durations.of(it).first == measuredLabel } ?: true
            if (measuredWording) SpeakerModel.END_ERROR_BOUND_MS else 2 * SpeakerModel.END_ERROR_BOUND_MS
        }
        val creditedBy = HashMap<StageKey, MutableSet<String>>()
        for ((id, stages) in generatorCredits) for (stage in stages) creditedBy.getOrPut(stage) { mutableSetOf() } += id
        val targets = ProtectedStages.of(device).map { row ->
            val key = StageKey(row.hazard, row.stage)
            BranchLoadMeasure.Target(key, creditedBy[key].orEmpty(), row.completedAtNanos!! / 1_000_000 + 500)
        }
        val load = BranchLoadMeasure(ClosedLoopSpeakerBranches(epsilon)).run(offers + inputs.queued, targets)
        val matrix = linkedMapOf(
            "no error, no skew, no held" to ClosedLoopSpeakerBranches({ 0 }, heldAfterDone = false, frameSkewMs = 0),
            "epsilon, no skew, no held (frozen R10)" to ClosedLoopSpeakerBranches(epsilon, heldAfterDone = false, frameSkewMs = 0),
            "epsilon, held after done" to ClosedLoopSpeakerBranches(epsilon, heldAfterDone = true, frameSkewMs = 0),
            "epsilon, frame skew 50 ms" to ClosedLoopSpeakerBranches(epsilon, heldAfterDone = false, frameSkewMs = 50),
            "epsilon, held after done, frame skew 50 ms" to ClosedLoopSpeakerBranches(epsilon, heldAfterDone = true, frameSkewMs = 50),
        ).mapValues { (_, kernel) -> BranchLoadMeasure(kernel).run(offers + inputs.queued, targets) }

        return CaptureResult(
            id = result.id,
            a12 = a12,
            productionAtPreset = ProtectedStages.Reproduction.of(device, productionResult),
            legacyAtPreset = ProtectedStages.Reproduction.of(device, legacy.finish(presetBirths)),
            repeats = repeats,
            branchLoad = load,
            openLoopMatrix = matrix,
            closedLoop = closedLoop.mapValues { (_, m) -> m.finish() },
            problems = problems,
            durationSources = productionResult.durationSources,
        )
    }

    private fun verdict(pass: Boolean) = if (pass) "PASS" else "FAIL"

    private companion object {
        /** R4: the arms' height. */
        const val PRESET_HEIGHT_M = 1.15f
        const val OTHER_C = "other C captures"
        const val C_TABLE = "c table"
    }
}
