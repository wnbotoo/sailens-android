package com.sailens.guidance.depth.geometry

import android.os.Debug
import android.os.SystemClock
import com.sailens.core.frame.ImageFrame
import com.sailens.core.log.LogService
import com.sailens.core.runtime.MlRuntimeInfo
import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.FrameFacts
import com.sailens.guidance.geometry.GeometryQuality
import com.sailens.guidance.geometry.GroundFitter
import com.sailens.guidance.geometry.GroundObservation
import com.sailens.guidance.geometry.TimeBasis
import com.sailens.guidance.model.trace.DepthRunTrace
import com.sailens.guidance.safety.BaselineQualification
import com.sailens.guidance.service.TraceService
import com.sailens.runtime.accelerator.AcceleratorCoordinator
import com.sailens.vision.depth.DepthKind
import com.sailens.vision.depth.DepthMap
import com.sailens.vision.depth.DepthRun
import com.sailens.vision.depth.DisparityMap
import com.sailens.vision.depth.MeasurementKey
import com.sailens.vision.depth.MetricDepthMap
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a depth session stands; the capability is derived from it (`DepthGeometryCapability`). */
enum class DepthLaneState {
    INITIALIZING,
    NOT_CONFIGURED,
    MODEL_UNREADABLE,
    INIT_FAILED,

    /** The source refused itself at initialization ([DepthSourceUnavailable]): its reason is the state's issue. */
    SOURCE_UNAVAILABLE,
    RUNNING,
    PREPROCESS_UNAVAILABLE,
    PAUSED_THERMAL,
    PAUSED_YIELD,
    RUN_FAILED,
    CLOSED,
}

/**
 * The latest published observation of a depth session, and where the session stands now.
 *
 * [frameTimeNanos] is the time of the frame the run was made from; the observation's own times, on the same basis, are
 * [measurementTimeNanos] (its age counts from it) and [viewTimeNanos] (its cells and [view] belong to it). A model
 * measures and views at its frame's time, so for it the three are one.
 */
data class DepthGeometrySnapshot(
    val generation: Long,
    val state: DepthLaneState,
    /** Why the last frame was not attempted, when it was not; for the capability's `stale` reason. */
    val lastSkipReason: String? = null,
    val runId: Long? = null,
    val observation: GroundObservation? = null,
    val frameSequence: Long? = null,
    val frameTimeNanos: Long? = null,
    val timeBasis: TimeBasis? = null,
    val geometryQuality: GeometryQuality? = null,
    val periodMs: Long,
    val prepareMs: Long? = null,
    val inferMs: Long? = null,
    val outputReadMs: Long? = null,
    val fitMs: Long? = null,
    val runtimeInfo: MlRuntimeInfo? = null,
    val detail: Float? = null,
    val modelShape: String? = null,
    val kind: DepthKind? = null,
    val measurementTimeNanos: Long? = null,
    val viewTimeNanos: Long? = null,
    /** Which measurement the observation holds: the source's own key, or for a model `(generation, runId)`. */
    val measurementKey: MeasurementKey? = null,
    val view: DepthViewGeometry? = null,
    val cells: DepthCells? = null,
    /** Metric depth, a valid fit: the phone height the depths measure against the one the settings assume. */
    val phoneHeight: PhoneHeightCheck? = null,
    /** The detail behind a failed or refused [state]: `DepthSession.stateIssue` when the snapshot was read. */
    val stateIssue: String? = null,
)

/** What a depth session needs from the Guidance session that starts it. */
class DepthStartContext(
    /** The lane's own subscription to the camera, at its own cadence. */
    val frames: Flow<ImageFrame>,
    val releaseFrame: (ImageFrame) -> Unit,
    /** The main pipeline's latest baseline qualification; null before its first result. */
    val baseline: () -> BaselineQualification?,
    /**
     * The camera geometry of a frame's facts, resolved now: the frame's own, and, for a map whose view is at another
     * time than its frame's, the same facts at that time ([atView]).
     */
    val geometry: (FrameFacts) -> CameraGeometry,
    /** The trace session the records belong to; null when tracing is off. */
    val traceSessionId: String?,
)

/**
 * The depth lane (docs/geometry.md §6): learned disparity and the gravity-constrained ground fit, beside
 * Guidance and never in it. Nothing reads its observations to decide anything: they reach the capability,
 * the trace and the debug views only.
 *
 * One instance for the app (a Koin single): the start and stop use cases are separate instances, so the
 * cross-session state lives here, and it is only the tail of the barrier chain. There is no "current
 * session". [start] returns a [DepthSession] to the Guidance flow that started it; that flow reads the
 * session's snapshot and stops it ([stop]), whatever has started since.
 *
 * Barrier chain: a session initializes its runner only after every earlier session has finished its
 * teardown. Each session's `done` completes when its own job has ended (its completion handler runs even
 * for a job cancelled before it started) and the previous `done` has completed, the same link as
 * `GuidanceRunSequencer`'s. A runner is released in the session job's `finally`, so `done` follows the
 * release. Cancelling a session in the middle of the chain does not cut it.
 *
 * Failures stay here: every `Throwable` but cancellation is caught and becomes the session's state;
 * cancellation is rethrown. The session jobs are children of this lane's supervisor, not of the Guidance
 * flow.
 *
 * The debug capture's depth evidence ([DepthEvidenceRecorder]) is fed from [handle]: a copy of the planes
 * before the frame goes back, and the record once the run has an output. Its calls are guarded; with or
 * without a recorder the lane does and records the same.
 */
class DepthGeometryLane(
    private val producerFactory: () -> DepthProducer,
    private val modelCheck: DepthModelCheck,
    private val coordinator: AcceleratorCoordinator,
    private val thermal: ThermalStatusSource,
    private val traceService: TraceService,
    private val config: DepthGeometryConfig,
    private val logService: LogService,
    private val fitterFactory: () -> GroundFitter = { GroundFitter() },
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
    /** The calling thread's CPU time, nanoseconds; negative where the platform does not measure it. */
    private val threadCpuNanos: () -> Long = Debug::threadCpuTimeNanos,
    @OptIn(ExperimentalCoroutinesApi::class)
    private val laneDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    @OptIn(ExperimentalCoroutinesApi::class)
    private val fitDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    /** The debug capture's depth evidence stream; observation only. */
    private val evidence: DepthEvidenceRecorder = DepthEvidenceRecorder.None,
) {
    // A session catches its own failures; the handler is only the last guard against a crash.
    private val scope = CoroutineScope(
        SupervisorJob() + laneDispatcher +
            CoroutineExceptionHandler { _, error -> logService.error(TAG, "Depth lane session failed", error) },
    )
    private val lock = Any()
    private var generation = 0L
    private var lastDone: Job? = null

    /** Not a suspending call: the caller stores the session before any suspension point. */
    fun start(context: DepthStartContext): DepthSession {
        synchronized(lock) {
            generation++
            val earlier = lastDone
            val done = Job()
            val session = DepthSession(generation, context.traceSessionId, config, traceService, elapsedMs)
            lastDone = done
            val job = scope.launch(start = CoroutineStart.LAZY) { session.run(earlier, context) }
            // Registered before the job starts: it fires for a job cancelled before it ran, too.
            job.invokeOnCompletion {
                if (earlier == null) done.complete() else earlier.invokeOnCompletion { done.complete() }
            }
            session.job = job
            session.done = done
            job.start()
            return session
        }
    }

    /**
     * Closes [session] -- the one the caller started, whatever started since -- and returns at once. The
     * closing record is written before this returns; the runner is released when its call in progress
     * returns. A second call does nothing.
     */
    fun stop(session: DepthSession) {
        session.seal()
        session.job?.cancel()
    }

    private suspend fun DepthSession.run(earlier: Job?, context: DepthStartContext) {
        var runner: DepthProducer? = null
        try {
            earlier?.join()
            when (val check = runCatchingNonCancellation { modelCheck.check() }) {
                null -> { setState(DepthLaneState.MODEL_UNREADABLE, "model_check_failed"); return }
                DepthModelCheckResult.Missing -> { setState(DepthLaneState.NOT_CONFIGURED, null); return }
                is DepthModelCheckResult.Unreadable -> { setState(DepthLaneState.MODEL_UNREADABLE, check.detail); return }
                DepthModelCheckResult.Compatible -> Unit
            }
            val created = producerFactory()
            runner = created
            var refused: String? = null
            val initialized = runCatchingNonCancellation {
                try {
                    created.initialize()
                } catch (error: DepthSourceUnavailable) {
                    logService.warning(TAG, "Depth source unavailable: ${error.reason}")
                    refused = error.reason
                }
            }
            refused?.let { reason ->
                setState(DepthLaneState.SOURCE_UNAVAILABLE, reason)
                return
            }
            if (initialized == null) {
                setState(DepthLaneState.INIT_FAILED, "init_failed")
                return
            }
            setState(DepthLaneState.RUNNING, null)
            val fitter = fitterFactory()
            context.frames.collect { frame ->
                // Every frame goes back exactly once: early, before the accelerator runs, or here.
                val release = FrameRelease(frame, context.releaseFrame)
                try {
                    handle(frame, release, created, fitter, context)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logService.warning(TAG, "Depth run failed", throwable = error)
                    failInFlight()
                } finally {
                    release()
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            // The frame stream itself failed: this session's lane stops, Guidance goes on.
            logService.warning(TAG, "Depth lane stopped", throwable = error)
            setState(DepthLaneState.RUN_FAILED, "lane_failed")
        } finally {
            withContext(NonCancellable) {
                runner?.let { r -> runCatching { r.release() }.onFailure { logService.warning(TAG, "Depth runner release failed", throwable = it) } }
            }
        }
    }

    /**
     * One frame. A throw from any step after [DepthSession.begin] settles the attempt as failed (the caller
     * catches it); before it, nothing was attempted.
     */
    private suspend fun DepthSession.handle(
        frame: ImageFrame,
        release: FrameRelease,
        runner: DepthProducer,
        fitter: GroundFitter,
        context: DepthStartContext,
    ) {
        fun skip(reason: String) {
            release()
            recordSkip(reason)
        }
        if (stopped) return skip(SKIP_STOPPED)
        when (thermal.current()) {
            ThermalLevel.SEVERE -> { setState(DepthLaneState.PAUSED_THERMAL, null); return skip(SKIP_THERMAL) }
            ThermalLevel.MODERATE -> {
                periodMs = (1000f / config.throttledFps).toLong()
                val last = lastAttemptFrameNanos
                if (last != null && frame.timestamp - last in 0 until periodMs * 1_000_000L) return skip(SKIP_THERMAL_CADENCE)
            }
            else -> periodMs = config.frameIntervalMs
        }
        if (runner.needsAccelerator && coordinator.isYielding(AcceleratorCoordinator.Priority.BACKGROUND)) {
            setState(DepthLaneState.PAUSED_YIELD, null)
            return skip(SKIP_YIELD)
        }
        when (context.baseline()) {
            null -> return skip(SKIP_NO_BASELINE)
            is BaselineQualification.Unqualified -> return skip(SKIP_FRAME_UNQUALIFIED)
            else -> Unit
        }
        // Read while the frame is held: a map whose view is at another time is placed with these facts at that time.
        val facts = FrameFacts.of(frame)
        val geometry = context.geometry(facts)
        if (!geometry.canFit()) return skip(SKIP_GEOMETRY)

        val attempt = begin(frame.sequenceNumber, geometry.frameTimeNanos, geometry.timeBasis, geometry.quality) ?: return release()
        lastAttemptFrameNanos = frame.timestamp
        // Taken while the frame is still held; handed back by the finally below unless a record takes it.
        var evidenceSlot = context.traceSessionId?.let { id -> observeEvidence { evidence.copy(id, this.generation, attempt.runId, frame) } }
        try {
            val prepareStart = elapsedMs()
            val prepared = try {
                runner.prepare(frame)
            } finally {
                release()
            }
            val prepareMs = elapsedMs() - prepareStart
            if (prepared == null) {
                noteFailure(DepthLaneState.PREPROCESS_UNAVAILABLE)
                commit(attempt, DepthRunTrace.PREPROCESS_UNAVAILABLE, snapshot = null, prepareMs = prepareMs)
                return
            }

            // Only a source that runs on the accelerator waits for its admission.
            var admitWaitMs: Long? = null
            val admission = if (runner.needsAccelerator) {
                stage(attempt, STAGE_ADMIT)
                val admitStart = elapsedMs()
                val admitted = coordinator.tryAdmit(AcceleratorCoordinator.Priority.BACKGROUND)
                if (admitted == null) {
                    commit(attempt, DepthRunTrace.YIELD_DROPPED, snapshot = null, prepareMs = prepareMs)
                    return
                }
                admitWaitMs = elapsedMs() - admitStart
                admitted
            } else {
                null
            }
            stage(attempt, STAGE_INFER)
            val run = try {
                runner.infer(prepared)
            } finally {
                admission?.close()
            }
            // The evidence stream records a model's outputs, which are disparity maps.
            val evidenceRun = run.asDisparityRun()
            if (evidenceRun == null) evidenceSlot?.let { slot -> evidenceSlot = null; observeEvidence { slot.discard() } }
            if (run.nonFiniteCount > 0) {
                noteFailure(DepthLaneState.RUNNING)
                commit(attempt, DepthRunTrace.NON_FINITE_OUTPUT, snapshot = null, prepareMs = prepareMs, run = run)
                evidenceSlot?.let { slot ->
                    evidenceSlot = null
                    observeEvidence {
                        slot.submit(DepthEvidenceRun(DepthRunTrace.NON_FINITE_OUTPUT, null, geometry, config.gridWidth, config.gridHeight, checkNotNull(evidenceRun), null))
                    }
                }
                return
            }

            // A metric map must name its view; an unknown view or one too far from the frame cannot be placed with it.
            val viewTime = run.map.times.viewTimeNanos
            if ((run.map.kind == DepthKind.METRIC_DEPTH && viewTime == null) ||
                (viewTime != null && abs(viewTime - facts.sensorTimestampNanos) > config.maxViewOffsetPeriods * periodMs * 1_000_000L)
            ) {
                commit(attempt, DepthRunTrace.DEPTH_TIME_MISMATCH, snapshot = null, prepareMs = prepareMs, admitWaitMs = admitWaitMs, run = run, skipReason = SKIP_TIME_MISMATCH)
                return
            }
            // Holes and low confidence are unknown, not failure; a map that is mostly unknown measured nothing. Not a
            // failure either: it neither counts toward stopping the lane nor refreshes the last observation's time.
            val metric = run.map as? MetricDepthMap
            if (metric != null && metric.unknownFraction > config.maxUnknownFraction) {
                commit(attempt, DepthRunTrace.NO_MEASUREMENT, snapshot = null, prepareMs = prepareMs, admitWaitMs = admitWaitMs, run = run, skipReason = SKIP_NO_MEASUREMENT)
                return
            }

            // The map's pixels belong to its view: gravity and intrinsics at that moment. A model's view is its frame.
            val view = viewGeometry(run.map, facts, geometry, context)
            check(view.canFit()) { "no usable geometry at the map's view time" }
            val intrinsics = checkNotNull(view.intrinsics)
            val down = checkNotNull(view.gravityInCamera)
            stage(attempt, STAGE_FIT)
            // Counted before the call: a fit that throws still leaves its gap in the ordinals.
            val fitOrdinal = fitCalls++
            val fitStart = elapsedMs()
            var fitCpuUs: Long? = null
            val observation = withContext(fitDispatcher) {
                // The fit does not suspend: both readings are of the thread that ran it.
                val cpuStart = threadCpuNanos()
                val fitted = fitter.fit(run.map, intrinsics, down, config.gridWidth, config.gridHeight, view.frameTimeNanos)
                val cpuEnd = threadCpuNanos()
                if (cpuStart >= 0 && cpuEnd >= cpuStart) fitCpuUs = (cpuEnd - cpuStart) / 1_000
                fitted
            }
            val fitMs = elapsedMs() - fitStart
            // Outside the fit's own time: what the reading of each cell holds, for every main frame that reads this run.
            val viewGeometry = DepthViewGeometry(intrinsics, down, config.gridWidth, config.gridHeight, view.phone, view.timeBasis, view.quality)
            val cells = DepthCells.of(run.map, observation, viewGeometry)
            publish(attempt, frame, geometry, view, viewGeometry, cells, prepareMs, admitWaitMs, run, observation, fitMs, fitCpuUs)
            evidenceSlot?.let { slot ->
                evidenceSlot = null
                observeEvidence {
                    slot.submit(DepthEvidenceRun(DepthRunTrace.PUBLISHED, fitOrdinal, view, config.gridWidth, config.gridHeight, checkNotNull(evidenceRun), observation))
                }
            }
        } finally {
            evidenceSlot?.let { slot -> observeEvidence { slot.discard() } }
        }
    }

    /**
     * The geometry of [map]'s view: the frame's own when the view is at the frame's time (or the map does not say), the
     * frame's facts at the view time otherwise.
     */
    private fun viewGeometry(map: DepthMap, facts: FrameFacts, frameGeometry: CameraGeometry, context: DepthStartContext): CameraGeometry {
        val viewTime = map.times.viewTimeNanos ?: return frameGeometry
        if (viewTime == facts.sensorTimestampNanos) return frameGeometry
        return context.geometry(facts.atView(viewTime))
    }

    private fun CameraGeometry.canFit(): Boolean = quality !is GeometryQuality.Unavailable && intrinsics != null && gravityInCamera != null

    /** A model's run as the evidence stream takes it; null for a map that is not a model's disparity. */
    @Suppress("UNCHECKED_CAST")
    private fun DepthRun<DepthMap>.asDisparityRun(): DepthRun<DisparityMap>? = if (map is DisparityMap) this as DepthRun<DisparityMap> else null

    private fun DepthSession.publish(
        attempt: DepthAttempt,
        frame: ImageFrame,
        geometry: CameraGeometry,
        view: CameraGeometry,
        viewGeometry: DepthViewGeometry,
        cells: DepthCells?,
        prepareMs: Long,
        admitWaitMs: Long?,
        run: DepthRun<DepthMap>,
        observation: GroundObservation,
        fitMs: Long,
        fitCpuUs: Long?,
    ) {
        val times = run.map.times
        // On the view's time basis: the view's own time, less however much earlier the source measured. A model measures
        // its own frame; a metric source that cannot say when it measured has no age, so it is never fresh.
        val measurementTime = when (run.map.kind) {
            DepthKind.RELATIVE_DISPARITY -> view.frameTimeNanos?.let { viewAt ->
                viewAt - (times.viewTimeNanos?.let { v -> times.measurementTimeNanos?.let { v - it } } ?: 0L)
            }
            DepthKind.METRIC_DEPTH -> times.viewTimeNanos?.let { sourceViewAt ->
                times.measurementTimeNanos?.let { measured ->
                    view.frameTimeNanos?.let { viewAt -> viewAt - (sourceViewAt - measured) }
                }
            }
        }
        val phoneHeight = observation.measuredPhoneHeightM?.let { measured -> PhoneHeightCheck.of(measured, view.phone) }
        val snapshot = DepthGeometrySnapshot(
            generation = this.generation,
            state = DepthLaneState.RUNNING,
            runId = attempt.runId,
            observation = observation,
            frameSequence = frame.sequenceNumber,
            frameTimeNanos = geometry.frameTimeNanos,
            timeBasis = geometry.timeBasis,
            geometryQuality = view.quality,
            periodMs = periodMs,
            prepareMs = prepareMs,
            inferMs = run.inferenceMs,
            outputReadMs = run.outputReadMs,
            fitMs = fitMs,
            runtimeInfo = run.runtimeInfo,
            detail = run.detail,
            modelShape = run.shapeLabel,
            kind = run.map.kind,
            measurementTimeNanos = measurementTime,
            viewTimeNanos = view.frameTimeNanos,
            measurementKey = times.measurementKey ?: MeasurementKey(this.generation, attempt.runId),
            view = viewGeometry,
            cells = cells,
            phoneHeight = phoneHeight,
        )
        commit(attempt, DepthRunTrace.PUBLISHED, snapshot, prepareMs, admitWaitMs, run, observation, fitMs, fitCpuUs)
    }

    private inline fun <T> runCatchingNonCancellation(block: () -> T): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        logService.warning(TAG, "Depth lane step failed", throwable = error)
        null
    }

    /**
     * A call to the evidence recorder: whatever it throws stays here, an `Error` such as running out of memory included,
     * and the run goes on as without it. Only cancellation passes, as everywhere in the lane.
     */
    private inline fun <T> observeEvidence(block: () -> T): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        if (!evidenceFailureLogged) {
            evidenceFailureLogged = true
            logService.warning(TAG, "Depth evidence recorder failed", throwable = error)
        }
        null
    }

    @Volatile
    private var evidenceFailureLogged = false

    companion object {
        private const val TAG = "DepthGeometryLane"
        const val SKIP_STOPPED: String = "stopped"
        const val SKIP_THERMAL: String = "thermal"
        const val SKIP_THERMAL_CADENCE: String = "thermal_cadence"
        const val SKIP_YIELD: String = "yield"
        const val SKIP_NO_BASELINE: String = "no_baseline_yet"
        const val SKIP_FRAME_UNQUALIFIED: String = "frame_unqualified"
        const val SKIP_GEOMETRY: String = "geometry_unavailable"

        /** After the run, not before it: the attempt has its record; the capability names the reason while it lasts. */
        const val SKIP_NO_MEASUREMENT: String = "no_measurement"
        const val SKIP_TIME_MISMATCH: String = "depth_time_mismatch"
        const val STAGE_PREPARE: String = "prepare"
        const val STAGE_ADMIT: String = "admit"
        const val STAGE_INFER: String = "infer"
        const val STAGE_FIT: String = "fit"
    }
}

/** Gives a lent frame back to its source once, however many times it is called. */
internal class FrameRelease(private val frame: ImageFrame, private val release: (ImageFrame) -> Unit) : () -> Unit {
    private var released = false

    override fun invoke() {
        if (released) return
        released = true
        release(frame)
    }
}

/** What an attempt carries into its record; the session compares it by identity. */
internal class DepthAttempt(
    val runId: Long,
    val frameSequence: Long,
    val frameTimeNanos: Long?,
    val timeBasis: TimeBasis,
    val quality: GeometryQuality,
)
