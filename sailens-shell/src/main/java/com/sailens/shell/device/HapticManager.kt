package com.sailens.shell.device

import android.content.Context
import android.os.SystemClock
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
    private val arbiter = HapticArbiter(clock = SystemClock::elapsedRealtime)

    /**
     * Plays the rhythm for [event], unless a pattern of equal or higher priority is still playing.
     * A new pattern cancels the one in progress, so without this a MEDIUM rhythm would cut a
     * CRITICAL one short -- and a rhythm cut short is a different symbol.
     *
     * @return whether the pattern was played.
     */
    fun trigger(event: SceneEvent): Boolean {
        val haptic = GuidanceHaptic.forEvent(event)
        if (!arbiter.tryClaim(event.priority.value, haptic.durationMs)) return false
        player.play(timings = haptic.timings, amplitude = GuidanceHaptic.amplitudeFor(event))
        return true
    }

    /**
     * Plays one symbol from the vocabulary unconditionally. The settings preview and the failure
     * alarms use this; a navigation rhythm arriving while an alarm plays does not cut it.
     */
    fun play(haptic: GuidanceHaptic, amplitude: Int = DEFAULT_AMPLITUDE) {
        arbiter.claimUnconditionally(haptic.durationMs)
        player.play(timings = haptic.timings, amplitude = amplitude)
    }

    fun cancel() {
        arbiter.clear()
        player.cancel()
    }

    private companion object {
        const val DEFAULT_AMPLITUDE = 205
    }
}

private val GuidanceHaptic.durationMs: Long get() = timings.sum()

/**
 * Which vibration pattern owns the motor right now. Pure, so the rule is testable on the JVM:
 * a new pattern may interrupt only a strictly lower-priority one that is still playing.
 */
internal class HapticArbiter(private val clock: () -> Long) {
    private var activePriority: Int? = null
    private var activeUntilMs: Long = 0L

    @Synchronized
    fun tryClaim(priority: Int, durationMs: Long): Boolean {
        val now = clock()
        val active = activePriority
        if (active != null && now < activeUntilMs && priority <= active) return false
        activePriority = priority
        activeUntilMs = now + durationMs
        return true
    }

    @Synchronized
    fun claimUnconditionally(durationMs: Long) {
        activePriority = Int.MAX_VALUE
        activeUntilMs = clock() + durationMs
    }

    @Synchronized
    fun clear() {
        activePriority = null
        activeUntilMs = 0L
    }
}
