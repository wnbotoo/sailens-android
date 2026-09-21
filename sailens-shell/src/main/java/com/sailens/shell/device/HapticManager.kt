package com.sailens.shell.device

import android.content.Context
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.output.HapticPlayer

/**
 * Guidance's haptic channel: turns navigation meaning into a rhythm from [GuidanceHaptic] and
 * hands it to the output primitive.
 *
 * Vibration is the only channel that needs neither the TTS engine nor the audio the user is
 * already listening to, so it carries two jobs: the full navigation vocabulary when speech is
 * off, and the fallback alarm when speech itself has failed.
 *
 * The vocabulary is the Guidance part and stays here; reaching the motor is mechanism and lives
 * in sailens-output (architecture.md §6.6).
 */
class HapticManager(context: Context) {

    private val player = HapticPlayer(context)

    fun trigger(event: SceneEvent) {
        play(
            haptic = GuidanceHaptic.forEvent(event),
            amplitude = GuidanceHaptic.amplitudeFor(event.priority),
        )
    }

    /** Plays one symbol from the vocabulary. The settings preview and the failure alarms use this. */
    fun play(haptic: GuidanceHaptic, amplitude: Int = DEFAULT_AMPLITUDE) {
        player.play(timings = haptic.timings, amplitude = amplitude)
    }

    fun cancel() {
        player.cancel()
    }

    private companion object {
        const val DEFAULT_AMPLITUDE = 205
    }
}
