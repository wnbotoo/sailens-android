package com.sailens.shell.guidance.screen

import com.sailens.shell.app.GuidanceStartGate

/** What pressing the Guidance start/stop control does. */
internal sealed interface GuidanceToggleAction {
    data object Stop : GuidanceToggleAction
    data object Start : GuidanceToggleAction

    /** Guidance does not start; the reason is shown to the person. */
    data class Refuse(val reason: String) : GuidanceToggleAction
}

/**
 * Stopping is never held off, and a running session never consults the gate; starting asks the
 * gate (if one is bound) first. Kept pure so this is testable without the ViewModel.
 */
internal fun guidanceToggleAction(isRunning: Boolean, startGate: GuidanceStartGate?): GuidanceToggleAction {
    if (isRunning) return GuidanceToggleAction.Stop
    val blocked = startGate?.startBlockedReason() ?: return GuidanceToggleAction.Start
    return GuidanceToggleAction.Refuse(blocked)
}
