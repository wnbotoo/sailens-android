package com.sailens.vision.consistency

import com.sailens.vision.depth.DisparityMap
import kotlin.math.abs
import kotlin.math.max

/**
 * The depth model's accelerator check: the same comparison as for the other models (the CPU's
 * output is the reference), for a disparity map.
 *
 * Correct exports agreed with the CPU to 0.3–0.5% median relative error on the GPU, in FP32 and FP16;
 * the broken ones (position-embedding interpolation left in the graph, token activations broadcast
 * between ranks 2 and 3) were off by 60–97% without any error being raised. The ground fit on the two
 * outputs is compared too, because that is what the disparity is for: the caller fits both (the fit
 * is Guidance's) and hands the per-frame agreement in as a [GroundFitAgreement].
 */
data class DisparityAgreement(
    /**
     * Median over the content pixels of |candidate − reference| / max(|reference|, 5% of max|reference|). Where that
     * denominator is 0 (a zero reference value with a zero maximum) equal values are no error and different ones an
     * infinite one.
     */
    val medianRelativeError: Float,
    val p95RelativeError: Float,
    val pixelCount: Int,
)

fun compareDisparity(reference: DisparityMap, candidate: DisparityMap): DisparityAgreement {
    require(reference.width == candidate.width && reference.height == candidate.height) {
        "maps differ in size: ${reference.width}x${reference.height} vs ${candidate.width}x${candidate.height}"
    }
    val columns = reference.contentColumns
    val rows = reference.contentRows
    var maxAbs = 0f
    for (y in rows) for (x in columns) maxAbs = max(maxAbs, abs(reference.values[y * reference.width + x]))
    val floor = 0.05f * maxAbs
    val errors = FloatArray((rows.last - rows.first + 1) * (columns.last - columns.first + 1))
    var n = 0
    for (y in rows) for (x in columns) {
        val i = y * reference.width + x
        val r = reference.values[i]
        val c = candidate.values[i]
        val denominator = max(abs(r), floor)
        // A zero denominator is not an error in itself: two equal values agree, whatever they are.
        val e = if (denominator > 0f) abs(c - r) / denominator else if (c == r) 0f else Float.POSITIVE_INFINITY
        // A non-finite value is as wrong as a value can be.
        errors[n++] = if (e.isFinite()) e else Float.POSITIVE_INFINITY
    }
    errors.sort(0, n)
    return DisparityAgreement(
        medianRelativeError = if (n > 0) errors[n / 2] else Float.POSITIVE_INFINITY,
        p95RelativeError = if (n > 0) errors[((n - 1) * 95) / 100] else Float.POSITIVE_INFINITY,
        pixelCount = n,
    )
}

/** One frame's ground-fit agreement. Absent (null in [DepthFrameAgreement]) when the frame had no geometry. */
data class GroundFitAgreement(
    val validityAgrees: Boolean,
    val referenceValid: Boolean,
    /** Ground/above/below agreement over the label grid; null unless both fits are valid. */
    val labelAgreement: Float?,
)

/**
 * One frame's comparison. [candidateNonFinite] and [referenceNonFinite] count NaN or infinite values in the
 * accelerator's and the CPU's output: any one is a failed run (docs/models.md, the `depth` contract), whatever the
 * statistics of the other values say, so such a frame is not fitted and fails the check.
 */
data class DepthFrameAgreement(
    val disparity: DisparityAgreement,
    val fit: GroundFitAgreement?,
    val candidateNonFinite: Int = 0,
    val referenceNonFinite: Int = 0,
) {
    val hasNonFiniteOutput: Boolean get() = candidateNonFinite > 0 || referenceNonFinite > 0
}

/**
 * The bands, from the measured exports above. The fit counts are out of [framesRequired], never out of
 * the frames that happened to have geometry: a check that lost its gravity samples cannot pass on the
 * few frames it kept.
 */
data class DepthConsistencyThresholds(
    val framesRequired: Int = 8,
    val maxPassMedianRelativeError: Float = 0.02f,
    val failAboveMedianRelativeError: Float = 0.10f,
    val minPassFitAgreeingFrames: Int = 7,
    val failAtOrBelowFitAgreeingFrames: Int = 4,
    val minLabelAgreement: Float = 0.95f,
)

data class DepthConsistency(
    val frames: List<DepthFrameAgreement>,
    val maxMedianRelativeError: Float,
    val framesWithGeometry: Int,
    val fitAgreeingFrames: Int,
    /** Lowest label agreement on frames where both fits were valid; null when there were none. */
    val minLabelAgreement: Float?,
    val verdict: ConsistencyVerdict,
    /** Frames where either output had a NaN or infinite value. */
    val nonFiniteOutputFrames: Int = 0,
)

/**
 * FAIL when any frame's output, on either side, has a NaN or infinite value; when any frame's disparity is off by
 * more than the fail band; or when at least
 * [DepthConsistencyThresholds.minPassFitAgreeingFrames] frames had geometry and the fits agreed on
 * validity in no more than [DepthConsistencyThresholds.failAtOrBelowFitAgreeingFrames]. PASS when every
 * frame's disparity is within the pass band, the fits agreed on at least
 * [DepthConsistencyThresholds.minPassFitAgreeingFrames] of [DepthConsistencyThresholds.framesRequired]
 * frames, and the labels agreed where both were valid. Anything else, including too few frames with
 * geometry to judge the fit, is INCONCLUSIVE.
 */
fun judgeDepth(
    frames: List<DepthFrameAgreement>,
    thresholds: DepthConsistencyThresholds = DepthConsistencyThresholds(),
): DepthConsistency {
    val maxMedian = frames.maxOfOrNull { it.disparity.medianRelativeError } ?: Float.POSITIVE_INFINITY
    val withGeometry = frames.count { it.fit != null }
    val agreeing = frames.count { it.fit?.validityAgrees == true }
    val labels = frames.mapNotNull { it.fit?.labelAgreement }
    val minLabel = labels.minOrNull()
    val fitJudgeable = withGeometry >= thresholds.minPassFitAgreeingFrames
    val nonFinite = frames.count { it.hasNonFiniteOutput }
    val verdict = when {
        frames.isEmpty() -> ConsistencyVerdict.INCONCLUSIVE
        nonFinite > 0 -> ConsistencyVerdict.FAIL
        maxMedian > thresholds.failAboveMedianRelativeError -> ConsistencyVerdict.FAIL
        fitJudgeable && agreeing <= thresholds.failAtOrBelowFitAgreeingFrames -> ConsistencyVerdict.FAIL
        maxMedian <= thresholds.maxPassMedianRelativeError &&
            fitJudgeable &&
            agreeing >= thresholds.minPassFitAgreeingFrames &&
            (minLabel == null || minLabel >= thresholds.minLabelAgreement) -> ConsistencyVerdict.PASS
        else -> ConsistencyVerdict.INCONCLUSIVE
    }
    return DepthConsistency(frames, maxMedian, withGeometry, agreeing, minLabel, verdict, nonFinite)
}
