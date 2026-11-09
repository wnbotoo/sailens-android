package com.sailens.shell.guidance.safety

import com.sailens.guidance.safety.BaselineQualification
import com.sailens.guidance.safety.PromptHold
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

/**
 * One change: the axes and state after [cause], observed at [elapsedMs]; the state has held since [stateSinceElapsedMs].
 * [release] is set on the change in which a hold ended: its evidence, kept here since the axes no longer hold it.
 */
internal data class GuidanceSafetySnapshot(
    val axes: GuidanceSafetyAxes,
    val state: GuidanceSafetyState,
    val stateSinceElapsedMs: Long,
    val elapsedMs: Long,
    val cause: SafetyInput,
    val release: PromptHold.Release<HoldCause>? = null,
)

/** The alerts about Guidance itself: what an input to the safety monitor can call for. */
internal enum class SystemAlert { FAILED, STALLED, INTERRUPTED, SPEECH_UNAVAILABLE }

/** Whether the screen says the session runs: from the flow's start until it fails or is stopped. */
internal val GuidanceRunPhase.isRunning: Boolean
    get() = this == GuidanceRunPhase.AWAITING_FIRST_RESULT || this == GuidanceRunPhase.RUNNING

/**
 * The safety monitor's inputs, one per event the view model handles, and what it holds after them. An input that can
 * call for an alert returns it, for this input alone: null when none is due.
 *
 * [GuidanceSafetyMonitor] is the one implementation. The interface is here so that a test can count the inputs that
 * reach it.
 */
internal interface SafetyMonitor {
    val axes: GuidanceSafetyAxes
    val state: GuidanceSafetyState
    val stateSinceElapsedMs: Long

    fun onStartRequested(nowMs: Long)
    fun onStallWatchStarted(nowMs: Long)
    fun onFlowStarted(nowMs: Long)

    /** [sequenceNumber] is the result's frame, [receivedAtMs] when its source received it (null: not recorded). */
    fun onResult(nowMs: Long, baseline: BaselineQualification, sequenceNumber: Long, receivedAtMs: Long?, speechReady: Boolean)

    /** Always [SystemAlert.FAILED]. */
    fun onFlowFailed(nowMs: Long, error: Throwable): SystemAlert?
    fun onStopped(nowMs: Long)

    /** [SystemAlert.INTERRUPTED] when the session runs. */
    fun onLifecycleStop(nowMs: Long): SystemAlert?
    fun onLifecycleStart(nowMs: Long)

    /** [SystemAlert.SPEECH_UNAVAILABLE] once per outage of own speech. */
    fun onOutput(nowMs: Long, facts: OutputFacts): SystemAlert?

    /** [SystemAlert.STALLED] when the session is stalled and an alarm is due. */
    fun poll(nowMs: Long): SystemAlert?
}

/**
 * Keeps the safety axes from the events the view model handles, derives the state from them, and says which of the
 * alerts about Guidance itself each input calls for.
 *
 * Each input is one of the view model's events, called at the same point, with the time it happened. After each, the
 * axes are rebuilt (the stall condition asked of [stallCondition] while the stall watch runs) and, when they or the
 * state changed, [onChange] hears of it; a change of state alone restarts [GuidanceSafetySnapshot.stateSinceElapsedMs].
 * Then the input returns the alert it calls for, if any, read from what it has just changed. An alert follows the
 * input, not a change of state: a stall that goes on is alarmed again every repeat, and only going to the background
 * is an interruption to tell.
 *
 * Each input also steps the prompt hold ([PromptHold]) once, after the facts changed: with the stop the facts call for
 * ([stopCause]) and, for a usable result, its frame and receipt. The hold is an axis, so the state shows it.
 *
 * The state itself is not announced. Pure and clock-driven, like the stall detector, and like it not thread-safe: the
 * view model calls it from the main thread only.
 *
 * @param stallCondition the stall detector's condition at a time: whether its limit has passed, nothing more.
 * @param stallAlarmDue the stall detector's alarm test at a time: its condition, then its repeat, which it records.
 */
internal class GuidanceSafetyMonitor(
    private val stallCondition: (nowMs: Long) -> StallCondition,
    private val stallAlarmDue: (nowMs: Long) -> Boolean,
    nowMs: Long,
    initialOutput: OutputFacts,
    private val onChange: (GuidanceSafetySnapshot) -> Unit = {},
) : SafetyMonitor {
    private var phase = GuidanceRunPhase.NOT_STARTED
    private var failure: FailureReason? = null
    private var interrupted = false
    private var watching = false
    private var judgement: BaselineQualification? = null
    private var output = initialOutput

    /**
     * The current outage of own speech has been told. It ends only when the engine leaves UNAVAILABLE: a stop and a
     * new start of Guidance, or a turn of the screen reader or the speech setting, keep it.
     */
    private var speechOutageTold = false

    /** Ordinary prompts held after a stop; kept only while the session runs. */
    private val promptHold = PromptHold<HoldCause>()

    override var axes: GuidanceSafetyAxes = axesAt(nowMs)
        private set
    override var state: GuidanceSafetyState = axes.state()
        private set
    override var stateSinceElapsedMs: Long = nowMs
        private set

    /** Start pressed: a new run, with no result and no failure yet. */
    override fun onStartRequested(nowMs: Long) = update(nowMs, SafetyInput.START_REQUESTED) {
        phase = GuidanceRunPhase.STARTING
        failure = null
        judgement = null
    }

    override fun onStallWatchStarted(nowMs: Long) = update(nowMs, SafetyInput.STALL_WATCH_STARTED) {
        watching = true
    }

    /** The analysis flow is collected: the view model says it is running from here. */
    override fun onFlowStarted(nowMs: Long) = update(nowMs, SafetyInput.FLOW_STARTED) {
        phase = GuidanceRunPhase.AWAITING_FIRST_RESULT
        failure = null
    }

    /** A result reached the view model, after the stall detector heard of it. */
    override fun onResult(nowMs: Long, baseline: BaselineQualification, sequenceNumber: Long, receivedAtMs: Long?, speechReady: Boolean) {
        val usable = if (baseline is BaselineQualification.Unqualified) null else PromptHold.Result(sequenceNumber, receivedAtMs)
        update(nowMs, SafetyInput.RESULT, usable) {
            if (phase == GuidanceRunPhase.AWAITING_FIRST_RESULT) phase = GuidanceRunPhase.RUNNING
            judgement = baseline
            output = output.copy(speechReady = speechReady)
        }
    }

    /**
     * The analysis flow threw; the stall watch stops with it. An interruption is kept, as the view model keeps it. The
     * failure is always told, in the background too.
     */
    override fun onFlowFailed(nowMs: Long, error: Throwable): SystemAlert? {
        update(nowMs, SafetyInput.FLOW_FAILED) {
            phase = GuidanceRunPhase.FAILED
            failure = if (error is GuidancePipelineFailedException) FailureReason.PIPELINE_FAILED else FailureReason.ERROR
            watching = false
        }
        return SystemAlert.FAILED
    }

    /** The user stopped, or the screen went away: the stall watch stops with it, and an interruption no longer stands. */
    override fun onStopped(nowMs: Long) = update(nowMs, SafetyInput.STOPPED) {
        phase = GuidanceRunPhase.NOT_STARTED
        failure = null
        watching = false
        interrupted = false
    }

    /**
     * The app went to the background. Only a running session is interrupted, and only then is it told: before the
     * flow's start, after a failure or a stop, there is nothing the user would lose.
     */
    override fun onLifecycleStop(nowMs: Long): SystemAlert? {
        val running = phase.isRunning
        update(nowMs, SafetyInput.LIFECYCLE_STOP) {
            if (running) interrupted = true
        }
        return if (running) SystemAlert.INTERRUPTED else null
    }

    /**
     * Back in the foreground. A running session whose stall watch runs waits for a first result again: the detector's
     * startup grace has just been restarted, before this.
     */
    override fun onLifecycleStart(nowMs: Long) = update(nowMs, SafetyInput.LIFECYCLE_START) {
        if (phase.isRunning && watching) phase = GuidanceRunPhase.AWAITING_FIRST_RESULT
        interrupted = false
    }

    /**
     * The output as the screen now holds it. Own speech being unavailable is told once per outage, as soon as the user
     * relies on it (the output axis has [OutputFault.SPEECH_UNAVAILABLE]): an outage that began under the screen reader
     * or with speech off is told when own speech is back, since the engine's state does not change again then. A new
     * outage begins only once the engine has left UNAVAILABLE. Guidance need not be running.
     */
    override fun onOutput(nowMs: Long, facts: OutputFacts): SystemAlert? {
        update(nowMs, SafetyInput.OUTPUT) {
            output = facts
        }
        if (facts.engineState != SpeechEngineState.UNAVAILABLE) {
            speechOutageTold = false
            return null
        }
        if (speechOutageTold || OutputFault.SPEECH_UNAVAILABLE !in axes.outputFaults) return null
        speechOutageTold = true
        return SystemAlert.SPEECH_UNAVAILABLE
    }

    /**
     * The stall watch's poll: the condition may have changed with time alone, and [nowMs] is the one time both the
     * state and the alarm are read at. In the background the interruption has been told and the camera gives no frames
     * by design, so no alarm is asked for: the alarm's repeat stays where it was.
     */
    override fun poll(nowMs: Long): SystemAlert? {
        update(nowMs, SafetyInput.POLL) {}
        if (interrupted) return null
        return if (stallAlarmDue(nowMs)) SystemAlert.STALLED else null
    }

    private inline fun update(nowMs: Long, cause: SafetyInput, result: PromptHold.Result? = null, change: () -> Unit) {
        change()
        val facts = axesAt(nowMs)
        val release = if (facts.phase.isRunning) {
            (promptHold.step(nowMs, facts.stopCause(), result) as? PromptHold.Change.Released)?.release
        } else {
            promptHold.clear()
            null
        }
        val next = facts.copy(hold = promptHold.held)
        if (next == axes) return
        val nextState = next.state()
        if (nextState != state) stateSinceElapsedMs = nowMs
        axes = next
        state = nextState
        onChange(GuidanceSafetySnapshot(axes, state, stateSinceElapsedMs, nowMs, cause, release))
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
}
