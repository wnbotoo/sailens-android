package com.sailens.shell.guidance.screen

import com.sailens.shell.app.GuidanceStartGate

/** What pressing the Guidance start/stop control does. */
internal sealed interface GuidanceToggleAction {
    data object Stop : GuidanceToggleAction
    data object Start : GuidanceToggleAction

    /** Guidance does not start; the reason is shown to the person. */
    data class Refuse(val reason: String) : GuidanceToggleAction

    /**
     * The person has not confirmed the current safety notice: it is shown instead, and Guidance does
     * not start. Its button starts Guidance; leaving it any other way does not.
     */
    data object ShowSafetyNotice : GuidanceToggleAction
}

/**
 * Stopping is never held off, and a running session consults neither the gate nor the safety
 * notice. Starting asks the gate (if one is bound) first, then needs the current safety notice
 * confirmed: a start the gate refuses anyway does not first ask the person to read the notice. Kept
 * pure so this is testable without the ViewModel.
 */
internal fun guidanceToggleAction(
    isRunning: Boolean,
    startGate: GuidanceStartGate?,
    safetyNoticeConfirmed: Boolean,
): GuidanceToggleAction {
    if (isRunning) return GuidanceToggleAction.Stop
    val blocked = startGate?.startBlockedReason()
    if (blocked != null) return GuidanceToggleAction.Refuse(blocked)
    return if (safetyNoticeConfirmed) GuidanceToggleAction.Start else GuidanceToggleAction.ShowSafetyNotice
}
