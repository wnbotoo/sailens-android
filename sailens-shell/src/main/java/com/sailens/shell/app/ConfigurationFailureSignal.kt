package com.sailens.shell.app

import com.sailens.core.log.LogService
import com.sailens.output.SpeechManager
import com.sailens.shell.device.GuidanceHaptic
import com.sailens.shell.device.HapticManager
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Announces a fatal configuration state through a channel the user can actually perceive.
 *
 * A release build must not crash-loop on a packaging mistake, but it must not fail quietly
 * either. For someone who cannot see the screen, "the app is showing an error" and "the app is
 * working and there is nothing ahead" are the same experience -- silence. So the failure is
 * pushed out at least once through a non-visual channel (architecture.md §5.2).
 *
 * **Haptics first, then speech.** Not "speech, or haptics if speech is broken": at the moment this
 * runs, nothing has started a TTS engine, because the app never reached the screen that does. So
 * `isReady` is false for a reason that has nothing to do with whether speech works, and treating
 * that as "speech is unavailable" meant the reason was never spoken at all — the user got one
 * buzz and no explanation. The buzz still comes first because it is immediate and needs no engine;
 * the engine is then started, and the reason is spoken once it is ready.
 *
 * If the engine never becomes ready, the haptic has already happened. That is the failsafe: the
 * user is told *that* something is wrong even when we cannot tell them *what*, which is the same
 * rule the speech-engine failure alarm follows and one of the three broadcast-layer invariants.
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
    /**
     * Atomic and outside composition: [androidx.compose.runtime.LaunchedEffect] restarts on
     * configuration change and process-death restore, and a fatal state that buzzes on every
     * rotation teaches the user to ignore it.
     */
    private val signalled = AtomicBoolean(false)

    /**
     * @param message already-localised text describing what is wrong. Spoken.
     * @param diagnostic developer-facing detail. Logged, never spoken.
     * @return true when this call was the one that signalled; false when it had already happened.
     */
    fun signalOnce(message: String, diagnostic: String = ""): Boolean {
        if (!signalled.compareAndSet(false, true)) return false

        logService.error(
            TAG,
            "Fatal configuration state: $message${if (diagnostic.isEmpty()) "" else " ($diagnostic)"}",
        )

        // Immediate and engine-free. Whatever happens to speech, the user knows something is wrong.
        hapticManager.play(GuidanceHaptic.INTERRUPTED)

        if (speechManager.isReady) {
            speechManager.speakSystemNotice(message)
            return true
        }

        // Nothing has started an engine yet — this state is reached before any screen that does.
        // Start one and say the reason when it arrives; if it never does, the haptic stands alone.
        logService.info(TAG, "Starting speech engine to announce the configuration failure")
        speechManager.initialize {
            speechManager.speakSystemNotice(message)
        }
        return true
    }

    private companion object {
        const val TAG = "ConfigFailure"
    }
}
