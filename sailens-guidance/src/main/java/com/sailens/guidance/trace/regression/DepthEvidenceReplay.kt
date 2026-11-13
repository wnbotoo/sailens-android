package com.sailens.guidance.trace.regression

import com.sailens.guidance.geometry.GroundFitValidity
import com.sailens.guidance.geometry.GroundFitter
import com.sailens.guidance.geometry.GroundGuardState
import com.sailens.guidance.geometry.SupportGuardRule
import com.sailens.guidance.geometry.GroundObservation
import com.sailens.guidance.trace.capture.DepthEvidenceRecord
import com.sailens.guidance.trace.capture.DepthObservationRecord
import com.sailens.guidance.trace.capture.toRecord
import com.sailens.guidance.trace.capture.toTransform
import com.sailens.vision.consistency.compareDisparity
import com.sailens.vision.depth.DisparityMap
import kotlin.math.abs
import kotlin.math.max

/**
 * The bands the depth path is held to against its reproduction on a PC (docs/field-capture.md §11).
 *
 * @param minAcceptableRuns fewer acceptable runs than this cannot be judged, however well they agree.
 * @param maxMedianRelativeError the median, over the acceptable runs, of each run's median relative error
 *   (`compareDisparity`, the PC's output the reference): FP16 against FP32.
 * @param minFitAgreement the share of acceptable runs where the PC's output, fitted from the phone's history,
 *   agrees with the phone's ([DepthPcAgreement.fitAgreement]).
 * @param minLabelAgreement the mean, over the runs where both of those fits are valid, of the share of grid
 *   cells with the same label.
 */
data class DepthEvidenceThresholds(
    val minAcceptableRuns: Int = 300,
    val maxMedianRelativeError: Float = 0.01f,
    val minFitAgreement: Float = 0.95f,
    val minLabelAgreement: Float = 0.95f,
)

enum class DepthEvidenceVerdict {
    /** The phone's fit replays exactly and, with a PC output, the PC agrees within the bands. */
    PASS,

    /** A replayed fit differs from the phone's, or the PC is outside a band. */
    FAIL,

    /** Too few acceptable runs, or a PC output missing for any of them: neither pass nor fail. */
    CANNOT_JUDGE,
}

/** One replayed run that did not give what the phone recorded. */
data class DepthReplayMismatch(val runId: Long, val fitOrdinal: Long, val what: String, val expected: String, val actual: String)

/** A run where the PC's output, fitted from the phone's history, did not do what the phone's output did. */
data class DepthFitDisagreement(val runId: Long, val fitOrdinal: Long, val what: String, val phone: String, val pc: String)

/** The PC's output against the phone's, over the acceptable runs it covers. */
data class DepthPcAgreement(
    /** Acceptable runs with a PC output, from the first; the comparison stops at the first run without one. */
    val runs: Int,
    val medianRelativeError: Float,
    val p95RelativeError: Float,
    val maxRelativeError: Float,
    /**
     * Judged. Before each run, the phone's fitter (the replay's, in the session's order) is copied, history and all,
     * and the copy fits the PC's output. The run agrees when that fit has the phone's validity, leaves the guard as
     * the phone's fit leaves it ([com.sailens.guidance.geometry.GroundGuardState]: the hold, and whether the guard
     * is armed), and the history it leaves gives every later run's phone output the validity the phone's history
     * gives it, for as long as the two histories differ: the copy goes on fitting the phone's outputs until its
     * history is the phone's ([GroundFitter.sameGuardHistory]), which the next valid fit on both makes it. A fit
     * that returns early keeps the history it found, so the check carries on past it. A call that goes differently
     * counts once, on the run where it happens, whenever its consequence shows; a difference that recurs counts
     * every time.
     */
    val fitAgreement: Float,
    /** The first disagreeing runs, in the order found, and how many there were in all. */
    val disagreements: List<DepthFitDisagreement>,
    val disagreementCount: Int,
    /** Runs whose PC output left a different history that neither showed nor settled before the comparison ended. */
    val unsettledAtEnd: Int,
    /** Mean label agreement over [labelRuns], the runs where both of those fits are valid; null when none were. */
    val labelAgreement: Float?,
    val labelRuns: Int,
    /**
     * Validity agreement with each side's own fitter fitting the session in order: reported, not judged. The
     * support-switch hold ends only when two levels are seen together again, so one call that goes differently
     * keeps the two apart until then.
     */
    val sessionValidityAgreement: Float,
    /** Where the comparison stopped early, when it did. */
    val stoppedAt: String?,
)

/**
 * How the support-switch guard went over a session's fits: [switches] fits gave `SUPPORT_SWITCH`, [holdsBegun] of them
 * began a hold, and the hold stayed on for at most [longestHold] fits in a row -- the fits that returned early while
 * it was on included, which is not the same count as a run of `SUPPORT_SWITCH` results.
 */
data class GuardSummary(val switches: Int, val holdsBegun: Int, val longestHold: Int)

data class DepthEvidenceReport(
    val generation: Long,
    /** The guard rule the capture names ([SupportGuardRule.id]); null when it names none (recorded before rules were). */
    val recordedRule: Int?,
    /** The rule the PC comparison and [evaluatedGuard] fit with: the recorded one unless another was asked for. */
    val evaluatedRule: Int,
    val records: Int,
    val fittedRecords: Int,
    val nonFiniteRecords: Int,
    /** Fitted runs from the session's first fit up to the first gap: the only ones a replay can stand on. */
    val acceptableRuns: Int,
    /** Why acceptance ends before the last fitted record, when it does. */
    val firstGap: String?,
    val exactRuns: Int,
    val mismatches: List<DepthReplayMismatch>,
    val mismatchCount: Int,
    /** Largest differences of the fit's numbers on runs that matched otherwise (ART and HotSpot may differ by an ulp). */
    val maxScaleDifference: Float,
    val maxShiftDifference: Float,
    /** The guard over the exact replay (the recorded rule), and over the phone's outputs fitted with the evaluated rule. */
    val recordedGuard: GuardSummary?,
    val evaluatedGuard: GuardSummary?,
    val pc: DepthPcAgreement?,
    val verdict: DepthEvidenceVerdict,
    val verdictReason: String,
)

/**
 * Redoes the depth evidence stream's fits on the JVM. One [GroundFitter] per depth session from its first fit,
 * fed the phone's outputs in the fitter's own call order; each result must give the masks the phone recorded
 * (CRC32), its validity and gravity agreement. The fitter's history has no expiry, so the replay can stand only
 * on an unbroken run of fits from the session's start: it ends at the first missing ordinal, or at the first run
 * the stream counted as dropped, and nothing after a gap counts, however many runs follow.
 *
 * Two guard rules can be at play ([SupportGuardRule]). The exact replay always fits with the rule the capture was
 * recorded with: it checks the recording, and a summary recorded under one rule never matches a fit under another.
 * The rest -- the PC comparison and [DepthEvidenceReport.evaluatedGuard] -- fits with the evaluated rule, the recorded
 * one unless another is asked for: the phone's outputs are fitted again under it from each depth session's first fit,
 * and that history is what the PC's outputs are compared from. A capture that names a rule this build does not know
 * cannot be judged.
 *
 * With a [candidate] -- the same runs' outputs computed on a PC from the recorded planes -- the PC's outputs are
 * compared with the phone's, and fitted from the phone's history run by run ([DepthPcAgreement.fitAgreement],
 * [DepthEvidenceThresholds]). A second fitter also fits the PC's outputs in the session's order on its own, and its
 * agreement with the phone's history is reported ([DepthPcAgreement.sessionValidityAgreement]).
 */
class DepthEvidenceReplay(
    private val thresholds: DepthEvidenceThresholds = DepthEvidenceThresholds(),
    private val fitterFactory: (SupportGuardRule) -> GroundFitter = { GroundFitter(guardRule = it) },
    private val mismatchesKept: Int = 20,
) {
    /**
     * @param recordedRule the capture's `depthEvidence.groundFitRule`, as recorded: null when it names none.
     * @param evaluatedRule the rule to compare the PC with and to summarise the guard under; null for the recorded one.
     * @param firstDropped the first run the stream dropped, from the manifest's counters
     *   (`DepthEvidenceStats.firstDroppedGeneration` and `firstDroppedRunId`): acceptance ends there too, even when
     *   the dropped run made no fit.
     */
    fun replay(
        records: List<DepthEvidenceRecord>,
        output: (DepthEvidenceRecord) -> FloatArray,
        recordedRule: Int?,
        candidate: ((DepthEvidenceRecord) -> FloatArray?)? = null,
        firstDropped: DroppedRun? = null,
        evaluatedRule: SupportGuardRule? = null,
    ): List<DepthEvidenceReport> {
        val recorded = SupportGuardRule.recorded(recordedRule)
        return records.groupBy { it.generation }.toSortedMap().map { (generation, session) ->
            if (recorded == null) {
                unknownRule(generation, session, recordedRule, evaluatedRule)
            } else {
                replaySession(
                    generation, session, output, candidate, firstDropped?.takeIf { it.generation == generation }?.runId,
                    recordedRule, recorded, evaluatedRule ?: recorded,
                )
            }
        }
    }

    /** A run of depth session [generation] the stream counted as dropped. */
    data class DroppedRun(val generation: Long, val runId: Long)

    private fun unknownRule(generation: Long, records: List<DepthEvidenceRecord>, recordedRule: Int?, evaluatedRule: SupportGuardRule?) =
        DepthEvidenceReport(
            generation = generation,
            recordedRule = recordedRule,
            evaluatedRule = (evaluatedRule ?: SupportGuardRule.CURRENT).id,
            records = records.size,
            fittedRecords = records.count { it.fitOrdinal != null },
            nonFiniteRecords = records.count { it.fitOrdinal == null },
            acceptableRuns = 0,
            firstGap = null,
            exactRuns = 0,
            mismatches = emptyList(),
            mismatchCount = 0,
            maxScaleDifference = 0f,
            maxShiftDifference = 0f,
            recordedGuard = null,
            evaluatedGuard = null,
            pc = null,
            verdict = DepthEvidenceVerdict.CANNOT_JUDGE,
            verdictReason = "recorded with guard rule $recordedRule, which this build does not know",
        )

    private fun replaySession(
        generation: Long,
        records: List<DepthEvidenceRecord>,
        output: (DepthEvidenceRecord) -> FloatArray,
        candidate: ((DepthEvidenceRecord) -> FloatArray?)?,
        firstDroppedRunId: Long?,
        recordedRule: Int?,
        recorded: SupportGuardRule,
        evaluated: SupportGuardRule,
    ): DepthEvidenceReport {
        val fitted = records.filter { it.fitOrdinal != null }.sortedBy { it.fitOrdinal }
        val (acceptable, firstGap) = acceptablePrefix(fitted, firstDroppedRunId)

        val fitter = fitterFactory(recorded)
        val recordedGuard = GuardTally(fitter)
        val mismatches = mutableListOf<DepthReplayMismatch>()
        var mismatchCount = 0
        var exact = 0
        var maxScale = 0f
        var maxShift = 0f
        for (record in acceptable) {
            val ordinal = checkNotNull(record.fitOrdinal)
            val expected = record.observation
            val actual = recordedGuard.fit { fit(it, record, output(record)) }
            val problem = if (expected == null) {
                Triple("observation", "a recorded fit", "none recorded")
            } else {
                differs(expected, actual.toRecord())
            }
            if (expected != null && problem == null) {
                exact++
                maxScale = max(maxScale, abs(expected.disparityScale - actual.disparityScale))
                maxShift = max(maxShift, abs(expected.disparityShift - actual.disparityShift))
            } else if (problem != null) {
                mismatchCount++
                if (mismatches.size < mismatchesKept) mismatches += DepthReplayMismatch(record.runId, ordinal, problem.first, problem.second, problem.third)
            }
        }

        val evaluatedGuard = if (evaluated == recorded) {
            recordedGuard.summary()
        } else {
            GuardTally(fitterFactory(evaluated)).also { tally -> acceptable.forEach { record -> tally.fit { fit(it, record, output(record)) } } }.summary()
        }
        val pc = candidate?.let { pcAgreement(acceptable, output, it, evaluated) }
        val (verdict, reason) = judge(acceptable.size, mismatchCount, pc, candidate != null)
        return DepthEvidenceReport(
            generation = generation,
            recordedRule = recordedRule,
            evaluatedRule = evaluated.id,
            records = records.size,
            fittedRecords = fitted.size,
            nonFiniteRecords = records.count { it.fitOrdinal == null },
            acceptableRuns = acceptable.size,
            firstGap = firstGap,
            exactRuns = exact,
            mismatches = mismatches,
            mismatchCount = mismatchCount,
            maxScaleDifference = maxScale,
            maxShiftDifference = maxShift,
            recordedGuard = recordedGuard.summary(),
            evaluatedGuard = evaluatedGuard,
            pc = pc,
            verdict = verdict,
            verdictReason = reason,
        )
    }

    /** Counts what the guard does over one fitter's fits, in order. */
    private class GuardTally(val fitter: GroundFitter) {
        private var switches = 0
        private var holdsBegun = 0
        private var holding = 0
        private var longest = 0

        fun fit(call: (GroundFitter) -> GroundObservation): GroundObservation {
            val before = fitter.guardState().holdingSwitch
            val observation = call(fitter)
            if (observation.validity == GroundFitValidity.SUPPORT_SWITCH) {
                switches++
                if (!before) holdsBegun++
            }
            if (fitter.guardState().holdingSwitch) {
                holding++
                longest = max(longest, holding)
            } else {
                holding = 0
            }
            return observation
        }

        fun summary() = GuardSummary(switches, holdsBegun, longest)
    }

    /**
     * The fitted records from ordinal 0 up to the first missing, repeated or out-of-order one, or the first at or
     * after a run the stream dropped.
     */
    private fun acceptablePrefix(fitted: List<DepthEvidenceRecord>, firstDroppedRunId: Long?): Pair<List<DepthEvidenceRecord>, String?> {
        var expected = 0L
        var lastRunId = Long.MIN_VALUE
        for ((index, record) in fitted.withIndex()) {
            val ordinal = checkNotNull(record.fitOrdinal)
            val gap = when {
                firstDroppedRunId != null && record.runId >= firstDroppedRunId ->
                    "the stream dropped run $firstDroppedRunId (fit call $ordinal is run ${record.runId})"
                ordinal != expected && expected == 0L -> "the first recorded fit is call $ordinal, not the session's first"
                ordinal != expected -> "fit call $expected is missing (next recorded: $ordinal, run ${record.runId})"
                record.runId <= lastRunId -> "fit call $ordinal has run id ${record.runId}, not after $lastRunId"
                else -> null
            }
            if (gap != null) return fitted.subList(0, index) to gap
            expected++
            lastRunId = record.runId
        }
        return fitted to null
    }

    private fun fit(fitter: GroundFitter, record: DepthEvidenceRecord, values: FloatArray): GroundObservation {
        val geometry = record.geometry.toGeometry()
        val map = DisparityMap(record.output.width, record.output.height, values, record.transform.toTransform())
        return fitter.fit(
            map,
            checkNotNull(geometry.intrinsics) { "run ${record.runId}: fitted without intrinsics" },
            checkNotNull(geometry.gravityInCamera) { "run ${record.runId}: fitted without gravity" },
            record.gridWidth,
            record.gridHeight,
            geometry.frameTimeNanos,
        )
    }

    private fun differs(expected: DepthObservationRecord, actual: DepthObservationRecord): Triple<String, String, String>? = when {
        expected.validity != actual.validity -> Triple("validity", expected.validity, actual.validity)
        expected.groundMaskCrc32 != actual.groundMaskCrc32 ->
            Triple("ground mask", "${expected.groundCells} cells", "${actual.groundCells} cells")
        expected.aboveGroundMaskCrc32 != actual.aboveGroundMaskCrc32 ->
            Triple("above-ground mask", "${expected.aboveGroundCells} cells", "${actual.aboveGroundCells} cells")
        expected.belowGroundMaskCrc32 != actual.belowGroundMaskCrc32 ->
            Triple("below-ground mask", "${expected.belowGroundCells} cells", "${actual.belowGroundCells} cells")
        expected.planeAgreesWithGravity != actual.planeAgreesWithGravity ->
            Triple("plane agrees with gravity", "${expected.planeAgreesWithGravity}", "${actual.planeAgreesWithGravity}")
        else -> null
    }

    private fun pcAgreement(
        acceptable: List<DepthEvidenceRecord>,
        output: (DepthEvidenceRecord) -> FloatArray,
        candidate: (DepthEvidenceRecord) -> FloatArray?,
        rule: SupportGuardRule,
    ): DepthPcAgreement {
        // The phone's history under [rule], rebuilt in the session's order: under the recorded rule, the exact replay's.
        val phoneFitter = fitterFactory(rule)
        // The PC's own history, for the reported session-order agreement only.
        val sessionFitter = fitterFactory(rule)
        val errors = ArrayList<Float>(acceptable.size)
        val agrees = ArrayList<Boolean>(acceptable.size)
        val disagreements = mutableListOf<DepthFitDisagreement>()
        var disagreementCount = 0
        fun disagree(index: Int, what: String, phone: String, pc: String) {
            if (!agrees[index]) return
            agrees[index] = false
            disagreementCount++
            val record = acceptable[index]
            if (disagreements.size < mismatchesKept) disagreements += DepthFitDisagreement(record.runId, checkNotNull(record.fitOrdinal), what, phone, pc)
        }
        var sessionValidityAgrees = 0
        var labelSum = 0.0
        var labelRuns = 0
        var stoppedAt: String? = null
        // Copies whose history a PC output left different from the phone's: each follows the phone's outputs until
        // its history is the phone's, or a later run's validity shows the difference.
        class Branch(val origin: Int, val fitter: GroundFitter)
        var branches = mutableListOf<Branch>()
        for ((index, record) in acceptable.withIndex()) {
            val values = candidate(record)
            if (values == null) {
                // The PC fitter's history breaks here, as the phone's would at a gap.
                stoppedAt = "no PC output for run ${record.runId} (fit call ${record.fitOrdinal})"
                break
            }
            val transform = record.transform.toTransform()
            val pcMap = DisparityMap(record.output.width, record.output.height, values, transform)
            // Read again rather than held from the exact replay: a session's maps do not stay in memory.
            val phoneValues = output(record)
            val phoneMap = DisparityMap(record.output.width, record.output.height, phoneValues, transform)
            errors += compareDisparity(reference = pcMap, candidate = phoneMap).medianRelativeError

            val shared = phoneFitter.copy()
            val pc = fit(shared, record, values)
            val phone = fit(phoneFitter, record, phoneValues)
            agrees += true
            // Histories earlier PC outputs left: does this run's phone output show them?
            val following = mutableListOf<Branch>()
            for (branch in branches) {
                val later = fit(branch.fitter, record, phoneValues)
                when {
                    later.validity != phone.validity -> disagree(
                        branch.origin, "run ${record.runId} (fit ${record.fitOrdinal}) from its history", phone.validity.text(), later.validity.text(),
                    )
                    !branch.fitter.sameGuardHistory(phoneFitter) -> following += branch
                }
            }
            branches = following
            if (pc.validity != phone.validity) {
                disagree(index, "validity", phone.validity.text(), pc.validity.text())
            } else if (shared.guardState() != phoneFitter.guardState()) {
                disagree(index, "guard", phoneFitter.guardState().text(), shared.guardState().text())
            } else if (!shared.sameGuardHistory(phoneFitter)) {
                branches += Branch(index, shared)
            }
            if (phone.isValid && pc.isValid) {
                labelSum += labelAgreement(phone, pc)
                labelRuns++
            }
            if (phone.validity == fit(sessionFitter, record, values).validity) sessionValidityAgrees++
        }
        val sorted = errors.sorted()
        val n = sorted.size
        return DepthPcAgreement(
            runs = n,
            medianRelativeError = if (n > 0) sorted[n / 2] else Float.POSITIVE_INFINITY,
            p95RelativeError = if (n > 0) sorted[((n - 1) * 95) / 100] else Float.POSITIVE_INFINITY,
            maxRelativeError = sorted.lastOrNull() ?: Float.POSITIVE_INFINITY,
            fitAgreement = if (n > 0) (n - disagreementCount).toFloat() / n else 0f,
            disagreements = disagreements,
            disagreementCount = disagreementCount,
            unsettledAtEnd = branches.size,
            labelAgreement = if (labelRuns > 0) (labelSum / labelRuns).toFloat() else null,
            labelRuns = labelRuns,
            sessionValidityAgreement = if (n > 0) sessionValidityAgrees.toFloat() / n else 0f,
            stoppedAt = stoppedAt,
        )
    }

    private fun GroundFitValidity.text(): String = name.lowercase()

    private fun GroundGuardState.text(): String =
        "${if (holdingSwitch) "holding" else "not holding"}, ${if (armed) "armed" else "not armed"}"

    private fun judge(acceptable: Int, mismatches: Int, pc: DepthPcAgreement?, pcGiven: Boolean): Pair<DepthEvidenceVerdict, String> {
        if (mismatches > 0) return DepthEvidenceVerdict.FAIL to "$mismatches replayed fit(s) differ from the phone's"
        if (acceptable < thresholds.minAcceptableRuns) {
            return DepthEvidenceVerdict.CANNOT_JUDGE to "$acceptable acceptable run(s), fewer than ${thresholds.minAcceptableRuns}"
        }
        if (!pcGiven) return DepthEvidenceVerdict.CANNOT_JUDGE to "the fit replays exactly; no PC output to compare with"
        val agreement = checkNotNull(pc)
        // Every acceptable run needs its PC output: comparing the first ones and leaving the rest out is not a pass,
        // however many came before the gap.
        agreement.stoppedAt?.let { return DepthEvidenceVerdict.CANNOT_JUDGE to "PC outputs incomplete: $it" }
        if (agreement.runs < thresholds.minAcceptableRuns) {
            return DepthEvidenceVerdict.CANNOT_JUDGE to "PC output for ${agreement.runs} acceptable run(s), fewer than ${thresholds.minAcceptableRuns}"
        }
        val outside = buildList {
            if (agreement.medianRelativeError > thresholds.maxMedianRelativeError) add("median relative error ${agreement.medianRelativeError}")
            if (agreement.fitAgreement < thresholds.minFitAgreement) add("fit agreement ${agreement.fitAgreement}")
            agreement.labelAgreement?.let { if (it < thresholds.minLabelAgreement) add("label agreement $it") }
        }
        return if (outside.isEmpty()) {
            DepthEvidenceVerdict.PASS to "${agreement.runs} acceptable runs within every band"
        } else {
            DepthEvidenceVerdict.FAIL to "outside the bands: ${outside.joinToString()}"
        }
    }
}

/** Ground/above/below the same, cell by cell, over the grid: the measure of the consistency check. */
internal fun labelAgreement(a: GroundObservation, b: GroundObservation): Float {
    val width = a.groundMask.width
    val height = a.groundMask.height
    var same = 0
    for (y in 0 until height) for (x in 0 until width) {
        if (a.groundMask.get(x, y) == b.groundMask.get(x, y) &&
            a.aboveGroundMask.get(x, y) == b.aboveGroundMask.get(x, y) &&
            a.belowGroundMask.get(x, y) == b.belowGroundMask.get(x, y)
        ) same++
    }
    return same.toFloat() / (width * height)
}
