package com.sailens.shell.guidance.safety

import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.trace.GuidanceAlertKinds
import com.sailens.guidance.model.trace.GuidanceAlertTrace
import com.sailens.guidance.model.trace.SafetyStateTrace
import com.sailens.guidance.model.trace.SafetyTraceNames
import com.sailens.guidance.safety.traceLevel
import com.sailens.guidance.safety.traceReasons

// Every name here is a `when` without `else`: a new state, phase, condition, channel or input does not compile until
// it has its name, and the names are the closed sets the trace parser accepts.

/** The state's name in the trace. */
internal val GuidanceSafetyState.traceName: String
    get() = when (this) {
        GuidanceSafetyState.Idle -> SafetyTraceNames.States.IDLE
        GuidanceSafetyState.Initializing -> SafetyTraceNames.States.INITIALIZING
        GuidanceSafetyState.Guiding -> SafetyTraceNames.States.GUIDING
        is GuidanceSafetyState.Degraded -> SafetyTraceNames.States.DEGRADED
        is GuidanceSafetyState.StopRequired -> SafetyTraceNames.States.STOP_REQUIRED
        is GuidanceSafetyState.Interrupted -> SafetyTraceNames.States.INTERRUPTED
        is GuidanceSafetyState.Failed -> SafetyTraceNames.States.FAILED
    }

/** The state's reasons, lower case: for a degraded state the perception reasons, then the output faults. */
internal val GuidanceSafetyState.traceReasons: List<String>
    get() = when (this) {
        GuidanceSafetyState.Idle, GuidanceSafetyState.Initializing, GuidanceSafetyState.Guiding -> emptyList()
        is GuidanceSafetyState.Degraded -> perception.sorted().map { it.name.lowercase() } + output.sorted().map { it.name.lowercase() }
        is GuidanceSafetyState.StopRequired -> listOf(reason.name.lowercase())
        is GuidanceSafetyState.Interrupted -> listOf(reason.name.lowercase())
        is GuidanceSafetyState.Failed -> listOf(reason.name.lowercase())
    }

/** What the debug panel shows: the name, with the reasons when there are any. Equal to the trace's state label. */
internal val GuidanceSafetyState.label: String
    get() = traceReasons.let { reasons ->
        if (reasons.isEmpty()) traceName else reasons.sorted().joinToString(prefix = "$traceName(", postfix = ")", separator = ",")
    }

internal val GuidanceRunPhase.traceName: String
    get() = when (this) {
        GuidanceRunPhase.NOT_STARTED -> SafetyTraceNames.Phases.NOT_STARTED
        GuidanceRunPhase.STARTING -> SafetyTraceNames.Phases.STARTING
        GuidanceRunPhase.AWAITING_FIRST_RESULT -> SafetyTraceNames.Phases.AWAITING_FIRST_RESULT
        GuidanceRunPhase.RUNNING -> SafetyTraceNames.Phases.RUNNING
        GuidanceRunPhase.FAILED -> SafetyTraceNames.Phases.FAILED
    }

internal val StallCondition.traceName: String
    get() = when (this) {
        StallCondition.NONE -> SafetyTraceNames.Stalls.NONE
        StallCondition.STARTUP_TIMEOUT -> SafetyTraceNames.Stalls.STARTUP_TIMEOUT
        StallCondition.NO_RESULTS -> SafetyTraceNames.Stalls.NO_RESULTS
    }

internal val NavigationChannel.traceName: String
    get() = when (this) {
        NavigationChannel.SCREEN_READER -> SafetyTraceNames.Channels.SCREEN_READER
        NavigationChannel.SPEECH -> SafetyTraceNames.Channels.SPEECH
        NavigationChannel.HAPTICS_ONLY -> SafetyTraceNames.Channels.HAPTICS_ONLY
        NavigationChannel.STATUS_CARD_ONLY -> SafetyTraceNames.Channels.STATUS_CARD_ONLY
        NavigationChannel.NONE -> SafetyTraceNames.Channels.NONE
    }

internal val SafetyInput.traceName: String
    get() = when (this) {
        SafetyInput.START_REQUESTED -> SafetyTraceNames.Causes.START_REQUESTED
        SafetyInput.STALL_WATCH_STARTED -> SafetyTraceNames.Causes.STALL_WATCH_STARTED
        SafetyInput.FLOW_STARTED -> SafetyTraceNames.Causes.FLOW_STARTED
        SafetyInput.RESULT -> SafetyTraceNames.Causes.RESULT
        SafetyInput.FLOW_FAILED -> SafetyTraceNames.Causes.FLOW_FAILED
        SafetyInput.STOPPED -> SafetyTraceNames.Causes.STOPPED
        SafetyInput.LIFECYCLE_STOP -> SafetyTraceNames.Causes.LIFECYCLE_STOP
        SafetyInput.LIFECYCLE_START -> SafetyTraceNames.Causes.LIFECYCLE_START
        SafetyInput.OUTPUT -> SafetyTraceNames.Causes.OUTPUT
        SafetyInput.POLL -> SafetyTraceNames.Causes.POLL
    }

/** The alert's kind in a `guidance_alert` record. */
internal val SystemAlert.traceKind: String
    get() = when (this) {
        SystemAlert.FAILED -> GuidanceAlertKinds.FAILED
        SystemAlert.STALLED -> GuidanceAlertKinds.STALLED
        SystemAlert.INTERRUPTED -> GuidanceAlertKinds.INTERRUPTED
        SystemAlert.SPEECH_UNAVAILABLE -> GuidanceAlertKinds.SPEECH_UNAVAILABLE
    }

/**
 * The key of the sentence said for the alert: its string resource's name. Speech going unavailable has no sentence
 * (own speech is what failed), so null.
 */
internal val SystemAlert.noticeKey: String?
    get() = when (this) {
        SystemAlert.FAILED -> "notice_guidance_failed"
        SystemAlert.STALLED -> "notice_guidance_stalled"
        SystemAlert.INTERRUPTED -> "notice_guidance_interrupted"
        SystemAlert.SPEECH_UNAVAILABLE -> null
    }

/**
 * How the alert competes, as a `guidance_alert` priority: a stall notice is in the HIGH band, so a CRITICAL prompt
 * may cut it once results resume; nothing may cut the others, and every alert rhythm claims the motor unconditionally.
 */
internal val SystemAlert.tracePriority: String
    get() = when (this) {
        SystemAlert.STALLED -> EventPriority.HIGH.name.lowercase()
        SystemAlert.FAILED, SystemAlert.INTERRUPTED, SystemAlert.SPEECH_UNAVAILABLE -> GuidanceAlertTrace.MAX_PRIORITY
    }

/** [this] as its `safety_state` record, at [at] (wall clock). The trace service fills in the session and `seq`. */
internal fun GuidanceSafetySnapshot.toTrace(at: Long): SafetyStateTrace = SafetyStateTrace(
    at = at,
    elapsedMs = elapsedMs,
    stateSinceElapsedMs = stateSinceElapsedMs,
    state = state.traceName,
    reasons = state.traceReasons,
    phase = axes.phase.traceName,
    interrupted = axes.interrupted,
    stall = axes.stall.traceName,
    judgement = axes.judgement?.traceLevel,
    judgementReasons = axes.judgement?.traceReasons.orEmpty(),
    outputFaults = axes.outputFaults.sorted().map { it.name.lowercase() },
    expectedNavigationChannel = axes.expectedNavigationChannel.traceName,
    cause = cause.traceName,
)
