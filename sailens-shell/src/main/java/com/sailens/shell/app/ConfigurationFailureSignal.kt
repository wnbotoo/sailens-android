package com.sailens.shell.app

import com.sailens.core.log.LogService
import com.sailens.output.Announcement
import com.sailens.output.SpeechManager
import com.sailens.shell.device.GuidanceHaptic
import com.sailens.shell.device.HapticManager

/**
 * Announces a fatal configuration state through a channel the user can actually perceive.
 *
 * A release build must not crash-loop on a packaging mistake, but it must not fail quietly
 * either. For someone who cannot see the screen, "the app is showing an error" and "the app is
 * working and there is nothing ahead" are the same experience -- silence. So the failure is
 * pushed out at least once through a non-visual channel (architecture.md §5.2).
 *
 * Speech first, haptics when speech is not available. The haptic fallback **ignores the haptics
 * preference** on purpose: the channel the user chose is the one that is broken, and this is the
 * only one left. That is the same rule the speech-engine failure alarm follows, and it is one of
 * the three broadcast-layer invariants.
 *
 * The rhythm is [GuidanceHaptic.INTERRUPTED] -- the existing "assistance is not running" symbol,
 * the heaviest in the vocabulary and deliberately unlike any navigation cue. No new symbol is
 * introduced: the vocabulary is a closed set still awaiting blind-user testing.
 */
class ConfigurationFailureSignal(
    private val speechManager: SpeechManager,
    private val hapticManager: HapticManager,
    private val logService: LogService,
) {
    private var signalled = false

    /**
     * @param message already-localised text describing what is wrong.
     */
    fun signalOnce(message: String) {
        if (signalled) return
        signalled = true

        logService.error(TAG, "Fatal configuration state: $message")

        if (speechManager.isReady) {
            speechManager.speakSystemNotice(message)
            return
        }

        logService.error(TAG, "Speech unavailable; signalling configuration failure by haptics")
        hapticManager.play(GuidanceHaptic.INTERRUPTED)
    }

    private companion object {
        const val TAG = "ConfigFailure"
    }
}
