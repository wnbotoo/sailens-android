package com.sailens.guidance.model.trace

/**
 * The depth lane in one trace (docs/trace_metrics_guide.md, `depth_run`). Counts, distributions and timings come from
 * the `depth_run` records alone, each attempt once: by its own record, or as the closing record's in-flight attempt
 * ([CANCELLED]). Only the ages come from the frames, which are sampled.
 *
 * @param missingRecords records known to be missing; without the session's count, at least this many.
 * @param depthSessions depth sessions seen (generations).
 * @param attempts attempts settled: own records plus in-flight attempts at a stop.
 * @param outcomeCounts per outcome, the closing records left out and [CANCELLED] added for the in-flight ones.
 * @param validityCounts per ground-fit validity, published runs only.
 * @param inverseOutputRatio published runs whose fit found the output inverted, over all published runs.
 * @param skipCounts frames skipped before an attempt, per reason, over the whole trace.
 * @param inferMs the model run; [outputReadMs] reading its output back.
 * @param gpuMs per run, [inferMs] + [outputReadMs]: the accelerator's share of a run, which the device budget
 *   judges. Percentiles of the sum, never the sum of the two percentiles.
 * @param fitMs the wall time of the fit, the hand-off to its dispatcher included; [fitCpuMs] the fit thread's CPU time
 *   inside it, from the records that have it (rounded up to whole milliseconds).
 * @param ageMs the attached observation's age on the traced frames, by frame time.
 */
data class DepthRunReport(
    val completeness: DepthRecordCompleteness,
    val missingRecords: Long = 0,
    val depthSessions: Int = 0,
    val attempts: Int = 0,
    val outcomeCounts: Map<String, Int> = emptyMap(),
    val validityCounts: Map<String, Int> = emptyMap(),
    val inverseOutputRatio: Double = 0.0,
    val skipCounts: Map<String, Int> = emptyMap(),
    val prepareMs: TimingStats? = null,
    val admitWaitMs: TimingStats? = null,
    val inferMs: TimingStats? = null,
    val outputReadMs: TimingStats? = null,
    val gpuMs: TimingStats? = null,
    val fitMs: TimingStats? = null,
    val fitCpuMs: TimingStats? = null,
    val ageMs: TimingStats? = null,
) {
    companion object {
        const val CANCELLED: String = "cancelled"
        val NOT_RECORDED: DepthRunReport = DepthRunReport(DepthRecordCompleteness.NOT_RECORDED)
    }
}

/** Nearest-rank percentiles of a sample, milliseconds. */
data class TimingStats(val count: Int, val p50: Long, val p95: Long, val max: Long)

enum class DepthRecordCompleteness {
    /** A trace from before depth runs were recorded (no `depthTrace` flag), or none at all. */
    NOT_RECORDED,

    /** Every record the session counted is there, every depth session has its closing record, run ids run from 1. */
    COMPLETE,

    /** Records are missing: a `seq` gap, fewer than the session counted, a run id gap, or a depth session never closed. */
    INCOMPLETE,

    /** Nothing found missing, but there is no summary to count against: the process may have died. */
    POSSIBLY_INCOMPLETE,
}
