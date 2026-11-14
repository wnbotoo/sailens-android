package com.sailens.guidance.depth.geometry

import com.sailens.guidance.geometry.GeometryQuality
import com.sailens.guidance.geometry.GroundObservation
import com.sailens.guidance.geometry.TimeBasis
import com.sailens.guidance.model.trace.DepthRunTrace
import com.sailens.guidance.service.TraceService
import com.sailens.vision.depth.DepthRun
import com.sailens.vision.depth.MetricDepthMap
import kotlinx.coroutines.Job

/**
 * One Guidance session's use of the depth lane: its own runner, fitter, snapshot and records.
 *
 * Every state change that a record or the snapshot shows happens under this session's own [lock]:
 * - [begin] gives an attempt its run id and marks it in flight, unless the session is closed;
 * - [commit] publishes the attempt's snapshot (when it has one) together with its record, unless the session
 *   was closed meanwhile -- then the attempt was already settled by the closing record and is dropped;
 * - [seal] closes the session and writes the closing record, which carries the attempt in flight, if any.
 * So the closing record is the last of its generation, and every run id is settled exactly once. The lock
 * holds memory operations and one ledger call only; the ledger's lock is taken inside it, never the other
 * way round.
 */
class DepthSession internal constructor(
    val generation: Long,
    private val traceSessionId: String?,
    private val config: DepthGeometryConfig,
    private val traceService: TraceService,
    private val elapsedMs: () -> Long,
) {
    private val lock = Any()
    private var closed = false
    private var nextRunId = 0L
    private var inFlight: DepthAttempt? = null
    private var inFlightStage: String? = null
    private val skips = LinkedHashMap<String, Int>()
    private var failuresTotal = 0
    private var consecutiveFailures = 0

    @Volatile
    private var state: DepthLaneState = DepthLaneState.INITIALIZING

    @Volatile
    private var issue: String? = null

    @Volatile
    private var lastSkip: String? = null

    @Volatile
    private var published: DepthGeometrySnapshot? = null

    @Volatile
    internal var periodMs: Long = config.frameIntervalMs

    @Volatile
    internal var lastAttemptFrameNanos: Long? = null

    /** Calls of this session's fitter so far; the lane's own coroutine only. */
    internal var fitCalls: Long = 0

    internal var job: Job? = null
    internal var done: Job? = null

    /** Stopped after too many failures in a row; frames are then counted as skipped. */
    internal val stopped: Boolean get() = state == DepthLaneState.RUN_FAILED

    /**
     * Where the session stands, with its latest published observation, read under the session's lock: an observation
     * is never seen with the state from before it was published.
     */
    fun latest(): DepthGeometrySnapshot = synchronized(lock) {
        (published ?: DepthGeometrySnapshot(generation = generation, state = state, periodMs = periodMs))
            .copy(state = state, lastSkipReason = lastSkip, periodMs = periodMs, stateIssue = issue)
    }

    /** The detail behind a failed state, for the capability's issues. */
    val stateIssue: String? get() = issue

    /** No effect once the session is closed: a late step of the lane cannot reopen it. */
    internal fun setState(next: DepthLaneState, detail: String?) = synchronized(lock) {
        if (closed) return
        state = next
        issue = detail
    }

    internal fun recordSkip(reason: String) = synchronized(lock) {
        if (closed) return
        skips[reason] = (skips[reason] ?: 0) + 1
        lastSkip = reason
    }

    internal fun begin(frameSequence: Long, frameTimeNanos: Long?, timeBasis: TimeBasis, quality: GeometryQuality): DepthAttempt? =
        synchronized(lock) {
            if (closed) return null
            nextRunId++
            DepthAttempt(nextRunId, frameSequence, frameTimeNanos, timeBasis, quality).also {
                inFlight = it
                inFlightStage = DepthGeometryLane.STAGE_PREPARE
            }
        }

    internal fun stage(attempt: DepthAttempt, stage: String) = synchronized(lock) {
        if (inFlight === attempt) inFlightStage = stage
    }

    /** A step threw: counts the failure and settles the attempt in flight, if any, as failed. */
    internal fun failInFlight() {
        noteFailure(DepthLaneState.RUNNING)
        synchronized(lock) {
            inFlight?.let { commit(it, DepthRunTrace.FAILED, snapshot = null) }
        }
    }

    internal fun noteFailure(after: DepthLaneState) {
        val stop = synchronized(lock) {
            failuresTotal++
            consecutiveFailures++
            consecutiveFailures >= config.maxConsecutiveFailures
        }
        setState(if (stop) DepthLaneState.RUN_FAILED else after, if (stop) "run_failed" else null)
    }

    /**
     * Settles [attempt] with its record. A [snapshot] is published in the same step, which also ends a failure streak
     * and returns a paused or failing state to running (a stopped lane stays stopped). [skipReason]: an attempt that ran
     * and was skipped after its run (no measurement, a view off its frame); the reason stays the last skip, for the
     * capability, until something else replaces it. It publishes nothing, but its completed inference ends a failure
     * streak and restores running just as a publication does, without changing the old observation or its times.
     *
     * @return false when the session was closed meanwhile and the attempt was settled by the closing record.
     */
    internal fun commit(
        attempt: DepthAttempt,
        outcome: String,
        snapshot: DepthGeometrySnapshot?,
        prepareMs: Long? = null,
        admitWaitMs: Long? = null,
        run: DepthRun<*>? = null,
        observation: GroundObservation? = null,
        fitMs: Long? = null,
        fitCpuUs: Long? = null,
        skipReason: String? = null,
    ): Boolean = synchronized(lock) {
        if (closed || inFlight !== attempt) return false
        if (snapshot != null) {
            published = snapshot
            lastSkip = null
        }
        if (snapshot != null || skipReason != null) {
            consecutiveFailures = 0
            if (state != DepthLaneState.RUN_FAILED) {
                state = DepthLaneState.RUNNING
                issue = null
            }
        }
        traceSessionId?.let { id ->
            traceService.recordDepthRun(
                DepthRunTrace(
                    sessionId = id,
                    generation = generation,
                    runId = attempt.runId,
                    outcome = outcome,
                    atElapsedMs = elapsedMs(),
                    frameSequence = attempt.frameSequence,
                    frameTimeNanos = attempt.frameTimeNanos,
                    timeBasis = attempt.timeBasis.name.lowercase(),
                    geometryQuality = attempt.quality.label(),
                    validity = observation?.validity?.name?.lowercase(),
                    inlierRatio = observation?.inlierRatio,
                    confidence = observation?.confidence,
                    planeAgreesWithGravity = observation?.planeAgreesWithGravity,
                    groundRatio = observation?.groundMask?.fraction(),
                    aboveGroundRatio = observation?.aboveGroundMask?.fraction(),
                    belowGroundRatio = observation?.belowGroundMask?.fraction(),
                    disparityScale = observation?.disparityScale,
                    disparityShift = observation?.disparityShift,
                    detail = run?.detail,
                    prepareMs = prepareMs,
                    admitWaitMs = admitWaitMs,
                    inferMs = run?.inferenceMs,
                    outputReadMs = run?.outputReadMs,
                    fitMs = fitMs,
                    fitCpuUs = fitCpuUs,
                    accelerator = run?.runtimeInfo?.accelerator,
                    acceleratorSelection = run?.runtimeInfo?.acceleratorSelection,
                    preprocessBackend = run?.runtimeInfo?.preprocessBackend,
                    postprocessBackend = run?.runtimeInfo?.postprocessBackend,
                    modelShape = run?.shapeLabel,
                    skipsSinceLast = LinkedHashMap(skips),
                    depthKind = run?.map?.kind?.name?.lowercase(),
                    scaleBasis = run?.map?.kind?.let { scaleBasisOf(it).name.lowercase() },
                    unknownFraction = (run?.map as? MetricDepthMap)?.unknownFraction,
                    measurementTimeNanos = snapshot?.measurementTimeNanos,
                    viewTimeNanos = snapshot?.viewTimeNanos,
                    measurementEpoch = snapshot?.measurementKey?.sourceEpoch,
                    measurementId = snapshot?.measurementKey?.measurement,
                    measuredPhoneHeightM = observation?.measuredPhoneHeightM,
                    phoneHeightDifference = snapshot?.phoneHeight?.relativeDifference,
                    metricShiftRatio = observation?.metricShiftRatio,
                ),
            )
        }
        skips.clear()
        if (snapshot == null && skipReason != null) lastSkip = skipReason
        inFlight = null
        inFlightStage = null
        true
    }

    /** Closes the session and writes its closing record; a second call does nothing. */
    internal fun seal() = synchronized(lock) {
        if (closed) return
        closed = true
        traceSessionId?.let { id ->
            traceService.recordDepthRun(
                DepthRunTrace(
                    sessionId = id,
                    generation = generation,
                    runId = nextRunId,
                    outcome = DepthRunTrace.CLOSED,
                    atElapsedMs = elapsedMs(),
                    skipsSinceLast = LinkedHashMap(skips),
                    cancelledRunId = inFlight?.runId,
                    cancelledStage = inFlightStage,
                    failuresTotal = failuresTotal,
                    finalState = state.name.lowercase(),
                ),
            )
        }
        skips.clear()
        inFlight = null
        inFlightStage = null
        state = DepthLaneState.CLOSED
    }

    private fun GeometryQuality.label(): String = when (this) {
        GeometryQuality.Ok -> "ok"
        is GeometryQuality.Degraded -> "degraded"
        is GeometryQuality.Unavailable -> "unavailable"
    }

    private fun com.sailens.core.mask.BinaryMask.fraction(): Float {
        val total = width * height
        return if (total == 0) 0f else countTrue().toFloat() / total
    }
}
