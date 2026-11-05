package com.sailens.shell.guidance.safety

import com.sailens.guidance.safety.BaselineQualification
import com.sailens.guidance.usecase.scene.GuidancePipelineFailedException
import com.sailens.output.SpeechEngineState

/** The output facts the axes are read from, as the view model holds them; [speechReady] is `isReady` when read. */
internal data class OutputFacts(
    val engineState: SpeechEngineState,
    val speechEnabled: Boolean,
    val screenReaderActive: Boolean,
    val hapticsEnabled: Boolean,
    val speechReady: Boolean,
)

/** The input that changed the axes. */
internal enum class SafetyInput {
    START_REQUESTED, STALL_WATCH_STARTED, FLOW_STARTED, RESULT, FLOW_FAILED, STOPPED, LIFECYCLE_STOP, LIFECYCLE_START,
    OUTPUT, POLL,
}

/** One change: the axes and state after [cause], observed at [elapsedMs]; the state has held since [stateSinceElapsedMs]. */
internal data class GuidanceSafetySnapshot(
    val axes: GuidanceSafetyAxes,
    val state: GuidanceSafetyState,
    val stateSinceElapsedMs: Long,
    val elapsedMs: Long,
    val cause: SafetyInput,
)

/** The alerts the view model gives about Guidance itself. */
internal enum class SystemAlert { FAILED, STALLED, INTERRUPTED, SPEECH_UNAVAILABLE }

/**
 * Keeps the safety axes from the events the view model handles, and derives the state from them.
 *
 * Each input is one of the view model's events, called at the same point, with the time it happened. After each, the
 * axes are rebuilt (the stall condition asked of [stallCondition] while the stall watch runs) and, when they or the
 * state changed, [onChange] hears of it; a change of state alone restarts [GuidanceSafetySnapshot.stateSinceElapsedMs].
 *
 * Observation only: nothing decides on it. Pure and clock-driven, like the stall detector, and like it not
 * thread-safe: the view model calls it from the main thread only.
 *
 * @param stallCondition the stall detector's condition at a time: whether its limit has passed, nothing more.
 */
internal class GuidanceSafetyMonitor(
    private val stallCondition: (nowMs: Long) -> StallCondition,
    nowMs: Long,
    initialOutput: OutputFacts,
    private val onChange: (GuidanceSafetySnapshot) -> Unit = {},
) {
    private var phase = GuidanceRunPhase.NOT_STARTED
    private var failure: FailureReason? = null
    private var interrupted = false
    private var watching = false
    private var judgement: BaselineQualification? = null
    private var output = initialOutput

    var axes: GuidanceSafetyAxes = axesAt(nowMs)
        private set
    var state: GuidanceSafetyState = axes.state()
        private set
    var stateSinceElapsedMs: Long = nowMs
        private set

    /** Start pressed: a new run, with no result and no failure yet. */
    fun onStartRequested(nowMs: Long) = update(nowMs, SafetyInput.START_REQUESTED) {
        phase = GuidanceRunPhase.STARTING
        failure = null
        judgement = null
    }

    fun onStallWatchStarted(nowMs: Long) = update(nowMs, SafetyInput.STALL_WATCH_STARTED) {
        watching = true
    }

    /** The analysis flow is collected: the view model says it is running from here. */
    fun onFlowStarted(nowMs: Long) = update(nowMs, SafetyInput.FLOW_STARTED) {
        phase = GuidanceRunPhase.AWAITING_FIRST_RESULT
        failure = null
    }

    /** A result reached the view model, after the stall detector heard of it. */
    fun onResult(nowMs: Long, baseline: BaselineQualification, speechReady: Boolean) = update(nowMs, SafetyInput.RESULT) {
        if (phase == GuidanceRunPhase.AWAITING_FIRST_RESULT) phase = GuidanceRunPhase.RUNNING
        judgement = baseline
        output = output.copy(speechReady = speechReady)
    }

    /** The analysis flow threw; the stall watch stops with it. An interruption is kept, as the view model keeps it. */
    fun onFlowFailed(nowMs: Long, error: Throwable) = update(nowMs, SafetyInput.FLOW_FAILED) {
        phase = GuidanceRunPhase.FAILED
        failure = if (error is GuidancePipelineFailedException) FailureReason.PIPELINE_FAILED else FailureReason.ERROR
        watching = false
    }

    /** The user stopped, or the screen went away: the stall watch stops with it, and an interruption no longer stands. */
    fun onStopped(nowMs: Long) = update(nowMs, SafetyInput.STOPPED) {
        phase = GuidanceRunPhase.NOT_STARTED
        failure = null
        watching = false
        interrupted = false
    }

    /** The app went to the background. Only a running session is interrupted, as only then the view model says so. */
    fun onLifecycleStop(nowMs: Long) = update(nowMs, SafetyInput.LIFECYCLE_STOP) {
        if (phase.isRunning) interrupted = true
    }

    /**
     * Back in the foreground. A running session whose stall watch runs waits for a first result again: the view model
     * has just restarted the detector's startup grace, before this.
     */
    fun onLifecycleStart(nowMs: Long) = update(nowMs, SafetyInput.LIFECYCLE_START) {
        if (phase.isRunning && watching) phase = GuidanceRunPhase.AWAITING_FIRST_RESULT
        interrupted = false
    }

    fun onOutput(nowMs: Long, facts: OutputFacts) = update(nowMs, SafetyInput.OUTPUT) {
        output = facts
    }

    /** The stall watch's poll: the condition may have changed with time alone. */
    fun poll(nowMs: Long) = update(nowMs, SafetyInput.POLL) {}

    /**
     * Whether the state stands behind [alert] being given now: a failure for FAILED, a stall for STALLED, an
     * interruption for INTERRUPTED, own speech unavailable for SPEECH_UNAVAILABLE. The alert's own pacing (the stall
     * alarm's repeat, once per speech outage) is not the state's, so for those only giving one can disagree.
     */
    fun supports(alert: SystemAlert): Boolean = when (alert) {
        SystemAlert.FAILED -> state is GuidanceSafetyState.Failed
        SystemAlert.STALLED -> state.let {
            it is GuidanceSafetyState.StopRequired &&
                (it.reason == StopReason.STARTUP_STALLED || it.reason == StopReason.PERCEPTION_STALLED)
        }
        SystemAlert.INTERRUPTED -> state is GuidanceSafetyState.Interrupted
        SystemAlert.SPEECH_UNAVAILABLE -> OutputFault.SPEECH_UNAVAILABLE in axes.outputFaults
    }

    private inline fun update(nowMs: Long, cause: SafetyInput, change: () -> Unit) {
        change()
        val next = axesAt(nowMs)
        if (next == axes) return
        val nextState = next.state()
        if (nextState != state) stateSinceElapsedMs = nowMs
        axes = next
        state = nextState
        onChange(GuidanceSafetySnapshot(axes, state, stateSinceElapsedMs, nowMs, cause))
    }

    private fun axesAt(nowMs: Long) = GuidanceSafetyAxes(
        phase = phase,
        failure = failure,
        interrupted = interrupted,
        stall = if (watching) stallCondition(nowMs) else StallCondition.NONE,
        judgement = judgement,
        outputFaults = outputFaults(output.engineState, output.speechEnabled, output.screenReaderActive),
        expectedNavigationChannel = expectedNavigationChannel(
            speechEnabled = output.speechEnabled,
            screenReaderActive = output.screenReaderActive,
            hapticsEnabled = output.hapticsEnabled,
            speechReady = output.speechReady,
        ),
    )

    private val GuidanceRunPhase.isRunning: Boolean
        get() = this == GuidanceRunPhase.AWAITING_FIRST_RESULT || this == GuidanceRunPhase.RUNNING
}
