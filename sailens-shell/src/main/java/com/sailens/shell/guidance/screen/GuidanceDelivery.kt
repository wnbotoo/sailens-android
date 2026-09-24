package com.sailens.shell.guidance.screen

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
