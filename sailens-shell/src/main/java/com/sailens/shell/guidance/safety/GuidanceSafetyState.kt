package com.sailens.shell.guidance.safety

import com.sailens.guidance.safety.BaselineQualification
import com.sailens.guidance.safety.DegradeReason
import com.sailens.guidance.safety.PromptHold
import com.sailens.guidance.safety.UnqualifiedReason
import com.sailens.output.SpeechEngineState

/**
 * Where Guidance stands for the user, as one value: whether it is running, can judge the way ahead, and can be heard.
 *
 * Derived from [GuidanceSafetyAxes] by [state]. The debug panel and the trace show it, and it is not announced; the
 * monitor that keeps it decides when the alerts about Guidance itself are due (failed, stalled, interrupted, speech
 * unavailable), each from the input that calls for it. "I cannot say it" (the output) and "I cannot judge the way"
 * (the judgement) are separate axes, and the output alone never moves this into or out of [StopRequired],
 * [Interrupted] or [Failed].
 */
sealed interface GuidanceSafetyState {
    /** Not running. */
    data object Idle : GuidanceSafetyState

    /** Starting, or running and waiting for the first result (again, after coming back to the foreground). */
    data object Initializing : GuidanceSafetyState

    /** Running, judging as usual, and its own speech (if relied on) working. */
    data object Guiding : GuidanceSafetyState

    /** Running, with part of the judgement paused or the output impaired. At least one set is not empty. */
    data class Degraded(val perception: Set<DegradeReason>, val output: Set<OutputFault>) : GuidanceSafetyState {
        init {
            require(perception.isNotEmpty() || output.isNotEmpty()) { "A degraded state names at least one reason" }
        }
    }

    /** Running, and no prompt about the way ahead can be trusted: the user must stop. */
    data class StopRequired(val reason: StopReason) : GuidanceSafetyState

    /** Still a running session, but the app is in the background and the camera gives no frames. */
    data class Interrupted(val reason: InterruptReason) : GuidanceSafetyState

    /** The session ended on an error and does not recover by itself. */
    data class Failed(val reason: FailureReason) : GuidanceSafetyState
}

enum class StopReason { CAMERA_OBSTRUCTED, TOO_DARK, STARTUP_STALLED, PERCEPTION_STALLED }

enum class InterruptReason { LIFECYCLE }

enum class FailureReason {
    /** Too many frames in a row failed (`GuidancePipelineFailedException`). */
    PIPELINE_FAILED,

    /** Any other error that ended the analysis, at start or later. */
    ERROR,
}

/** The app's own speech, when the user relies on it, as its engine reports it. */
enum class OutputFault {
    /** The engine failed and has not yet proved it recovered by taking a submission. */
    SPEECH_UNAVAILABLE,

    /** The engine is idle or still initializing. */
    SPEECH_NOT_READY,
}

/**
 * Why ordinary prompts are held: the stop that began it. A stop for the camera or a stall is a [StopReason]; going to the
 * background is [LIFECYCLE].
 */
internal enum class HoldCause { CAMERA_OBSTRUCTED, TOO_DARK, STARTUP_STALLED, PERCEPTION_STALLED, LIFECYCLE }

/** The session's stage, from the facts the view model keeps today (`isLoading`, `isRunning`, a result seen). */
internal enum class GuidanceRunPhase {
    NOT_STARTED,

    /** Start pressed, `onStart` not reached: waiting for the previous run and the resources. */
    STARTING,

    /** Running, and the stall detector has no result yet: since the start, or since coming back to the foreground. */
    AWAITING_FIRST_RESULT,

    /** A result came. Stays so when the analysis flow ends without an error, as the view model does. */
    RUNNING,

    /** The analysis flow threw; until the next start. */
    FAILED,
}

/** Whether the stall detector's time limit has passed: the condition only, not whether an alarm is due. */
internal enum class StallCondition {
    NONE,

    /** No first result within the startup limit. */
    STARTUP_TIMEOUT,

    /** Results came, then none for longer than the stall limit. */
    NO_RESULTS,
}

/**
 * The channel a navigation prompt is expected to go out on next, by the branches of `deliverGuidanceEvent` and the
 * `isReady` read for that frame. A sample, not evidence of delivery: that is the `prompt_outcome` record.
 */
internal enum class NavigationChannel { SCREEN_READER, SPEECH, HAPTICS_ONLY, STATUS_CARD_ONLY, NONE }

/**
 * The facts the state is derived from. Nothing here is a judgement.
 *
 * @param failure set exactly when [phase] is [GuidanceRunPhase.FAILED].
 * @param interrupted the app went to the background during a running session and has not come back.
 * @param judgement the latest result's qualification; null before the first result of a start.
 * @param outputFaults the output's health, by the engine's state, as the speech-unavailable alert reads it.
 * @param expectedNavigationChannel by the `isReady` read for the latest result or output change; never in [state].
 * @param hold ordinary prompts held after a stop: from the moment a stop began until enough fresh results came after it
 *   ([PromptHold]). Only while the session runs.
 */
internal data class GuidanceSafetyAxes(
    val phase: GuidanceRunPhase,
    val failure: FailureReason?,
    val interrupted: Boolean,
    val stall: StallCondition,
    val judgement: BaselineQualification?,
    val outputFaults: Set<OutputFault>,
    val expectedNavigationChannel: NavigationChannel,
    val hold: PromptHold.Held<HoldCause>? = null,
) {
    init {
        require((phase == GuidanceRunPhase.FAILED) == (failure != null)) { "A failure is set exactly when the phase is FAILED: $phase $failure" }
        require(hold == null || phase.isRunning) { "A hold is kept only while the session runs: $phase" }
    }
}

/**
 * The summary state, by the first rule that holds:
 *
 * 1. failed; 2. not started; 3. interrupted (the view model skips the stall alarm meanwhile); 4. stalled (the
 * judgement is then stale); 5. starting or waiting for the first result (the judgement is from before the
 * background); 6. the picture cannot be trusted; 6b. prompts still held after a stop whose cause has gone: the stop
 * it was, or, after the background, initializing; 7. part of the judgement paused, or the output impaired; 8. guiding.
 *
 * Without a hold this is the mapping without rule 6b. The expected channel is not read: [NavigationChannel.NONE] alone
 * does not change the state.
 */
internal fun GuidanceSafetyAxes.state(): GuidanceSafetyState {
    if (phase == GuidanceRunPhase.FAILED) return GuidanceSafetyState.Failed(checkNotNull(failure))
    if (phase == GuidanceRunPhase.NOT_STARTED) return GuidanceSafetyState.Idle
    if (interrupted) return GuidanceSafetyState.Interrupted(InterruptReason.LIFECYCLE)
    when (stall) {
        StallCondition.STARTUP_TIMEOUT -> return GuidanceSafetyState.StopRequired(StopReason.STARTUP_STALLED)
        StallCondition.NO_RESULTS -> return GuidanceSafetyState.StopRequired(StopReason.PERCEPTION_STALLED)
        StallCondition.NONE -> Unit
    }
    if (phase == GuidanceRunPhase.STARTING || phase == GuidanceRunPhase.AWAITING_FIRST_RESULT) return GuidanceSafetyState.Initializing
    val perception: Set<DegradeReason> = when (val judged = judgement) {
        null, BaselineQualification.Qualified -> emptySet()
        is BaselineQualification.Degraded -> judged.reasons
        is BaselineQualification.Unqualified -> return GuidanceSafetyState.StopRequired(judged.reason.toStopReason())
    }
    hold?.let { held -> return held.cause.recoveringState() }
    if (perception.isNotEmpty() || outputFaults.isNotEmpty()) return GuidanceSafetyState.Degraded(perception, outputFaults)
    return GuidanceSafetyState.Guiding
}

/** What a hold shows once its stop's cause has gone and it waits for fresh results: the stop it was. */
private fun HoldCause.recoveringState(): GuidanceSafetyState = when (this) {
    HoldCause.CAMERA_OBSTRUCTED -> GuidanceSafetyState.StopRequired(StopReason.CAMERA_OBSTRUCTED)
    HoldCause.TOO_DARK -> GuidanceSafetyState.StopRequired(StopReason.TOO_DARK)
    HoldCause.STARTUP_STALLED -> GuidanceSafetyState.StopRequired(StopReason.STARTUP_STALLED)
    HoldCause.PERCEPTION_STALLED -> GuidanceSafetyState.StopRequired(StopReason.PERCEPTION_STALLED)
    HoldCause.LIFECYCLE -> GuidanceSafetyState.Initializing
}

/**
 * The stop these facts call for, by the mapping without the hold: a stop's reason, or [HoldCause.LIFECYCLE] for an
 * interruption; null when they call for none.
 */
internal fun GuidanceSafetyAxes.stopCause(): HoldCause? = when (val state = copy(hold = null).state()) {
    is GuidanceSafetyState.StopRequired -> when (state.reason) {
        StopReason.CAMERA_OBSTRUCTED -> HoldCause.CAMERA_OBSTRUCTED
        StopReason.TOO_DARK -> HoldCause.TOO_DARK
        StopReason.STARTUP_STALLED -> HoldCause.STARTUP_STALLED
        StopReason.PERCEPTION_STALLED -> HoldCause.PERCEPTION_STALLED
    }
    is GuidanceSafetyState.Interrupted -> HoldCause.LIFECYCLE
    GuidanceSafetyState.Idle, GuidanceSafetyState.Initializing, GuidanceSafetyState.Guiding,
    is GuidanceSafetyState.Degraded, is GuidanceSafetyState.Failed -> null
}

private fun UnqualifiedReason.toStopReason(): StopReason = when (this) {
    UnqualifiedReason.CAMERA_OBSTRUCTED -> StopReason.CAMERA_OBSTRUCTED
    UnqualifiedReason.TOO_DARK -> StopReason.TOO_DARK
}

/**
 * The output's health with the engine state's meaning, the same the speech-unavailable alert reads: own speech is
 * relied on when speech is on and no screen reader speaks instead.
 */
internal fun outputFaults(engineState: SpeechEngineState, speechEnabled: Boolean, screenReaderActive: Boolean): Set<OutputFault> {
    val reliesOnOwnSpeech = speechEnabled && !screenReaderActive
    if (!reliesOnOwnSpeech) return emptySet()
    return when (engineState) {
        SpeechEngineState.UNAVAILABLE -> setOf(OutputFault.SPEECH_UNAVAILABLE)
        SpeechEngineState.IDLE, SpeechEngineState.INITIALIZING -> setOf(OutputFault.SPEECH_NOT_READY)
        SpeechEngineState.READY -> emptySet()
    }
}

/**
 * The channel `deliverGuidanceEvent` would carry a prompt on, with [speechReady] the `isReady` it reads. A not-ready
 * engine is still asked to start by one `speak()`, which carries no prompt and so is not a channel. With speech on,
 * not ready and navigation vibration off, nothing carries it: [NavigationChannel.NONE].
 */
internal fun expectedNavigationChannel(
    speechEnabled: Boolean,
    screenReaderActive: Boolean,
    hapticsEnabled: Boolean,
    speechReady: Boolean,
): NavigationChannel = when {
    speechEnabled && screenReaderActive -> NavigationChannel.SCREEN_READER
    speechEnabled && speechReady -> NavigationChannel.SPEECH
    speechEnabled -> if (hapticsEnabled) NavigationChannel.HAPTICS_ONLY else NavigationChannel.NONE
    hapticsEnabled -> NavigationChannel.HAPTICS_ONLY
    else -> NavigationChannel.STATUS_CARD_ONLY
}
