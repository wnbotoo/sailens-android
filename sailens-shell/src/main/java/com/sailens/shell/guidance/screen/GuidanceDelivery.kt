package com.sailens.shell.guidance.screen

import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.trace.PromptRevokeReasons

/**
 * Offers one Guidance prompt to the output channels the user has enabled and reports whether it
 * actually reached them.
 *
 * "Reached" is what the cooldown is allowed to count (DecideEventsUseCase records the prompt
 * provisionally; false here revokes that record, so the prompt is offered again next frame). The
 * answer therefore has to be honest:
 *
 * - **Screen reader active**: it is the spoken channel and always accepts. Vibration accompanies.
 * - **Own TTS, engine ready**: speech decides. [speak] is false when a prompt of equal or higher
 *   priority is still being spoken; then nothing vibrates either, because a rhythm felt without
 *   its words would still count as delivered and the words would never come.
 * - **Own TTS, engine not ready**: speech cannot deliver (the attempt still asks the engine to
 *   start). Vibration, if enabled, is the channel that remains, and counts on its own.
 * - **Speech off**: vibration decides; with vibration off too, the status card is the channel
 *   the user chose and the prompt counts as delivered.
 *
 * Pure, so the rule is testable without Android.
 */
internal inline fun deliverGuidanceEvent(
    speechEnabled: Boolean,
    screenReaderActive: Boolean,
    speechEngineReady: Boolean,
    hapticsEnabled: Boolean,
    announceToScreenReader: () -> Unit,
    speak: () -> Boolean,
    vibrate: () -> Boolean,
): Boolean {
    if (speechEnabled && screenReaderActive) {
        announceToScreenReader()
        if (hapticsEnabled) vibrate()
        return true
    }
    if (speechEnabled && speechEngineReady) {
        if (!speak()) return false
        if (hapticsEnabled) vibrate()
        return true
    }
    if (speechEnabled) speak() // not ready: refused, but asks the engine to start
    if (hapticsEnabled) return vibrate()
    return !speechEnabled
}

/**
 * Whether a Guidance prompt waits for a scene description instead of cutting it off.
 *
 * The user asked for the description, usually standing still. A prompt that is not urgent —
 * a traffic light, a side obstacle, "path alerts paused" — is worth less than the answer they are
 * listening to, and cutting a description off leaves them with half a sentence about where they
 * are. So below [GUIDANCE_PREEMPTS_DESCRIPTION_AT] the prompt waits: it is not delivered, its
 * cooldown record is revoked, and it is offered again on a later frame, after the description has
 * finished, if it still holds. At or above it — a vehicle or person ahead, a blocked path, a
 * covered lens — Guidance preempts as before.
 */
internal fun guidanceWaitsForDescription(
    priority: EventPriority,
    descriptionHoldsTheFloor: Boolean,
): Boolean = descriptionHoldsTheFloor && priority < GUIDANCE_PREEMPTS_DESCRIPTION_AT

internal val GUIDANCE_PREEMPTS_DESCRIPTION_AT: EventPriority = EventPriority.HIGH

/**
 * Offers the frame's primary Guidance prompt, and keeps the cooldown honest either way.
 *
 * DecideEventsUseCase has already recorded the prompt in the cooldown, provisionally. Only a prompt
 * that reached the user may keep that record, so every path that does not deliver revokes it and the
 * prompt is offered again on a later frame while its condition still holds:
 *
 * 1. A description holds the floor and the prompt is not urgent ([guidanceWaitsForDescription]):
 *    wait. The description is left alone and nothing is delivered.
 * 2. Otherwise the description gives way first ([preemptDescription]) — before the prompt is
 *    spoken, so no clause of it can be left in front of the prompt or behind it.
 * 3. [deliver] refused (a prompt of equal or higher priority is still being spoken): not delivered.
 *
 * @param revoke called with the reason, one of [PromptRevokeReasons], so the trace can say why.
 * @return whether the prompt was delivered.
 */
internal inline fun offerGuidancePrompt(
    priority: EventPriority,
    descriptionHoldsTheFloor: Boolean,
    preemptDescription: () -> Unit,
    deliver: () -> Boolean,
    revoke: (reason: String) -> Unit,
): Boolean {
    if (guidanceWaitsForDescription(priority, descriptionHoldsTheFloor)) {
        revoke(PromptRevokeReasons.WAITING_FOR_DESCRIPTION)
        return false
    }
    preemptDescription()
    if (!deliver()) {
        revoke(PromptRevokeReasons.OUTPUT_REFUSED)
        return false
    }
    return true
}
