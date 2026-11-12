package com.sailens.guidance.trace.regression

import com.sailens.guidance.geometry.GroundFitValidity
import com.sailens.guidance.geometry.GroundFitter
import com.sailens.guidance.geometry.GroundGuardState
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
     * is armed), and the state it leaves gives the next run's phone output the validity the phone's state gives it.
     * A call that goes differently counts on the run it happens, once; the next run starts from the phone's history
     * again, so a hold it would have started is not counted on every run after, while a difference that recurs is
     * counted every time.
     */
    val fitAgreement: Float,
    /** The first disagreeing runs, in order, and how many there were in all. */
    val disagreements: List<DepthFitDisagreement>,
    val disagreementCount: Int,
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

data class DepthEvidenceReport(
    val generation: Long,
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
 * With a [candidate] -- the same runs' outputs computed on a PC from the recorded planes -- the PC's outputs are
 * compared with the phone's, and fitted from the phone's history run by run ([DepthPcAgreement.fitAgreement],
 * [DepthEvidenceThresholds]). A second fitter also fits the PC's outputs in the session's order on its own, and its
 * agreement with the replay is reported ([DepthPcAgreement.sessionValidityAgreement]).
 */
class DepthEvidenceReplay(
    private val thresholds: DepthEvidenceThresholds = DepthEvidenceThresholds(),
    private val fitterFactory: () -> GroundFitter = { GroundFitter() },
    private val mismatchesKept: Int = 20,
) {
    /**
     * @param firstDropped the first run the stream dropped, from the manifest's counters
     *   (`DepthEvidenceStats.firstDroppedGeneration` and `firstDroppedRunId`): acceptance ends there too, even when
     *   the dropped run made no fit.
     */
    fun replay(
        records: List<DepthEvidenceRecord>,
        output: (DepthEvidenceRecord) -> FloatArray,
        candidate: ((DepthEvidenceRecord) -> FloatArray?)? = null,
        firstDropped: DroppedRun? = null,
    ): List<DepthEvidenceReport> =
        records.groupBy { it.generation }.toSortedMap().map { (generation, session) ->
            replaySession(generation, session, output, candidate, firstDropped?.takeIf { it.generation == generation }?.runId)
        }

    /** A run of depth session [generation] the stream counted as dropped. */
    data class DroppedRun(val generation: Long, val runId: Long)

    private fun replaySession(
        generation: Long,
        records: List<DepthEvidenceRecord>,
        output: (DepthEvidenceRecord) -> FloatArray,
        candidate: ((DepthEvidenceRecord) -> FloatArray?)?,
        firstDroppedRunId: Long?,
    ): DepthEvidenceReport {
        val fitted = records.filter { it.fitOrdinal != null }.sortedBy { it.fitOrdinal }
        val (acceptable, firstGap) = acceptablePrefix(fitted, firstDroppedRunId)

        val fitter = fitterFactory()
        val mismatches = mutableListOf<DepthReplayMismatch>()
        var mismatchCount = 0
        var exact = 0
        var maxScale = 0f
        var maxShift = 0f
        val replayed = ArrayList<GroundObservation>(acceptable.size)
        for (record in acceptable) {
            val ordinal = checkNotNull(record.fitOrdinal)
            val expected = record.observation
            val actual = fit(fitter, record, output(record))
            replayed += actual
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

        val pc = candidate?.let { pcAgreement(acceptable, replayed, output, it) }
        val (verdict, reason) = judge(acceptable.size, mismatchCount, pc, candidate != null)
        return DepthEvidenceReport(
            generation = generation,
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
            pc = pc,
            verdict = verdict,
            verdictReason = reason,
        )
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
        replayed: List<GroundObservation>,
        output: (DepthEvidenceRecord) -> FloatArray,
        candidate: (DepthEvidenceRecord) -> FloatArray?,
    ): DepthPcAgreement {
        // The phone's history, rebuilt in the session's order as the exact replay rebuilds it.
        val phoneFitter = fitterFactory()
        // The PC's own history, for the reported session-order agreement only.
        val sessionFitter = fitterFactory()
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
        // The state the PC's output left the previous run's copy in: this run's phone output is fitted from it once.
        var pcLeft: GroundFitter? = null
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
            // The previous run's PC output left a state: does it give this run what the phone's history gives?
            pcLeft?.let { left ->
                val next = fit(left, record, phoneValues)
                if (next.validity != phone.validity) disagree(index - 1, "next run", phone.validity.text(), next.validity.text())
            }
            if (pc.validity != phone.validity) {
                disagree(index, "validity", phone.validity.text(), pc.validity.text())
            } else if (shared.guardState() != phoneFitter.guardState()) {
                disagree(index, "guard", phoneFitter.guardState().text(), shared.guardState().text())
            }
            if (phone.isValid && pc.isValid) {
                labelSum += labelAgreement(phone, pc)
                labelRuns++
            }
            pcLeft = shared
            if (replayed[index].validity == fit(sessionFitter, record, values).validity) sessionValidityAgrees++
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
