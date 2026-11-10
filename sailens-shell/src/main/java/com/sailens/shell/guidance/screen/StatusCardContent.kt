package com.sailens.shell.guidance.screen

import com.sailens.guidance.delivery.VisionFaultAlerts
import com.sailens.guidance.delivery.isOrdinaryPrompt
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.scene.SceneEventMessageKeys
import com.sailens.guidance.safety.DegradeReason
import com.sailens.output.SpeechEngineState
import com.sailens.shell.guidance.safety.GuidanceSafetyState
import com.sailens.shell.guidance.safety.HoldCause
import com.sailens.shell.guidance.safety.PromptHoldView

/** The status card's headline: a fixed sentence, a held stop's own sentence, a paused judgement's, or the current event's. */
internal sealed interface StatusTitle {
    data object Error : StatusTitle
    data object Interrupted : StatusTitle
    data object SpeechUnavailable : StatusTitle
    data object Starting : StatusTitle
    data object Idle : StatusTitle

    /** A stop holds and its cause is a vision fault: the sentence spoken for it, by message key. */
    data class HeldFault(val messageKey: String) : StatusTitle

    /** A stop holds because Guidance stalled: the stall notice's sentence. */
    data object HeldStall : StatusTitle

    /** The stop's cause has gone and fresh results are awaited. */
    data object Confirming : StatusTitle

    /** Part of the judgement is paused and no ordinary prompt is current: the sentence spoken for it, by message key. */
    data class Paused(val messageKey: String) : StatusTitle

    data class Event(val event: SceneEvent) : StatusTitle
    data object Clear : StatusTitle
}

internal enum class StatusDetail { ERROR, INTERRUPTED, SPEECH_UNAVAILABLE, STARTING, IDLE, HOLD_STOPPING, HOLD_RECOVERING, PATH_PAUSED, GUIDANCE, CLEAR }

internal enum class StatusLabel { ERROR, STARTING, STANDBY, PAUSED, PATH_PAUSED, LATEST_GUIDANCE, MONITORING }

/** What the status card says, before the words are looked up; [alert] colours it as needing attention. */
internal data class StatusCardContent(val title: StatusTitle, val detail: StatusDetail, val label: StatusLabel, val alert: Boolean)

/**
 * The status card, by priority: an error, an interruption, speech gone, starting, not running, a hold, no result yet,
 * an ordinary prompt, a paused judgement, any other event, and otherwise a clear way. The card says the way looks clear
 * only when Guidance judges it in full: never before the first result of a start, nor while part of the judgement is
 * paused.
 *
 * While prompts are held the card shows the stop or that the view is being confirmed, never an event: the screen keeps
 * none then. When speech gone takes the title during a hold, the detail is still the hold's, so the card says that
 * navigation prompts are paused as well. An interruption is itself the stop and says so.
 *
 * A paused judgement is named by the sentence its notice is spoken with, and stays on the card for as long as it holds,
 * not only while its event is current. An ordinary prompt (an obstacle's, say) goes above it, as it does on every
 * channel; the paused judgement's own event does not, so the card's label and detail do not change when that event
 * expires. Not coloured as an alert: it is a low-priority state, like its notice, and lasts as long as the floor does.
 *
 * Of [safety] only two things are read: whether Guidance judges at all ([GuidanceSafetyState.Guiding] or
 * [GuidanceSafetyState.Degraded]; otherwise, below the hold, the card says it is starting, which is the case of no
 * result since the start), and the judgements paused ([GuidanceSafetyState.Degraded.perception]). A degraded output alone changes
 * nothing here: the judgement is whole, and speech gone has its own tier. Every other tier comes from the screen's own
 * facts and the hold.
 *
 * @param speechBroken the session runs on own speech and the engine is unavailable.
 * @param safety where Guidance stands, as the safety monitor last published it.
 * @param event the current event, which the screen clears when a hold begins and does not set while one lasts.
 */
internal fun statusCardContent(
    errorShown: Boolean,
    interrupted: Boolean,
    speechBroken: Boolean,
    loading: Boolean,
    running: Boolean,
    safety: GuidanceSafetyState,
    hold: PromptHoldView?,
    event: SceneEvent?,
): StatusCardContent {
    val held = hold?.takeIf { running && !errorShown }
    // No result yet. A hold after the background is initializing too, and has its own tier above. Any other state in
    // which Guidance does not judge has a tier above whenever the screen's facts agree with the monitor; should they
    // not, the card still claims nothing.
    val awaiting = held == null && safety != GuidanceSafetyState.Guiding && safety !is GuidanceSafetyState.Degraded
    val paused = (safety as? GuidanceSafetyState.Degraded)?.perception?.minOrNull()
    val prompt = event?.takeIf { it.isOrdinaryPrompt }
    val title = when {
        errorShown -> StatusTitle.Error
        interrupted -> StatusTitle.Interrupted
        speechBroken -> StatusTitle.SpeechUnavailable
        loading -> StatusTitle.Starting
        !running -> StatusTitle.Idle
        held != null -> held.title()
        awaiting -> StatusTitle.Starting
        prompt != null -> StatusTitle.Event(prompt)
        paused != null -> StatusTitle.Paused(paused.messageKey)
        event != null -> StatusTitle.Event(event)
        else -> StatusTitle.Clear
    }
    val detail = when {
        errorShown -> StatusDetail.ERROR
        interrupted -> StatusDetail.INTERRUPTED
        held != null && !loading -> if (held.stopping) StatusDetail.HOLD_STOPPING else StatusDetail.HOLD_RECOVERING
        speechBroken -> StatusDetail.SPEECH_UNAVAILABLE
        loading -> StatusDetail.STARTING
        !running -> StatusDetail.IDLE
        awaiting -> StatusDetail.STARTING
        prompt != null -> StatusDetail.GUIDANCE
        paused != null -> paused.detail
        event != null -> StatusDetail.GUIDANCE
        else -> StatusDetail.CLEAR
    }
    val label = when {
        errorShown || interrupted || speechBroken -> StatusLabel.ERROR
        loading -> StatusLabel.STARTING
        !running -> StatusLabel.STANDBY
        held != null -> StatusLabel.PAUSED
        awaiting -> StatusLabel.STARTING
        prompt != null -> StatusLabel.LATEST_GUIDANCE
        paused != null -> paused.label
        event != null -> StatusLabel.LATEST_GUIDANCE
        else -> StatusLabel.MONITORING
    }
    // By the event the card shows: one a tier above keeps off the card does not colour it.
    val shown = (title as? StatusTitle.Event)?.event
    val alert = errorShown || interrupted || speechBroken || held != null || (shown != null && shown.priority >= EventPriority.HIGH)
    return StatusCardContent(title, detail, label, alert)
}

/**
 * The status card for the screen's state. Speech counts as gone while the session runs on own speech (on, and no
 * screen reader speaking instead) and the engine is unavailable: speech is the main channel, so losing it means the
 * user gets nothing, second only to the analysis itself failing. With a screen reader the own engine is not started at
 * all, which is no fault.
 */
internal fun SceneAnalysisUiState.statusCardContent(): StatusCardContent = statusCardContent(
    errorShown = errorMessage != null,
    interrupted = wasInterrupted,
    speechBroken = isRunning && isSpeechEnabled && !isScreenReaderActive && speechEngineState == SpeechEngineState.UNAVAILABLE,
    loading = isLoading,
    running = isRunning,
    safety = safetyState,
    hold = promptHold,
    event = activeStatusEvent,
)

private fun PromptHoldView.title(): StatusTitle = if (!stopping) StatusTitle.Confirming else when (cause) {
    HoldCause.CAMERA_OBSTRUCTED -> StatusTitle.HeldFault(SceneEventMessageKeys.CAMERA_BLOCKED)
    HoldCause.TOO_DARK -> StatusTitle.HeldFault(SceneEventMessageKeys.LOW_LIGHT)
    HoldCause.STARTUP_STALLED, HoldCause.PERCEPTION_STALLED -> StatusTitle.HeldStall
    // The interruption has its own tier above; once the app is back, the hold waits for fresh results.
    HoldCause.LIFECYCLE -> StatusTitle.Confirming
}

/** The sentence the paused judgement's notice is spoken with ([VisionFaultAlerts]), word for word. */
private val DegradeReason.messageKey: String
    get() = when (this) {
        DegradeReason.GROUND_UNRECOGNIZED -> VisionFaultAlerts.Fault.GROUND_UNRECOGNIZED.messageKey
    }

/** The floor unrecognised pauses the path prompts, and obstacles go on: the label says the first, the detail the second. */
private val DegradeReason.label: StatusLabel
    get() = when (this) {
        DegradeReason.GROUND_UNRECOGNIZED -> StatusLabel.PATH_PAUSED
    }

private val DegradeReason.detail: StatusDetail
    get() = when (this) {
        DegradeReason.GROUND_UNRECOGNIZED -> StatusDetail.PATH_PAUSED
    }
