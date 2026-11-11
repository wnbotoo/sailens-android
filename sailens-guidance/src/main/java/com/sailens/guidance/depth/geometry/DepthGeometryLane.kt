package com.sailens.guidance.depth.geometry

import android.os.SystemClock
import com.sailens.core.frame.ImageFrame
import com.sailens.core.log.LogService
import com.sailens.core.runtime.MlRuntimeInfo
import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.GeometryQuality
import com.sailens.guidance.geometry.GroundFitter
import com.sailens.guidance.geometry.GroundObservation
import com.sailens.guidance.geometry.TimeBasis
import com.sailens.guidance.model.trace.DepthRunTrace
import com.sailens.guidance.safety.BaselineQualification
import com.sailens.guidance.service.TraceService
import com.sailens.runtime.accelerator.AcceleratorCoordinator
import com.sailens.vision.depth.DepthRun
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
    RUNNING,
    PREPROCESS_UNAVAILABLE,
    PAUSED_THERMAL,
    PAUSED_YIELD,
    RUN_FAILED,
    CLOSED,
}

/** The latest published observation of a depth session, and where the session stands now. */
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
)

/** What a depth session needs from the Guidance session that starts it. */
class DepthStartContext(
    /** The lane's own subscription to the camera, at its own cadence. */
    val frames: Flow<ImageFrame>,
    val releaseFrame: (ImageFrame) -> Unit,
    /** The main pipeline's latest baseline qualification; null before its first result. */
    val baseline: () -> BaselineQualification?,
    /** This frame's camera geometry, resolved now. */
    val geometry: (ImageFrame) -> CameraGeometry,
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
    private val runnerFactory: () -> DepthModelRunner,
    private val modelCheck: DepthModelCheck,
    private val coordinator: AcceleratorCoordinator,
    private val thermal: ThermalStatusSource,
    private val traceService: TraceService,
    private val config: DepthGeometryConfig,
    private val logService: LogService,
    private val fitterFactory: () -> GroundFitter = { GroundFitter() },
    private val elapsedMs: () -> Long = SystemClock::elapsedRealtime,
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
        var runner: DepthModelRunner? = null
        try {
            earlier?.join()
            when (val check = runCatchingNonCancellation { modelCheck.check() }) {
                null -> { setState(DepthLaneState.MODEL_UNREADABLE, "model_check_failed"); return }
                DepthModelCheckResult.Missing -> { setState(DepthLaneState.NOT_CONFIGURED, null); return }
                is DepthModelCheckResult.Unreadable -> { setState(DepthLaneState.MODEL_UNREADABLE, check.detail); return }
                DepthModelCheckResult.Compatible -> Unit
            }
            val created = runnerFactory()
            runner = created
            if (runCatchingNonCancellation { created.initialize() } == null) {
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
        runner: DepthModelRunner,
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
        if (coordinator.isYielding(AcceleratorCoordinator.Priority.BACKGROUND)) {
            setState(DepthLaneState.PAUSED_YIELD, null)
            return skip(SKIP_YIELD)
        }
        when (context.baseline()) {
            null -> return skip(SKIP_NO_BASELINE)
            is BaselineQualification.Unqualified -> return skip(SKIP_FRAME_UNQUALIFIED)
            else -> Unit
        }
        val geometry = context.geometry(frame)
        val intrinsics = geometry.intrinsics
        val down = geometry.gravityInCamera
        if (geometry.quality is GeometryQuality.Unavailable || intrinsics == null || down == null) return skip(SKIP_GEOMETRY)

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

            stage(attempt, STAGE_ADMIT)
            val admitStart = elapsedMs()
            val admission = coordinator.tryAdmit(AcceleratorCoordinator.Priority.BACKGROUND)
            if (admission == null) {
                commit(attempt, DepthRunTrace.YIELD_DROPPED, snapshot = null, prepareMs = prepareMs)
                return
            }
            val admitWaitMs = elapsedMs() - admitStart
            stage(attempt, STAGE_INFER)
            val run = try {
                runner.infer(prepared)
            } finally {
                admission.close()
            }
            if (run.nonFiniteCount > 0) {
                noteFailure(DepthLaneState.RUNNING)
                commit(attempt, DepthRunTrace.NON_FINITE_OUTPUT, snapshot = null, prepareMs = prepareMs, run = run)
                evidenceSlot?.let { slot ->
                    evidenceSlot = null
                    observeEvidence {
                        slot.submit(DepthEvidenceRun(DepthRunTrace.NON_FINITE_OUTPUT, null, geometry, config.gridWidth, config.gridHeight, run, null))
                    }
                }
                return
            }

            stage(attempt, STAGE_FIT)
            // Counted before the call: a fit that throws still leaves its gap in the ordinals.
            val fitOrdinal = fitCalls++
            val fitStart = elapsedMs()
            val observation = withContext(fitDispatcher) {
                fitter.fit(run.map, intrinsics, down, config.gridWidth, config.gridHeight, geometry.frameTimeNanos)
            }
            val fitMs = elapsedMs() - fitStart
            publish(attempt, frame, geometry, prepareMs, admitWaitMs, run, observation, fitMs)
            evidenceSlot?.let { slot ->
                evidenceSlot = null
                observeEvidence {
                    slot.submit(DepthEvidenceRun(DepthRunTrace.PUBLISHED, fitOrdinal, geometry, config.gridWidth, config.gridHeight, run, observation))
                }
            }
        } finally {
            evidenceSlot?.let { slot -> observeEvidence { slot.discard() } }
        }
    }

    private fun DepthSession.publish(
        attempt: DepthAttempt,
        frame: ImageFrame,
        geometry: CameraGeometry,
        prepareMs: Long,
        admitWaitMs: Long,
        run: DepthRun,
        observation: GroundObservation,
        fitMs: Long,
    ) {
        val snapshot = DepthGeometrySnapshot(
            generation = this.generation,
            state = DepthLaneState.RUNNING,
            runId = attempt.runId,
            observation = observation,
            frameSequence = frame.sequenceNumber,
            frameTimeNanos = geometry.frameTimeNanos,
            timeBasis = geometry.timeBasis,
            geometryQuality = geometry.quality,
            periodMs = periodMs,
            prepareMs = prepareMs,
            inferMs = run.inferenceMs,
            outputReadMs = run.outputReadMs,
            fitMs = fitMs,
            runtimeInfo = run.runtimeInfo,
            detail = run.detail,
            modelShape = run.shapeLabel,
        )
        commit(attempt, DepthRunTrace.PUBLISHED, snapshot, prepareMs, admitWaitMs, run, observation, fitMs)
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
