package com.sailens.vision.depth

/**
 * What a [DepthMap] holds: a fact of the source's output, not of its vendor or model family. Platform code branches on
 * this and nothing else.
 */
enum class DepthKind {
    /** A monocular model's output: `a/Z + b`, with a and b unknown and new each frame. */
    RELATIVE_DISPARITY,

    /** Depth along the optical axis (Z) in metres, with what the source knows of its confidence. */
    METRIC_DEPTH,
}

/**
 * Which measurement a depth map holds: the source's epoch and the measurement's number within it. Evidence gathered
 * from depth counts a measurement once by this key, however many runs and views it reappears in. It is not which run
 * produced the map: that is the lane's observation id.
 */
data class MeasurementKey(val sourceEpoch: Long, val measurement: Long)

/**
 * A depth map's times, in the clock of the camera frame it came with (`ImageFrame.timestamp`), nanoseconds.
 *
 * @param measurementTimeNanos when the depth was measured: its freshness counts from this.
 * @param viewTimeNanos the moment of the camera view the map's pixels belong to: the gravity and intrinsics that place
 *   them are resolved at this time. Later than the measurement when a source reprojects an older measurement into a
 *   newer view; the same when it does not.
 * @param measurementKey which measurement; null when the source does not name its measurements because every run is a
 *   new one (a model on the frame's own picture): the lane then keys each run's measurement by the run.
 */
data class DepthTimes(
    val measurementTimeNanos: Long?,
    val viewTimeNanos: Long?,
    val measurementKey: MeasurementKey?,
) {
    companion object {
        /** No time is known. */
        val UNKNOWN: DepthTimes = DepthTimes(null, null, null)

        /** Computed from the frame taken at [timestampNanos]: measured and viewed then, a new measurement every run. */
        fun ofFrame(timestampNanos: Long): DepthTimes = DepthTimes(timestampNanos, timestampNanos, null)
    }
}

/**
 * A depth source's output on its own pixel grid, and how that grid maps onto the upright analysis image the camera
 * geometry describes ([transform]). Readers sample it in analysis-image coordinates and in the disparity domain, where
 * every value is an inverse depth up to a scale and a shift: a relative map's own values, a metric map's `1/Z`. So the
 * ground fit reads both kinds alike, and its depth-ratio decisions do not change with the kind.
 *
 * A point is unknown where the source did not see it -- outside its coverage, in padding, cropped away -- or did not
 * measure it; [sampleAt] then returns null, and nothing is interpolated across it.
 */
sealed interface DepthMap {
    val kind: DepthKind
    val width: Int
    val height: Int

    /** The source grid ↔ the upright analysis image; a point it does not cover is unknown. */
    val transform: DepthInputTransform
    val times: DepthTimes

    /** The inverse depth at analysis point (u, v); null where unknown. */
    fun sampleAt(u: Float, v: Float): Float?

    /** [sampleAt] unboxed: `NaN` where it would be null. For loops that sample thousands of points. */
    fun sampleOrNaN(u: Float, v: Float): Float

    /**
     * Whether every value [sampleAt] would read at (u, v) is known: the point is covered, and each source pixel it
     * reads holds a measurement. Cheaper than sampling, for counting how much of an area was seen.
     */
    fun isKnownAt(u: Float, v: Float): Boolean
}
