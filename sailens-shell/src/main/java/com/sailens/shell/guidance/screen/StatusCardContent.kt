package com.sailens.shell.guidance.screen

import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.scene.SceneEventMessageKeys
import com.sailens.shell.guidance.safety.HoldCause
import com.sailens.shell.guidance.safety.PromptHoldView

/** The status card's headline: a fixed sentence, a held stop's own sentence, or the current event's. */
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

    data class Event(val event: SceneEvent) : StatusTitle
    data object Clear : StatusTitle
}

internal enum class StatusDetail { ERROR, INTERRUPTED, SPEECH_UNAVAILABLE, STARTING, IDLE, HOLD_STOPPING, HOLD_RECOVERING, GUIDANCE, CLEAR }

internal enum class StatusLabel { ERROR, STARTING, STANDBY, PAUSED, LATEST_GUIDANCE, MONITORING }

/** What the status card says, before the words are looked up; [alert] colours it as needing attention. */
internal data class StatusCardContent(val title: StatusTitle, val detail: StatusDetail, val label: StatusLabel, val alert: Boolean)

/**
 * The status card, by priority: an error, an interruption, speech gone, starting, not running, a hold, the current
 * event, and otherwise a clear way. While prompts are held the card shows the stop or that the view is being confirmed,
 * never an event: the screen keeps none then. When speech gone takes the title during a hold, the detail is still the
 * hold's, so the card says that navigation prompts are paused as well. An interruption is itself the stop and says so.
 *
 * @param speechBroken the session runs on own speech and the engine is unavailable.
 * @param event the current event, which the screen clears when a hold begins and does not set while one lasts.
 */
internal fun statusCardContent(
    errorShown: Boolean,
    interrupted: Boolean,
    speechBroken: Boolean,
    loading: Boolean,
    running: Boolean,
    hold: PromptHoldView?,
    event: SceneEvent?,
): StatusCardContent {
    val held = hold?.takeIf { running && !errorShown }
    val title = when {
        errorShown -> StatusTitle.Error
        interrupted -> StatusTitle.Interrupted
        speechBroken -> StatusTitle.SpeechUnavailable
        loading -> StatusTitle.Starting
        !running -> StatusTitle.Idle
        held != null -> held.title()
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
        event != null -> StatusDetail.GUIDANCE
        else -> StatusDetail.CLEAR
    }
    val label = when {
        errorShown || interrupted || speechBroken -> StatusLabel.ERROR
        loading -> StatusLabel.STARTING
        !running -> StatusLabel.STANDBY
        held != null -> StatusLabel.PAUSED
        event != null -> StatusLabel.LATEST_GUIDANCE
        else -> StatusLabel.MONITORING
    }
    val alert = errorShown || interrupted || speechBroken || held != null || (event != null && event.priority >= EventPriority.HIGH)
    return StatusCardContent(title, detail, label, alert)
}

private fun PromptHoldView.title(): StatusTitle = if (!stopping) StatusTitle.Confirming else when (cause) {
    HoldCause.CAMERA_OBSTRUCTED -> StatusTitle.HeldFault(SceneEventMessageKeys.CAMERA_BLOCKED)
    HoldCause.TOO_DARK -> StatusTitle.HeldFault(SceneEventMessageKeys.LOW_LIGHT)
    HoldCause.STARTUP_STALLED, HoldCause.PERCEPTION_STALLED -> StatusTitle.HeldStall
    // The interruption has its own tier above; once the app is back, the hold waits for fresh results.
    HoldCause.LIFECYCLE -> StatusTitle.Confirming
}
