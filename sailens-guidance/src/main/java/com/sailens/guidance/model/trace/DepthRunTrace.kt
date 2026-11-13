package com.sailens.guidance.model.trace

/**
 * One depth-lane attempt, or the session's closing record (docs/trace_metrics_guide.md, `depth_run`).
 *
 * Statistics come from these, not from frame records: several runs can fall between two frames, one
 * snapshot can be attached to several frames, and frames are sampled. Every attempt that reached the
 * preprocessing has exactly one settlement: its own record, or the closing record's [cancelledRunId]
 * when the session was stopped while it was in flight. Skips before the preprocessing are counted into
 * the next record.
 *
 * Accepted only while its own trace session is attached ([sessionId]), numbered with the session's next
 * [seq]; [SessionTraceSummary.depthRunCount] says how many a complete trace holds.
 */
data class DepthRunTrace(
    val sessionId: String,
    /** The depth session within the Guidance session's lane. */
    val generation: Long,
    /** From 1 within the depth session; the closing record carries the last one given out. */
    val runId: Long,
    val outcome: String,
    val seq: Long = 0,
    val atElapsedMs: Long,
    val frameSequence: Long? = null,
    val frameTimeNanos: Long? = null,
    val timeBasis: String? = null,
    val geometryQuality: String? = null,
    val validity: String? = null,
    val inlierRatio: Float? = null,
    val confidence: Float? = null,
    val planeAgreesWithGravity: Boolean? = null,
    val groundRatio: Float? = null,
    val aboveGroundRatio: Float? = null,
    val belowGroundRatio: Float? = null,
    val disparityScale: Float? = null,
    val disparityShift: Float? = null,
    val detail: Float? = null,
    val prepareMs: Long? = null,
    val admitWaitMs: Long? = null,
    val inferMs: Long? = null,
    val outputReadMs: Long? = null,
    val fitMs: Long? = null,
    /**
     * The fit thread's CPU time inside the fit, microseconds; null where the platform does not measure it. [fitMs] is the
     * wall time around the hand-off to the fit dispatcher, so it also holds what is not the fit's own CPU time.
     */
    val fitCpuUs: Long? = null,
    val accelerator: String? = null,
    val acceleratorSelection: String? = null,
    val preprocessBackend: String? = null,
    val postprocessBackend: String? = null,
    val modelShape: String? = null,
    /** Frames not attempted since the previous record, by reason. */
    val skipsSinceLast: Map<String, Int> = emptyMap(),
    /** Closing record only: the attempt in flight when the session was stopped, and where it was. */
    val cancelledRunId: Long? = null,
    val cancelledStage: String? = null,
    /** Closing record only. */
    val failuresTotal: Int? = null,
    val finalState: String? = null,
    /** The run's `DepthKind` and the scale its lengths have, lower case; absent before the source ran. */
    val depthKind: String? = null,
    val scaleBasis: String? = null,
    /** Metric depth: the share of the map's pixels the source did not measure. */
    val unknownFraction: Float? = null,
    /** A published run: when its depth was measured and the moment of its view, on the frame's time basis. */
    val measurementTimeNanos: Long? = null,
    val viewTimeNanos: Long? = null,
    /** A published run: which measurement it holds, `MeasurementKey` (a model's is its generation and run id). */
    val measurementEpoch: Long? = null,
    val measurementId: Long? = null,
    /** Metric depth, a valid fit: the phone height the depths measure, and how far it is from the setting's, a fraction. */
    val measuredPhoneHeightM: Float? = null,
    val phoneHeightDifference: Float? = null,
    /** Metric depth: the fit's shift check (`GroundObservation.metricShiftRatio`). */
    val metricShiftRatio: Float? = null,
) {
    companion object Outcomes {
        const val PUBLISHED: String = "published"
        const val PREPROCESS_UNAVAILABLE: String = "preprocess_unavailable"
        const val YIELD_DROPPED: String = "yield_dropped"
        const val FAILED: String = "failed"
        const val NON_FINITE_OUTPUT: String = "non_finite_output"

        /** A metric map mostly unknown: it measured nothing. Not a failure. */
        const val NO_MEASUREMENT: String = "no_measurement"

        /** A map whose view is too far from its frame's time to be placed with it. Not a failure. */
        const val DEPTH_TIME_MISMATCH: String = "depth_time_mismatch"
        const val CLOSED: String = "closed"

        /** A closed set: the parser rejects any other. */
        val ALL: Set<String> = setOf(PUBLISHED, PREPROCESS_UNAVAILABLE, YIELD_DROPPED, FAILED, NON_FINITE_OUTPUT, NO_MEASUREMENT, DEPTH_TIME_MISMATCH, CLOSED)
    }
}
