package com.sailens.vision.consistency

import com.sailens.vision.detection.Detection
import com.sailens.vision.semantic.SemanticClassMap

/**
 * Does a model give the same answer on an accelerator as on the CPU, for the same input?
 *
 * Running on an accelerator is not evidence of computing correctly there. Two model exports
 * (onnx2tf, NHWC) have run on the LiteRT GPU accelerator without a single error and answered wrongly: indoor
 * floors and pale paving came out as `building`, and an office chair the CPU found at 92% was
 * never detected. Re-exports of the same weights were correct on the same GPU. Nothing in the
 * output looks broken, so only a comparison with a reference computation finds it -- for a user who
 * cannot see the scene, the same kind of safety property as the class channel order.
 *
 * This file is the comparison only: two results of one model on one frame, the CPU's taken as the
 * reference. Running the models is the caller's job.
 */

/** Pixels the reference labelled [referenceClassId] and the candidate [candidateClassId]. */
data class ClassConfusion(
    val referenceClassId: Int,
    val candidateClassId: Int,
    /** As a fraction of all compared pixels. */
    val fraction: Float,
)

data class ClassMapAgreement(
    /** Fraction of pixels with the same class in both maps. */
    val agreement: Float,
    val pixelCount: Int,
    /** The largest disagreements, largest first. */
    val topConfusions: List<ClassConfusion>,
)

/**
 * Pixel-by-pixel class agreement of two class maps of the same frame.
 *
 * An accelerator that computes correctly differs from the CPU only along class boundaries, a
 * percent or two of the pixels. A wrong one replaces whole regions, which [topConfusions] names.
 */
fun compareClassMaps(
    reference: SemanticClassMap,
    candidate: SemanticClassMap,
    topK: Int = 3,
): ClassMapAgreement {
    require(reference.width == candidate.width && reference.height == candidate.height) {
        "class maps differ in size: ${reference.width}x${reference.height} vs ${candidate.width}x${candidate.height}"
    }
    val ref = reference.classIds
    val cand = candidate.classIds
    val pixelCount = reference.width * reference.height
    if (pixelCount == 0) return ClassMapAgreement(agreement = 1f, pixelCount = 0, topConfusions = emptyList())

    var classCount = 0
    for (i in 0 until pixelCount) {
        classCount = maxOf(classCount, ref[i] + 1, cand[i] + 1)
    }
    // A class-by-class table instead of a map keyed by pairs: a frame has ~230k pixels and no boxing.
    val confusion = IntArray(classCount * classCount)
    var agree = 0
    for (i in 0 until pixelCount) {
        val r = ref[i]
        val c = cand[i]
        if (r == c) agree++ else if (r >= 0 && c >= 0) confusion[r * classCount + c]++
    }
    val top = confusion.indices
        .filter { confusion[it] > 0 }
        .sortedByDescending { confusion[it] }
        .take(topK)
        .map { ClassConfusion(it / classCount, it % classCount, confusion[it].toFloat() / pixelCount) }
    return ClassMapAgreement(agree.toFloat() / pixelCount, pixelCount, top)
}

data class DetectionAgreement(
    /** Reference detections at or above the threshold. */
    val referenceCount: Int,
    /** Of those, how many the candidate also found. */
    val matchedReference: Int,
    /** Candidate detections at or above the threshold. */
    val candidateCount: Int,
    /** Of those, how many the reference did not find. */
    val extraCandidate: Int,
)

/**
 * Matches two detection lists of the same frame against the production decision at [threshold]:
 * same class, IoU at least [minIou], each detection used once, best overlaps first.
 *
 * What matters is whether both sides make the same production decision. A detection at or above
 * [threshold] on one side is matched by the same object at or above [threshold] on the other. The
 * one excuse is numeric jitter at the threshold: 0.41 against 0.36 is the same answer that happens
 * to straddle it. So a match across the threshold counts only when the two confidences are within
 * [margin] of each other -- 0.90 against 0.26 is a detection the accelerator loses in production,
 * not jitter. Decode both lists down to `threshold - margin` so the weaker side is visible.
 */
fun compareDetections(
    reference: List<Detection>,
    candidate: List<Detection>,
    threshold: Float,
    margin: Float = DEFAULT_CONFIDENCE_MARGIN,
    minIou: Float = DEFAULT_MIN_IOU,
): DetectionAgreement {
    val lower = threshold - margin
    val strongReference = reference.filter { it.confidence >= threshold }
    val strongCandidate = candidate.filter { it.confidence >= threshold }
    return DetectionAgreement(
        referenceCount = strongReference.size,
        matchedReference = matchCount(strongReference, candidate.filter { it.confidence >= lower }, threshold, margin, minIou),
        candidateCount = strongCandidate.size,
        extraCandidate = strongCandidate.size -
            matchCount(strongCandidate, reference.filter { it.confidence >= lower }, threshold, margin, minIou),
    )
}

/** [wanted] are all at or above [threshold]; an [offered] one below it matches only within [margin]. */
private fun matchCount(
    wanted: List<Detection>,
    offered: List<Detection>,
    threshold: Float,
    margin: Float,
    minIou: Float,
): Int {
    data class Overlap(val wanted: Int, val offered: Int, val iou: Float)
    val pairs = buildList {
        wanted.forEachIndexed { w, a ->
            offered.forEachIndexed { o, b ->
                val sameDecision = b.confidence >= threshold || a.confidence - b.confidence <= margin
                if (a.classId == b.classId && sameDecision) {
                    val iou = a.boundingBox.iou(b.boundingBox)
                    if (iou >= minIou) add(Overlap(w, o, iou))
                }
            }
        }
    }.sortedByDescending { it.iou }
    val usedWanted = BooleanArray(wanted.size)
    val usedOffered = BooleanArray(offered.size)
    var matched = 0
    for (p in pairs) {
        if (usedWanted[p.wanted] || usedOffered[p.offered]) continue
        usedWanted[p.wanted] = true
        usedOffered[p.offered] = true
        matched++
    }
    return matched
}

enum class ConsistencyVerdict {
    PASS,
    FAIL,

    /** The frames could not decide, e.g. the reference detected nothing to compare. */
    INCONCLUSIVE,
}

/**
 * Semantic agreement is judged in three bands. A correct accelerator on a sharp, steady view agrees
 * on 97-98% of the pixels; a broken one replaced whole regions (30% for the exports at the top of this file). In between
 * lies blur and unfamiliar content, where several classes score alike and ordinary numeric
 * differences flip the winner: a shaken phone gave a correct model 86-96%. That band is
 * [ConsistencyVerdict.INCONCLUSIVE], not a failure -- retry with the phone held still.
 */
data class ConsistencyThresholds(
    val minMeanClassAgreement: Float = 0.95f,
    val minFrameClassAgreement: Float = 0.90f,
    /** Below this mean the model fails outright, however the frames were taken. */
    val failBelowMeanClassAgreement: Float = 0.80f,
    val minDetectionRecall: Float = 0.90f,
    val maxExtraDetectionFraction: Float = 0.10f,
)

data class SemanticConsistency(
    val frames: List<ClassMapAgreement>,
    val meanAgreement: Float,
    val minAgreement: Float,
    val verdict: ConsistencyVerdict,
)

fun judgeSemantic(
    frames: List<ClassMapAgreement>,
    thresholds: ConsistencyThresholds = ConsistencyThresholds(),
): SemanticConsistency {
    if (frames.isEmpty()) return SemanticConsistency(frames, 0f, 0f, ConsistencyVerdict.INCONCLUSIVE)
    val mean = frames.map { it.agreement }.average().toFloat()
    val min = frames.minOf { it.agreement }
    val verdict = when {
        mean < thresholds.failBelowMeanClassAgreement -> ConsistencyVerdict.FAIL
        mean >= thresholds.minMeanClassAgreement && min >= thresholds.minFrameClassAgreement -> ConsistencyVerdict.PASS
        else -> ConsistencyVerdict.INCONCLUSIVE
    }
    return SemanticConsistency(frames, mean, min, verdict)
}

data class DetectionConsistency(
    val frames: List<DetectionAgreement>,
    /** Pooled over frames; null when the reference detected nothing. */
    val recall: Float?,
    /** Pooled over frames; null when the candidate detected nothing. */
    val extraFraction: Float?,
    val verdict: ConsistencyVerdict,
)

/**
 * Pooled over all frames, so one frame with a single borderline object does not decide alone.
 * With nothing detected on either side there is nothing to compare: point the camera at objects.
 * Detections the candidate makes while the reference makes none are a failure, not "nothing to
 * compare".
 */
fun judgeDetection(
    frames: List<DetectionAgreement>,
    thresholds: ConsistencyThresholds = ConsistencyThresholds(),
): DetectionConsistency {
    val reference = frames.sumOf { it.referenceCount }
    val matched = frames.sumOf { it.matchedReference }
    val candidate = frames.sumOf { it.candidateCount }
    val extra = frames.sumOf { it.extraCandidate }
    val recall = if (reference > 0) matched.toFloat() / reference else null
    val extraFraction = if (candidate > 0) extra.toFloat() / candidate else null
    val verdict = when {
        reference == 0 && candidate == 0 -> ConsistencyVerdict.INCONCLUSIVE
        recall != null && recall < thresholds.minDetectionRecall -> ConsistencyVerdict.FAIL
        extraFraction != null && extraFraction > thresholds.maxExtraDetectionFraction -> ConsistencyVerdict.FAIL
        else -> ConsistencyVerdict.PASS
    }
    return DetectionConsistency(frames, recall, extraFraction, verdict)
}

const val DEFAULT_CONFIDENCE_MARGIN: Float = 0.10f
const val DEFAULT_MIN_IOU: Float = 0.5f
