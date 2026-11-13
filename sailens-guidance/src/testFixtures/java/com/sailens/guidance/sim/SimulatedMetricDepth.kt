package com.sailens.guidance.sim

/**
 * The simulator's metric depth source (an AR depth API, a time-of-flight sensor), with the faults such sources have.
 * Its depth image is in the camera buffer's orientation, [width] × [height], and lies on the camera buffer as
 * [toBuffer] says (the source's own report, `DepthSourceMapping`): by default the 4:3 centre of the 16:9 buffer, so
 * the two images neither share an aspect nor a field of view. The producer turns it upright and maps it with that.
 *
 * Every pixel's depth is the scene's Z, then:
 * @param noise each depth times 1 ± noise, seeded.
 * @param scale every depth times this (a range scale error); [offsetM] added (a range offset).
 * @param quantizeM depths rounded to this step, as a millimetre format stores them.
 * @param mixedEdges a pixel next to a depth edge reads the mean of the two sides, as a sensor's mixed pixels do.
 * @param confidenceOf each pixel's confidence from its surface class and depth; [minConfidence] the threshold below
 *   which the map calls it unknown.
 * @param sparse when given, only this share of the pixels (scattered, seeded) is confident; the rest is unknown.
 * @param hole pixels (upright depth-image coordinates) with no measurement at all.
 * @param empty every pixel unknown; [emptyFrom] from that scenario frame on.
 * @param holdFrames one measurement serves this many frames, reprojected exactly into each frame's view: the map shows
 *   the current view, its measurement time and key stay those of the frame it was taken on.
 * @param measurementLagNanos the measurement is this much older than its view, besides any hold.
 * @param viewOffsetNanos the map's view is this far from its frame's time.
 * @param reportsMeasurementTime false: the source cannot say when it measured.
 * @param fails each run throws.
 */
data class SimulatedMetricDepth(
    val width: Int = 160,
    val height: Int = 120,
    val toBuffer: FloatArray = centreCrop(160, 120, 1280, 720),
    val noise: Double = 0.0,
    val scale: Double = 1.0,
    val offsetM: Double = 0.0,
    val quantizeM: Double? = null,
    val mixedEdges: Boolean = false,
    val confidenceOf: (classId: Int, zM: Double) -> Float = { _, _ -> 1f },
    val minConfidence: Float = 0.5f,
    val sparse: Double? = null,
    val hole: (x: Int, y: Int) -> Boolean = { _, _ -> false },
    val empty: Boolean = false,
    val emptyFrom: Int? = null,
    val holdFrames: Int = 1,
    val measurementLagNanos: Long = 0,
    val viewOffsetNanos: Long = 0,
    val reportsMeasurementTime: Boolean = true,
    val fails: Boolean = false,
    val seed: Int = 11,
) {
    override fun toString(): String = "SimulatedMetricDepth(${width}x$height${if (empty) ", empty" else ""}${if (fails) ", failing" else ""})"

    companion object {
        /** A [width] × [height] depth image over the centre of a buffer of the depth image's aspect, full height. */
        fun centreCrop(width: Int, height: Int, bufferWidth: Int, bufferHeight: Int): FloatArray {
            val scale = bufferHeight.toFloat() / height
            val left = (bufferWidth - width * scale) / 2
            return floatArrayOf(scale, 0f, left, 0f, scale, 0f)
        }
    }
}
