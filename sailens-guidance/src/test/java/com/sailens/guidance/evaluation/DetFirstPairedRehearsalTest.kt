package com.sailens.guidance.evaluation

import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.config.DetFirstConfig
import com.sailens.guidance.config.MetricGateConfig
import com.sailens.guidance.config.PerceptionConfig
import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.model.common.DirectionZone
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.scene.SceneEventMessageKeys
import com.sailens.guidance.processor.decision.CooldownManager
import com.sailens.guidance.processor.decision.EventConflictResolver
import com.sailens.guidance.processor.decision.EventGenerator
import com.sailens.guidance.processor.decision.EventMerger
import com.sailens.guidance.processor.decision.SpeechScale
import com.sailens.guidance.repository.DeviceSensorRepository
import com.sailens.guidance.semantics.DefaultNavigationSemanticsProvider
import com.sailens.guidance.trace.capture.CaptureSchema
import com.sailens.guidance.trace.capture.CaptureSession
import com.sailens.guidance.trace.capture.CaptureSessionReader
import com.sailens.guidance.trace.capture.RegressionClocks
import com.sailens.guidance.trace.capture.RegressionFrameRecord
import com.sailens.guidance.trace.capture.UtteranceRecordEvents
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
 * The paired reading of M5 ([PairedBranchRun]) on real captures: the policy with its switches against today's, each
 * through the production delivery protocol ([ProtocolDriver]), beside an arm that changes nothing and two that must
 * be caught. Skipped unless a path is given, as [DetFirstRehearsalTest]; the report goes to
 * `sailens-guidance/build/replay/detfirst-paired.txt`.
 */
class DetFirstPairedRehearsalTest {

    private class Arm(val name: String, val detFirst: DetFirstConfig, val alteration: () -> ProtocolDriver.Alteration = { ProtocolDriver.Alteration.NONE })

    /** Must be caught: every vehicle prompt withheld. */
    private class MutedVehicles : ProtocolDriver.Alteration {
        override fun withholds(primary: SceneEvent) = SceneEventMessageKeys.obstacleSuffix(primary.messageKey) == SceneEventMessageKeys.SUFFIX_VEHICLE
    }

    /** Must be caught: a top-priority sentence every ten seconds, cutting whatever is speaking. */
    private class Chatter(private val firing: Set<Long>) : ProtocolDriver.Alteration {
        override fun extra(scene: RegressionReplay.SceneFrame): SpeakerModel.Offer? {
            if (scene.record.seq !in firing) return null
            return SpeakerModel.Offer(scene.decidedAt, "chatter|${scene.decidedAt}", SpeechScale.of(EventPriority.CRITICAL, SpeechScale.MAX_RANK), scene.decidedAt + 3_000, 1_500)
        }
    }

    private class ArmResult(val nominal: PairedBranchRun.Result, val full: PairedBranchRun.Result, val nanos: Long, val nominalArm: ProtocolDriver, val nominalBase: ProtocolDriver)

    private class CaptureResult(
        val id: String,
        /** P against the baseline with no error, on the production protocol at the arms' height: what M5 asks of the baseline. */
        val production: ProtectedStages.Reproduction,
        val device: PromptPolicyEvaluation.Result,
        val deadlines: Map<StageKey, Long>,
        /** The legacy path with no error, by this run's own code, against [frozenLegacy]. */
        val nominalLegacyFirst: PairedBranchRun.Completions,
        val frozenLegacy: ProtectedStages.Reproduction,
        val samples: Map<String, List<LeadTime.Sample>>,
        val arms: Map<String, ArmResult>,
        /** Each hazard's frames as the launch policy's gates see them: time, then what holds it, null when it is admitted. */
        val gateFrames: Map<String, List<Pair<Long, String?>>>,
        val firstAt: Long,
        val frames: Int,
        val minutes: Double,
        val stationaryMinutes: Double,
        /** Events said to be NEAR or CRITICAL that conflict resolution removed, by arm: frames, and distinct hazards. */
        val removedNearOrCritical: Map<String, Pair<Int, Int>>,
        /** The launch arm with no branching, once per fixed trace of independent sentence errors. */
        val stress: List<PairedBranchRun.Result>,
        val unmodeled: UnmodeledFrom,
        /** Frames the arm offered on where the device offered nothing, so nothing timed them. */
        val untimedOffers: Map<String, Int>,
        val sentences: SentenceDurations,
    )

    private data class Counted(val regressions: Int, val regressionsFront: Int, val undeterminable: Int, val undeterminableFront: Int, val fragile: Int, val fragileFront: Int, val same: Int) {
        val total get() = regressions + undeterminable
        val totalFront get() = regressionsFront + undeterminableFront

        operator fun plus(o: Counted) = Counted(regressions + o.regressions, regressionsFront + o.regressionsFront, undeterminable + o.undeterminable, undeterminableFront + o.undeterminableFront, fragile + o.fragile, fragileFront + o.fragileFront, same + o.same)

        override fun toString() = "counted $total (ahead $totalFront): regressions $regressions (ahead $regressionsFront), " +
            "undeterminable $undeterminable (ahead $undeterminableFront); reported only: fragile $fragile (ahead $fragileFront), same inputs $same"
    }

    /** @param forgiven what no longer counts as lost, for a reading with a longer tolerance. */
    private fun counted(
        result: PairedBranchRun.Result,
        reproduction: ProtectedStages.Reproduction,
        unmodeled: Set<StageKey> = emptySet(),
        forgiven: (StageKey) -> Boolean = { false },
    ): Counted {
        val regressions = reproduction.m5Of(result.regressions.filterNotTo(HashSet(), forgiven) - unmodeled)
        val apart = reproduction.m5Of(result.bothLostApart.filterNotTo(HashSet(), forgiven) - unmodeled)
        val stopped = reproduction.m5Of(result.undeterminable.filterNotTo(HashSet(), forgiven) + (unmodeled intersect (result.kept + result.regressions + result.bothLostApart)))
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
            val said = key in side.everywhere && !r.unmodeled.covers(row.endAt)
            if (row.stage == Stage.NEAR && !row.trackless) {
                count("B3 near all", said)
                if (row.hazardClass == "person/front") count("B1 near person", said)
                if (row.hazardClass == "vehicle/front") count("B2 near vehicle", said)
            }
            if (row.stage == Stage.CRITICAL && row.hazardClass == "person/front") count("B5 critical person", said)
            if (row.stage == Stage.CRITICAL && row.hazardClass == "vehicle/front") count("B5 critical vehicle", said)
        }
        for (lead in r.device.leads) {
            if (lead.stage != Stage.FIRST || lead.thresholdM != M1_THRESHOLD_M) continue
            val name = when (lead.hazardClass) {
                "person/front" -> "person"
                "vehicle/front" -> "vehicle"
                else -> continue
            }
            val completion = side.completionMs(StageKey(lead.hazard, Stage.FIRST))?.takeUnless(r.unmodeled::covers)
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
        val nominalOnly = NOMINAL_ONLY
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
        File(out, "detfirst-label-list.csv").writeText(labelList(results))
        assertTrue(results.isNotEmpty())
        if (nominalOnly) return
        val failed = standingChecks(results)
        assertTrue("standing checks failed:\n${failed.joinToString("\n")}", failed.isEmpty())
    }

    /** Stages of P kept by one and not the other, and kept by both more than 1 ms apart. */
    private fun nominalAgainstFrozen(results: List<CaptureResult>): Pair<Int, Int> {
        var disagree = 0
        var offByMoreThan1Ms = 0
        for (r in results) {
            for ((key, frozenNs) in r.frozenLegacy.modelCompletionNs()) {
                val deadline = r.deadlines.getValue(key)
                val frozenKept = frozenNs != null && frozenNs / 1_000_000 <= deadline
                val nominal = r.nominalLegacyFirst.completionMs(key)
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
        val limit = maxOf(2, results.sumOf { it.production.p.size } / 100)
        fun total(name: String) = results.map { counted(it.arms.getValue(name).full, it.production) }.reduce(Counted::plus)
        val unchanged = total(NO_CHANGE)
        if (unchanged.total + unchanged.fragile != 0) add("an arm that changes nothing scored: $unchanged")
        for (r in results) {
            val full = r.arms.getValue(NO_CHANGE).full
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
        for (name in listOf(MUTED, CHATTER)) {
            val caught = total(name)
            if (caught.total <= limit) add("$name must be caught, but scored: $caught")
        }
    }

    private fun report(results: List<CaptureResult>): String {
        return buildString {
            val p = results.sumOf { it.production.p.size }
            val limit = maxOf(2, p / 100)
            appendLine("Paired M5 over ${results.size} calibration captures, production protocol, $PRESET_HEIGHT_M m; both arms decide for themselves on every frame")
            appendLine("M5 limit: at most $limit of |P| = $p; ahead CRITICAL and blocked: 0. Stop at $MAX_BRANCHES branches or $MAX_BRANCH_FRAMES branch-frames.")
            appendLine()
            appendLine("The legacy path with no error against the frozen nominal model (stages of P):")
            val (disagree, offByMoreThan1Ms) = nominalAgainstFrozen(results)
            appendLine("  stages kept by one and not the other: $disagree; kept by both with first completion more than 1 ms apart: $offByMoreThan1Ms")
            appendLine("The baseline with no error against the device, production protocol: P $p, reproduced ${results.sumOf { it.production.reproducedAll }}, of ahead CRITICAL and blocked ${results.sumOf { it.production.frontP.size }}: ${results.sumOf { it.production.reproducedFront }}")
            appendLine(String.format(Locale.ROOT, "%d frames in %.1f min: %.1f a second, so 20 stable frames are at least %.2f s", results.sumOf { it.frames }, results.sumOf { it.minutes }, results.sumOf { it.frames } / (results.sumOf { it.minutes } * 60), 20 / (results.sumOf { it.frames } / (results.sumOf { it.minutes } * 60))))
            appendLine("Not modeled, so counted undeterminable from then on: X3 and X5 windows ${results.sumOf { it.unmodeled.windowCount }}, frames past ${ClosedLoopSpeakerBranches.FRAME_SKEW_MS} ms ${results.sumOf { it.unmodeled.overrunCount }}, stages of P after them ${results.sumOf { r -> r.production.p.count { it.key in unmodeledOf(r) } }}")
            for (name in results.first().arms.keys) {
                appendLine()
                appendLine(name)
                val rows = results.map { it to it.arms.getValue(name) }
                val settled = rows.count { !it.second.full.capHit }
                appendLine("  settled $settled of ${rows.size}, highest branches ${rows.maxOf { it.second.full.maxBranches }}, branch-frames ${rows.sumOf { it.second.full.branchFrames }}, slowest capture ${rows.maxOf { it.second.nanos } / 1_000_000_000} s, all ${rows.sumOf { it.second.nanos } / 1_000_000_000} s")
                appendLine("  no error:      ${rows.map { (r, a) -> counted(a.nominal, r.production) }.reduce(Counted::plus)}")
                val full = rows.map { (r, a) -> counted(a.full, r.production, unmodeledOf(r)) }.reduce(Counted::plus)
                appendLine("  within epsilon: $full")
                appendLine("  M5 ${if (full.total <= limit && full.totalFront == 0) "passes" else "fails"}")
                appendLine("  what is lost with no error, by class and stage (never / late, in s past t_dev + 0.5):")
                val lost = HashMap<String, MutableList<Double?>>()
                for ((r, a) in rows) {
                    val classOf = r.device.stages.associateBy { StageKey(it.hazard, it.stage) }
                    for (key in a.nominal.regressions) {
                        val row = classOf.getValue(key)
                        val late = a.nominal.arm.completionMs(key)?.let { (it - r.deadlines.getValue(key)) / 1000.0 }
                        lost.getOrPut("${row.hazardClass} ${row.stage}") { mutableListOf() } += late
                    }
                }
                for ((label, lates) in lost.entries.sortedByDescending { it.value.size }) {
                    val known = lates.filterNotNull().sorted()
                    appendLine(String.format(Locale.ROOT, "    %s: %d; never %d; late %d%s", label, lates.size, lates.count { it == null }, known.size, if (known.isEmpty()) "" else String.format(Locale.ROOT, ", median %.1f, max %.1f", known[known.size / 2], known.last())))
                }
                appendLine("  and what became of the sentences that could have said them, with no error:")
                val why = java.util.TreeMap<String, MutableList<Double>>()
                for ((r, a) in rows) {
                    val classOf = r.device.stages.associateBy { StageKey(it.hazard, it.stage) }
                    for (key in a.nominal.regressions) {
                        val deadline = r.deadlines.getValue(key)
                        val row = classOf.getValue(key)
                        val ahead = if (row.hazardClass == "blocked" || (row.stage == Stage.CRITICAL && row.hazardClass in setOf("vehicle/front", "person/front"))) "ahead" else "other"
                        val sentences = a.nominalArm.offered.values.filter { key in a.nominalArm.credits[it.id].orEmpty() && it.atMs <= deadline }
                        val theirs = a.nominalBase.offered.values.filter { key in a.nominalBase.credits[it.id].orEmpty() && it.endAtMs != null && it.endAtMs!! <= deadline }.minByOrNull { it.endAtMs!! }
                        val spokenLate = sentences.filter { it.endAtMs != null }.minByOrNull { it.endAtMs!! }
                        val label = when {
                            spokenLate != null && "|true|" in spokenLate.id && theirs != null && "|true|" !in theirs.id && spokenLate.atMs <= theirs.atMs + 100 -> "spoken in time for today, but with the NEAR prefix, so longer"
                            spokenLate != null && theirs != null && "|true|" in spokenLate.id && "|true|" !in theirs.id -> "spoken later, with the NEAR prefix"
                            spokenLate != null -> "spoken later"
                            sentences.any { it.cutAtMs != null } -> "taken, then cut off, not said again in time"
                            sentences.any { it.accepted } -> "taken, not over by the deadline"
                            sentences.isNotEmpty() -> "offered, every time refused"
                            else -> "never offered in time"
                        }
                        why.getOrPut("$ahead: $label") { mutableListOf() } += (spokenLate?.endAtMs?.let { (it - deadline) / 1000.0 } ?: Double.NaN)
                    }
                }
                val blockers = java.util.TreeMap<String, Int>()
                fun kind(o: ProtocolDriver.Offered): String {
                    val parts = o.id.split('|')
                    val what = when {
                        parts[0] == "notice" -> parts[1]
                        parts[0] == "mode" || parts[0] == "chatter" -> parts[0]
                        else -> parts[1].removePrefix("event_").let { if (it.startsWith("obstacle")) "obstacle${if (parts[2] == "true") " NEAR" else ""}" else it }
                    }
                    return "$what ${o.scale / 8}.${o.scale % 8}"
                }
                for ((r, a) in rows) {
                    val classOf = r.device.stages.associateBy { StageKey(it.hazard, it.stage) }
                    for (key in a.nominal.regressions) {
                        val deadline = r.deadlines.getValue(key)
                        val ahead = classOf.getValue(key).let { row -> if (row.hazardClass == "blocked" || (row.stage == Stage.CRITICAL && row.hazardClass in setOf("vehicle/front", "person/front"))) "ahead" else "other" }
                        val sentences = a.nominalArm.offered.values.filter { key in a.nominalArm.credits[it.id].orEmpty() && it.atMs <= deadline }
                        val all = a.nominalArm.offered.values.toList()
                        if (sentences.isEmpty() || sentences.any { it.endAtMs != null }) continue
                        val cut = sentences.lastOrNull { it.cutAtMs != null }
                        if (cut != null) {
                            val by = all.firstOrNull { it.accepted && it.atMs == cut.cutAtMs }
                            blockers.merge("$ahead cut: ${kind(cut)} by ${by?.let(::kind) ?: "?"}", 1, Int::plus)
                        } else if (sentences.none { it.accepted }) {
                            val last = sentences.last()
                            val holder = all.lastOrNull { it.accepted && it.atMs <= last.atMs && (it.endAtMs ?: Long.MAX_VALUE) > last.atMs && (it.cutAtMs ?: Long.MAX_VALUE) > last.atMs }
                            blockers.merge("$ahead refused: ${kind(last)} by ${holder?.let(::kind) ?: "? (queued input or expired)"}", 1, Int::plus)
                        }
                    }
                }
                for ((label, n) in blockers) appendLine("    $label: $n")
                for ((label, lates) in why) {
                    val known = lates.filterNot { it.isNaN() }.sorted()
                    appendLine(String.format(Locale.ROOT, "    %s: %d%s", label, lates.size, if (known.isEmpty()) "" else String.format(Locale.ROOT, "; past the deadline median %.2f s, max %.2f s", known[known.size / 2], known.last())))
                }
                appendLine("  losses with no error, count (ahead):")
                append(lossLines(rows.map { (r, a) -> r to a.nominal }))
                if (name == LAUNCH) append(stressTraces(rows.map { it.first }) { it.arms.getValue(LAUNCH).nominal })
                if (name == LAUNCH) {
                    appendLine(whyNotBeforeCrossing(rows))
                    append(heardAheadInstead(rows))
                }
                if (name == LAUNCH || name == NO_CHANGE) {
                    val armCounts = rows.map { (r, a) -> countsOf(r, a.nominalArm) }.reduce(PromptCounts.Counts::plus)
                    val baseCounts = rows.map { (r, a) -> countsOf(r, a.nominalBase) }.reduce(PromptCounts.Counts::plus)
                    append(countLines(name, rows.map { it.first }, armCounts, baseCounts))
                }
                appendLine("  by capture:")
                for ((r, a) in rows) {
                    val c = counted(a.full, r.production)
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

    /** The stages whose deadline falls at or after the first thing the run does not model. */
    private fun unmodeledOf(r: CaptureResult): Set<StageKey> = r.deadlines.filterValues(r.unmodeled::covers).keys

    private fun countsOf(r: CaptureResult, driver: ProtocolDriver): PromptCounts.Counts = PromptCounts.of(
        driver.offered.values,
        driver.credits,
        object : PromptCounts.Lives {
            override fun everWithin2m(hazard: String) = r.samples[hazard].orEmpty().any { (it.validPointM ?: Float.MAX_VALUE) <= M1_THRESHOLD_M }

            override fun shorterThan1s(hazard: String) = r.gateFrames[hazard].orEmpty().let { it.isEmpty() || it.last().first - it.first().first < 1_000 }
        },
        r.minutes,
        r.stationaryMinutes,
        r.sentences::isFallback,
    )

    private fun countLines(name: String, captures: List<CaptureResult>, arm: PromptCounts.Counts, base: PromptCounts.Counts): String = buildString {
        fun f(x: Double) = String.format(Locale.ROOT, "%.2f", x)
        appendLine("  the counting lines, with no error (${f(arm.minutes)} min, stationary ${f(arm.stationaryMinutes)} min):")
        appendLine("    B4 NEAR or CRITICAL removed by conflict resolution: this arm ${captures.sumOf { it.removedNearOrCritical.getValue(name).first }} frames, ${captures.sumOf { it.removedNearOrCritical.getValue(name).second }} hazards; baseline ${captures.sumOf { it.removedNearOrCritical.getValue(NO_CHANGE).first }} frames, ${captures.sumOf { it.removedNearOrCritical.getValue(NO_CHANGE).second }} hazards (must be 0)")
        appendLine("    B6, C7 completed per minute: this arm ${f(arm.perMinute)} (${arm.completed}), baseline ${f(base.perMinute)} (${base.completed}), ratio ${f(arm.perMinute / base.perMinute)} (at most 1.25)")
        appendLine("    C7 completed per stationary minute: this arm ${f(arm.stationaryPerMinute)} (${arm.completedStationary}), baseline ${f(base.stationaryPerMinute)} (${base.completedStationary}), ratio ${f(arm.stationaryPerMinute / base.stationaryPerMinute)} (at most 1.25)")
        appendLine("    B7 regives: stages ${arm.regivenStages}, ${f(100.0 * arm.regivenStages / maxOf(arm.completed, 1))}% of completed (at most 15%); most of one stage ${arm.mostRegivesOfAStage} (at most 1). Sentences ${arm.regivenSentences}, ${f(100.0 * arm.regivenSentences / maxOf(arm.completed, 1))}%")
        appendLine("    metric-only prompts: offered ${arm.metricOnlyOffered}, completed ${arm.metricOnlyCompleted}")
        appendLine("    C4 metric-only never within 2.0 m: ${arm.metricOnlyNeverWithin2m}, ${f(arm.neverWithin2mPerMinute)} per minute (at most 1.0)")
        appendLine("    C5 metric-only on a track under 1 s: ${arm.metricOnlyShortLived}, ${f(100.0 * arm.metricOnlyShortLived / maxOf(arm.metricOnlyCompleted, 1))}% (at most 5%)")
        appendLine("    C6 by the revised count: repeats ${arm.repeat} (must be 0); overlaps ${arm.overlap}, periodic repeats ${arm.periodic} (reported only). By the frozen count: ${arm.stagesCreditedAgain} stages in ${arm.promptsCreditedAgain} prompts")
        appendLine("    D1 path-complex prompts offered: ${arm.pathComplexOffered} (must be 0 with its switch on)")
        appendLine("    sentences of a wording not measured on recordings: ${arm.fallbackWordings} of ${arm.offered}")
        appendLine("    untimed offers (frames the device offered nothing on): ${captures.sumOf { it.untimedOffers.getValue(name) }}")
    }

    private fun lossesIn(r: CaptureResult, run: PairedBranchRun.Result): Map<StageKey, Loss> = buildMap {
        for (stage in r.production.p) {
            val key = stage.key
            val notKept = key in run.regressions || key in run.bothLostApart || key in run.undeterminable
            Loss.of(notKept, key !in run.undeterminable, run.arm.completionMs(key), r.deadlines.getValue(key), r.unmodeled)?.let { put(key, it) }
        }
    }

    private class Tally(val all: Int, val ahead: Int) {
        override fun toString() = "$all ($ahead)"
    }

    /**
     * Each kind of loss over every protected stage, and over those the device finished while the track lived. That second
     * group is a split for reading, not a judgement: a track ending does not mean the hazard has gone.
     */
    private fun lossLines(rows: List<Pair<CaptureResult, PairedBranchRun.Result>>): String = buildString {
        fun tally(kinds: Set<Loss>, liveOnly: Boolean): Tally {
            var all = 0
            var ahead = 0
            for ((r, run) in rows) {
                val aheadKeys = r.production.p.filter { it.frontCritical }.mapTo(HashSet()) { it.key }
                val ends = r.device.stages.associate { StageKey(it.hazard, it.stage) to it.endAt }
                for ((key, loss) in lossesIn(r, run)) {
                    if (loss !in kinds) continue
                    if (liveOnly && r.deadlines.getValue(key) - 500 > ends.getValue(key)) continue
                    all++
                    if (key in aheadKeys) ahead++
                }
            }
            return Tally(all, ahead)
        }
        for ((name, liveOnly) in listOf("every stage" to false, "device finished while the track lived" to true)) {
            appendLine("      $name: never ${tally(setOf(Loss.NEVER), liveOnly)}; unknown ${tally(setOf(Loss.UNKNOWN), liveOnly)}; severe delay ${tally(setOf(Loss.SEVERE_DELAY), liveOnly)}; bounded delay ${tally(setOf(Loss.BOUNDED_DELAY), liveOnly)}")
        }
    }

    /** The serious losses of a run, by capture: a track's number starts over in each. */
    private fun seriousIn(r: CaptureResult, run: PairedBranchRun.Result): Set<Pair<String, StageKey>> =
        lossesIn(r, run).filterValues { it != Loss.BOUNDED_DELAY }.keys.mapTo(HashSet()) { r.id to it }

    /** What a few fixed traces of independent errors do, read as the nominal run is. A pressure check: it says nothing of the traces it does not run. */
    private fun stressTraces(captures: List<CaptureResult>, nominal: (CaptureResult) -> PairedBranchRun.Result): String = buildString {
        val aheadKeys = captures.flatMap { r -> r.production.p.filter { it.frontCritical }.map { r.id to it.key } }.toSet()
        val base = captures.flatMap { seriousIn(it, nominal(it)) }.toSet()
        val union = HashSet(base)
        appendLine("  fixed error traces; added and recovered are serious losses against the nominal run:")
        for (trace in 0 until STRESS_TRACES) {
            val serious = captures.flatMap { seriousIn(it, it.stress[trace]) }.toSet()
            union += serious
            val added = serious - base
            val recovered = base - serious
            appendLine("    trace $trace: added ${added.size} (ahead ${added.count { it in aheadKeys }}); recovered ${recovered.size} (ahead ${recovered.count { it in aheadKeys }})")
            append(lossLines(captures.map { it to it.stress[trace] }))
        }
        appendLine("    serious in the nominal run or any trace: ${union.size} (ahead ${union.count { it in aheadKeys }})")
    }

    /**
     * The stages ahead a person must look at: never over, severely late or unknown, in the nominal run or any trace. Times
     * are from the capture's first frame. It stays on this machine with the captures.
     */
    private fun labelList(results: List<CaptureResult>): String = buildString {
        appendLine("session,hazard,stage,class,appeared_s,track_ended_s,device_finished_s,readings")
        for (r in results) {
            val rowOf = r.device.stages.associateBy { StageKey(it.hazard, it.stage) }
            val readings = LinkedHashMap<StageKey, MutableList<String>>()
            val runs = listOf("nominal" to r.arms.getValue(LAUNCH).nominal) + r.stress.mapIndexed { i, run -> "trace$i" to run }
            val aheadKeys = r.production.p.filter { it.frontCritical }.mapTo(HashSet()) { it.key }
            for ((name, run) in runs) {
                for ((key, loss) in lossesIn(r, run)) {
                    if (loss != Loss.BOUNDED_DELAY && key in aheadKeys) readings.getOrPut(key) { mutableListOf() } += "$name:$loss"
                }
            }
            for ((key, seen) in readings.entries.sortedBy { rowOf.getValue(it.key).appearedAt }) {
                val row = rowOf.getValue(key)
                fun s(ms: Long) = String.format(Locale.ROOT, "%.1f", (ms - r.firstAt) / 1000.0)
                appendLine("${r.id},${key.hazard},${key.stage},${row.hazardClass},${s(row.appearedAt)},${s(row.endAt)},${s(r.deadlines.getValue(key) - 500)},${seen.joinToString(" ")}")
            }
        }
    }

    /** Stages lost ahead, with no error, where the user heard another warning ahead of the same level or higher anyway. */
    private fun heardAheadInstead(rows: List<Pair<CaptureResult, ArmResult>>): String = buildString {
        var lostAhead = 0
        var heard = 0
        for ((r, a) in rows) {
            val rowOf = r.device.stages.associateBy { StageKey(it.hazard, it.stage) }
            for (key in a.nominal.regressions) {
                val row = rowOf.getValue(key)
                val ahead = row.hazardClass == "blocked" || (row.stage == Stage.CRITICAL && row.hazardClass in setOf("vehicle/front", "person/front"))
                if (!ahead) continue
                lostAhead++
                val deadline = r.deadlines.getValue(key)
                val level = a.nominalBase.offered.values.filter { key in a.nominalBase.credits[it.id].orEmpty() }.mapNotNull { it.event?.priority }.maxOrNull() ?: continue
                val covered = a.nominalArm.offered.values.any { o ->
                    val end = o.endAtMs
                    val event = o.event
                    end != null && event != null && end in (deadline - HEARD_WITHIN_MS)..deadline && event.priority >= level &&
                        DirectionZone.CENTER in event.relatedZones && event.category in setOf(EventCategory.OBSTACLE, EventCategory.BLOCKED)
                }
                if (covered) heard++
            }
        }
        appendLine("  stages lost ahead with no error: $lostAhead; of them, another warning ahead of that level or higher ended within ${HEARD_WITHIN_MS / 1000} s before the deadline: $heard")
    }

    /** For each hazard ahead with a bracketed crossing of 2.0 m: what kept its first prompt from ending before it. */
    private fun whyNotBeforeCrossing(rows: List<Pair<CaptureResult, ArmResult>>): String = buildString {
        appendLine("  why a first prompt ahead is not over before 2.0 m, with no error (a sentence is taken as $FIRST_SENTENCE_MS ms):")
        val counts = java.util.TreeMap<String, Int>()
        val secondsSeen = HashMap<String, MutableList<Double>>()
        for ((r, a) in rows) {
            for (lead in r.device.leads) {
                if (lead.stage != Stage.FIRST || lead.thresholdM != M1_THRESHOLD_M) continue
                val name = when (lead.hazardClass) {
                    "person/front" -> "person"
                    "vehicle/front" -> "vehicle"
                    else -> continue
                }
                val key = StageKey(lead.hazard, Stage.FIRST)
                val samples = r.samples[lead.hazard].orEmpty()
                val completion = a.nominal.arm.completionMs(key)
                val result = LeadTime.classify(samples, M1_THRESHOLD_M, completion?.let { it * 1_000_000 })
                val crossing = result.crossing ?: continue
                val crossAt = crossing.t1Nanos / 1_000_000
                val frames = r.gateFrames[lead.hazard].orEmpty()
                val latestStart = crossAt - FIRST_SENTENCE_MS
                val firstSeen = frames.firstOrNull()?.first
                val admitted = frames.firstOrNull { it.second == null }?.first
                val heldAt = frames.lastOrNull { it.first <= latestStart }?.second
                val sentences = a.nominalArm.offered.values.filter { key in a.nominalArm.credits[it.id].orEmpty() && it.atMs <= latestStart }
                val why = when {
                    result.outcome == LeadTime.Outcome.BEFORE -> "over before the crossing"
                    firstSeen == null || firstSeen > latestStart -> "first tracked too late to say it"
                    admitted == null -> "never admitted: $heldAt"
                    admitted > latestStart -> "admitted too late, at the last moment held by: $heldAt"
                    sentences.isEmpty() -> "admitted in time, not offered in time: the cooldown, or a higher prompt first"
                    sentences.any { it.endAtMs != null } -> "said in time by the model's clock but over after t1"
                    sentences.any { it.cutAtMs != null } -> "offered in time, then cut off"
                    sentences.any { it.accepted } -> "offered in time, not over"
                    else -> "offered in time, refused"
                }
                counts.merge("$name: $why", 1, Int::plus)
                if (firstSeen != null) secondsSeen.getOrPut(name) { mutableListOf() } += (crossAt - firstSeen) / 1000.0
            }
        }
        for ((label, n) in counts) appendLine("    $label: $n")
        for ((name, seen) in secondsSeen) {
            val sorted = seen.sorted()
            appendLine(String.format(Locale.ROOT, "    %s: tracked before the crossing, s: p25 %.1f, median %.1f, p75 %.1f", name, sorted[sorted.size / 4], sorted[sorted.size / 2], sorted[sorted.size * 3 / 4]))
        }
    }

    /** One arm's runs on one capture: with no error, and within ε, on their own kernels, with drivers of their own. */
    private inner class ArmRuns(arm: Arm, analysis: AnalysisConfig, durations: SentenceDurations, queued: List<SpeakerModel.Queued>, targets: List<PairedBranchRun.Target>, watches: List<PairedBranchRun.Watch>) {
        private val off = analysis.copy(detFirst = DetFirstConfig())
        private val on = analysis.copy(detFirst = arm.detFirst)
        private val offScale = queued.onSpeechScale(DetFirstConfig())
        private val onScale = queued.onSpeechScale(arm.detFirst)

        private val noError = ClosedLoopSpeakerBranches({ 0 }, heldAfterDone = false, frameSkewMs = 0)
        private val withError = ClosedLoopSpeakerBranches(durations::epsilonMs, heldAfterDone = true, frameSkewMs = ClosedLoopSpeakerBranches.FRAME_SKEW_MS)
        private val nominalBase = ProtocolDriver(noError, off, durations)
        private val nominalArm = ProtocolDriver(noError, on, durations, arm.alteration())
        private val fullBase = ProtocolDriver(withError, off, durations)
        private val fullArm = ProtocolDriver(withError, on, durations, arm.alteration())

        val nominalLog get() = nominalArm to nominalBase

        val nominal = PairedBranchRun(
            noError, targets, offScale, watches, maxBranches = MAX_BRANCHES, maxBranchFrames = MAX_BRANCH_FRAMES, queuedForArm = onScale,
            startExtra = nominalBase.startExtra(),
            startCooldown = { if (it) nominalArm.startCooldown() else nominalBase.startCooldown() },
            startArmExtra = nominalArm.startExtra(),
        )
        val full = PairedBranchRun(
            withError, targets, offScale, watches, maxBranches = MAX_BRANCHES, maxBranchFrames = MAX_BRANCH_FRAMES, queuedForArm = onScale,
            startExtra = fullBase.startExtra(),
            startCooldown = { if (it) fullArm.startCooldown() else fullBase.startCooldown() },
            startArmExtra = fullArm.startExtra(),
        )
        var nanos = 0L

        fun onScene(scene: RegressionReplay.SceneFrame) {
            val t = scene.decidedAt
            for (driver in listOf(nominalBase, nominalArm)) driver.onScene(scene)
            nominal.onFrame(t, nominalBase, nominalArm)
            val before = System.nanoTime()
            for (driver in listOf(fullBase, fullArm)) driver.onScene(scene)
            full.onFrame(t, fullBase, fullArm)
            nanos += System.nanoTime() - before
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

        val firing = mutableSetOf<Long>()
        var nextChatter = Long.MIN_VALUE
        RegressionReplay(perception, analysis, semantics).replayScenes(session.regressionFrames, ::classMap, lost, geometry.evaluation, BirthOrderTrackIds()) { scene ->
            if (scene.decidedAt >= nextChatter) {
                nextChatter = scene.decidedAt + 10_000
                firing += scene.record.seq
            }
        }

        val deviceOffered = inputs.offers.mapTo(HashSet()) { it.atMs }
        val overruns = frameOverruns(session)
        val targets = ProtectedStages.of(device).map { PairedBranchRun.Target(StageKey(it.hazard, it.stage), it.completedAtNanos!! / 1_000_000 + 500, it.appearedAt) }
        val watches = device.stages.filter { !it.ambiguousOnly }.map { PairedBranchRun.Watch(StageKey(it.hazard, it.stage), it.endAt) }
        val sentences = SentenceDurations(durations, OTHER_C)
        val arms = listOf(
            Arm(NO_CHANGE, DetFirstConfig()),
            Arm(LAUNCH, DetFirstConfig.LAUNCH),
            Arm(MUTED, DetFirstConfig()) { MutedVehicles() },
            Arm(CHATTER, DetFirstConfig()) { Chatter(firing) },
        ) + SINGLE_SWITCHES.map { (name, config) -> Arm(name, config) }
        val stressRuns = (0 until STRESS_TRACES).map { trace ->
            val traced = SentenceDurations(durations, OTHER_C, errorTrace = trace)
            val kernel = ClosedLoopSpeakerBranches({ 0 }, heldAfterDone = false, frameSkewMs = 0)
            val off = ProtocolDriver(kernel, analysis, traced)
            val on = ProtocolDriver(kernel, analysis.copy(detFirst = DetFirstConfig.LAUNCH), traced)
            Triple(
                off,
                on,
                PairedBranchRun(
                    kernel, targets, inputs.queued.onSpeechScale(DetFirstConfig()), watches, maxBranches = 1, maxBranchFrames = MAX_BRANCH_FRAMES, queuedForArm = inputs.queued.onSpeechScale(DetFirstConfig.LAUNCH),
                    startExtra = off.startExtra(),
                    startCooldown = { armSide -> if (armSide) on.startCooldown() else off.startCooldown() },
                    startArmExtra = on.startExtra(),
                ),
            )
        }
        val runs = arms.associateWith { ArmRuns(it, analysis, sentences, inputs.queued, targets, watches) }

        // The baseline with no error, for what M5 asks of it: does it reproduce the device?
        val noError = ClosedLoopSpeakerBranches({ 0 }, heldAfterDone = false, frameSkewMs = 0)
        val baseA = ProtocolDriver(noError, analysis, sentences)
        val baseB = ProtocolDriver(noError, analysis, sentences)
        val forever = targets.map { PairedBranchRun.Watch(it.key, Long.MAX_VALUE) }
        val baselineFirst = PairedBranchRun(noError, emptyList(), inputs.queued.onSpeechScale(DetFirstConfig()), forever, startExtra = baseA.startExtra())

        // The legacy path with no error, by this class's own code, against the frozen model.
        val generator = EventGenerator(analysis)
        val resolver = EventConflictResolver()
        val merger = EventMerger()
        val episodes = StateEpisodes()
        val legacy = ClosedLoopRun(analysis, durations, inputs.queued, ClosedLoopRun.Protocol.LEGACY)
        val legacyFirst = PairedBranchRun(noError, emptyList(), inputs.queued, forever)
        val births = BirthOrderTrackIds()
        val samples = HashMap<String, MutableList<LeadTime.Sample>>()
        val gateFrames = HashMap<String, MutableList<Pair<Long, String?>>>()
        var firstAt: Long? = null
        var lastAt = 0L
        var stationaryMs = 0L
        var frames = 0
        val removed = HashMap<String, Pair<Int, MutableSet<String>>>()
        val conflictOf = mapOf(NO_CHANGE to (generator to EventConflictResolver()))
        val launchGenerator = EventGenerator(analysis.copy(detFirst = DetFirstConfig.LAUNCH))
        val launchGate = DetFirstConfig.LAUNCH.metricGate!!
        RegressionReplay(perception, analysis, semantics).replayScenes(session.regressionFrames, ::classMap, lost, geometry.evaluation, births) { scene ->
            val pitch = geometry.evaluation(scene.record)?.pitchDownDegrees
            legacy.onScene(scene, pitch)
            val t = scene.decidedAt
            frames++
            if (firstAt == null) firstAt = t else if (scene.stationary) stationaryMs += t - lastAt
            lastAt = t
            for ((name, pair) in conflictOf + (LAUNCH to (launchGenerator to EventConflictResolver(DetFirstConfig.LAUNCH.keepBlockedWithObstacle)))) {
                val (eventsFrom, resolve) = pair
                val all = eventsFrom.generate(scene.snapshot, t)
                val kept = resolve.resolve(all).toSet()
                val gone = all.filter { it !in kept && (it.isNear || it.priority == EventPriority.CRITICAL) }
                if (gone.isNotEmpty()) {
                    val (frames, hazards) = removed[name] ?: (0 to mutableSetOf())
                    hazards += gone.flatMap { event -> event.components.ifEmpty { null }?.map { it.primaryTrackId.toString() } ?: listOf(event.messageKey) }
                    removed[name] = (frames + 1) to hazards
                }
            }
            for (o in scene.snapshot.obstacles) {
                LeadTime.freshSample(o, scene.record.seq, pitch)?.let { samples.getOrPut(o.id.toString()) { mutableListOf() } += it }
                val hold = if (launchGenerator.passesTodaysGates(o)) null else launchGenerator.metricGateHold(o, launchGate)
                gateFrames.getOrPut(o.id.toString()) { mutableListOf() } += scene.decidedAt to hold
            }
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
            val decide = PairedBranchRun.Decider { cooldown: CooldownManager ->
                val primary = DecideEventsUseCase(generator, resolver, merger, cooldown, sensors, clock = { t })(scene.snapshot).firstOrNull() ?: return@Decider null
                val wording = primary.messageKey to primary.isNear
                val id = "${scene.record.seq}|${primary.messageKey}|${primary.isNear}|${primary.priority.value}|${primary.relatedZones}"
                val credits = if (primary.category in StateEpisodes.TRACKLESS) {
                    listOfNotNull(episodes.current(StateEpisodes.keyOf(primary), t)?.let { StageKey(it.id, Stage.FIRST) })
                } else {
                    StageCredit.credits(StageCredit.spoken(primary), groups, generator).filter { it.counts }.map { StageKey(it.trackId.toString(), it.stage) }
                }
                PairedBranchRun.Decision(
                    SpeakerModel.Offer(t, id, primary.priority.value, primary.expiresAt, durations.of(wording).second),
                    credits,
                    onAccepted = { it.confirm(primary.id) },
                    onRefused = { it.revoke(primary.id) },
                )
            }
            legacyFirst.onFrame(t, decide, decide)
            baseA.onScene(scene)
            baseB.onScene(scene)
            baselineFirst.onFrame(t, baseA, baseB)
            runs.values.forEach { it.onScene(scene) }
            for ((off, on, run) in stressRuns) {
                off.onScene(scene)
                on.onScene(scene)
                run.onFrame(t, off, on)
            }
        }
        val baselineCompletions = baselineFirst.finish().baseline
        val frozenLegacy = ProtectedStages.Reproduction.of(device, legacy.finish(births))
        return CaptureResult(
            result.id,
            frozenLegacy.withModel(targets.associate { it.key to baselineCompletions.completionMs(it.key)?.let { ms -> ms * 1_000_000 } }),
            device,
            targets.associate { it.key to it.deadlineMs },
            legacyFirst.finish().baseline,
            frozenLegacy,
            samples,
            runs.entries.associate { (arm, r) -> arm.name to ArmResult(r.nominal.finish(), r.full.finish(), r.nanos, r.nominalLog.first, r.nominalLog.second) },
            gateFrames,
            firstAt ?: 0L,
            frames,
            ((lastAt - (firstAt ?: lastAt)) / 60_000.0),
            stationaryMs / 60_000.0,
            arms.map { it.name }.associateWith { name -> removed[name]?.let { it.first to it.second.size } ?: (0 to 0) },
            stressRuns.map { it.third.finish() },
            UnmodeledFrom(inputs.windows.map { it.first }, overruns),
            runs.entries.associate { (arm, r) -> arm.name to r.nominalLog.first.offered.values.count { it.event != null && it.atMs !in deviceOffered } },
            sentences,
        )
    }

    /** Frames whose prompt took past the branches' skew to reach the speaker or to flush it. */
    private fun frameOverruns(session: CaptureSession): List<Long> {
        val records = session.utterances.sortedBy { it.elapsedRealtimeNanos }
        val first = HashMap<String, Double>()
        for (r in records) if (r.event == UtteranceRecordEvents.SUBMITTED || r.event == UtteranceRecordEvents.REFUSED) first.putIfAbsent(r.logicalId ?: r.attemptId, r.elapsedRealtimeNanos / 1e6)
        val decisions = session.regressionFrames.filter { it.events.isNotEmpty() }.mapNotNull { f -> f.clocks[RegressionClocks.DECIDE]?.lastOrNull()?.let { it to f } }.sortedBy { it.first }
        val over = mutableListOf<Long>()
        for ((at, frame) in decisions) first[frame.events.first().id]?.let { if (it - at > ClosedLoopSpeakerBranches.FRAME_SKEW_MS) over += at.toLong() }
        for (r in records) {
            if (r.event != UtteranceRecordEvents.SUPERSEDED) continue
            val now = r.elapsedRealtimeNanos / 1e6
            decisions.lastOrNull { it.first <= now }?.let { (at, _) -> if (now - at > ClosedLoopSpeakerBranches.FRAME_SKEW_MS) over += at.toLong() }
        }
        return over
    }

    private companion object {
        val PRESET_HEIGHT_M = System.getProperty("sailens.m3a.phoneHeight").orEmpty().toFloatOrNull() ?: 1.15f
        const val HEARD_WITHIN_MS = 5_000L
        const val STRESS_TRACES = 4

        /** What walking 1.2 m/s covers in the time to take in a warning and stop: below it a warning comes too late to act on. */
        const val USEFUL_WARNING_M = 1.0f
        const val OTHER_C = "other C captures"
        const val M1_THRESHOLD_M = 2.0f
        const val FIRST_SENTENCE_MS = 1_250L
        val NOMINAL_ONLY = System.getProperty("sailens.replay.singleSwitches") in setOf("nominal", "launch", "without")
        val MAX_BRANCHES = if (NOMINAL_ONLY) 1 else 1_000_000
        const val MAX_BRANCH_FRAMES = 500_000_000L

        const val NO_CHANGE = "no change"
        const val LAUNCH = "launch"
        const val MUTED = "every vehicle prompt withheld"
        const val CHATTER = "a top-priority sentence every 10 s"

        /** For diagnosis: each switch alone, with what it needs. */
        val SINGLE_SWITCHES: List<Pair<String, DetFirstConfig>> = if (System.getProperty("sailens.replay.singleSwitches") in setOf(null, "launch")) {
            emptyList()
        } else if (System.getProperty("sailens.replay.singleSwitches") == "without") {
            val launch = DetFirstConfig.LAUNCH
            listOf(
                "launch without proximityEscalation" to launch.copy(proximityEscalation = false),
                "launch without retryInterrupted" to launch.copy(retryInterrupted = false),
                "launch without keepBlockedWithObstacle" to launch.copy(keepBlockedWithObstacle = false),
                "launch without nearMeters" to launch.copy(nearMeters = null),
                "launch without pathComplexOff" to launch.copy(pathComplexOff = false),
                "launch without metricGate" to launch.copy(metricGate = null, nearMeters = null),
            )
        } else {
            listOf(
                "proximityEscalation" to DetFirstConfig(proximityEscalation = true),
                "speechRank" to DetFirstConfig(speechRank = true),
                "retryInterrupted" to DetFirstConfig(retryInterrupted = true),
                "keepBlockedWithObstacle" to DetFirstConfig(keepBlockedWithObstacle = true),
                "metricGate" to DetFirstConfig(speechRank = true, metricGate = MetricGateConfig()),
                "nearMeters" to DetFirstConfig(speechRank = true, metricGate = MetricGateConfig(), nearMeters = 2.0f),
                "pathComplexOff" to DetFirstConfig(pathComplexOff = true),
            )
        }
    }
}
