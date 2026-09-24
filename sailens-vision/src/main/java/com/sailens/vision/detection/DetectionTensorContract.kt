package com.sailens.vision.detection

/**
 * The detection output shapes this runtime can decode, in one place so the runner and a static
 * preflight answer "can this model be read?" with the same rule.
 *
 * - raw attribute-major `[1, 4 + classCount, N]` ([DetectionLayout.RAW_TRANSPOSED])
 * - end-to-end `[1, N, 6]` ([DetectionLayout.END_TO_END]): x1, y1, x2, y2, confidence, class id
 */
object DetectionTensorContract {
    const val RAW_BOX_ATTRIBUTES: Int = 4
    const val END_TO_END_ATTRIBUTES: Int = 6

    /** A decodable output tensor: its layout, how many candidates and attributes per candidate. */
    data class OutputSpec(
        val layout: DetectionLayout,
        val detectionCount: Int,
        val attributes: Int,
    )

    /** The spec for an output tensor of [shape], or null when this runtime cannot decode it. */
    fun outputSpecOrNull(shape: List<Int>, classCount: Int): OutputSpec? {
        if (shape.size != 3 || shape[0] != 1) return null
        if (shape[1] == RAW_BOX_ATTRIBUTES + classCount) {
            return OutputSpec(DetectionLayout.RAW_TRANSPOSED, detectionCount = shape[2], attributes = shape[1])
        }
        if (shape[2] == END_TO_END_ATTRIBUTES) {
            return OutputSpec(DetectionLayout.END_TO_END, detectionCount = shape[1], attributes = shape[2])
        }
        return null
    }
}
