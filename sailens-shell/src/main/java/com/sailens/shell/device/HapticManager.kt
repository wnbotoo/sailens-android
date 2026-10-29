package com.sailens.shell.device

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.sailens.guidance.model.common.EventPriority
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
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Starts the reserved rhythm when the motor is free, or looks again when it turned out still busy. */
    private val startReserved = Runnable { if (!arbiter.startReservedIfFree()) scheduleReserved() }

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
     * Plays [haptic] for an alert that must be felt whole and must not wait for ever (det-first D5: the
     * vision-fault alerts, whose switches the caller has already applied). It does not cut a pattern of
     * equal or higher priority that is still playing: it reserves the motor instead, and starts the moment
     * that pattern ends, ahead of any rhythm asked for after it -- on the main thread, with or without new
     * frames. Once started nothing cuts it. One reservation at a time: a newer one replaces it.
     *
     * @param onPlayed called on the main thread when it starts, which may be before this returns.
     */
    fun playOrReserve(haptic: GuidanceHaptic, priority: EventPriority, amplitude: Int, onPlayed: () -> Unit) {
        val started = arbiter.claimOrReserve(priority.value, haptic.durationMs) {
            player.play(timings = haptic.timings, amplitude = amplitude)
            onPlayed()
        }
        if (!started) scheduleReserved()
    }

    /** Drops a reserved rhythm that has not started. */
    fun cancelReservation() {
        arbiter.cancelReservation()
        mainHandler.removeCallbacks(startReserved)
    }

    private fun scheduleReserved() {
        val freeAt = arbiter.reservationFreeAtMs() ?: return
        mainHandler.removeCallbacks(startReserved)
        mainHandler.postDelayed(startReserved, (freeAt - SystemClock.elapsedRealtime()).coerceAtLeast(0))
    }

    /**
     * Plays one symbol from the vocabulary unconditionally. The settings preview and the failure
     * alarms use this; a navigation rhythm arriving while an alarm plays does not cut it.
     */
    fun play(haptic: GuidanceHaptic, amplitude: Int = DEFAULT_AMPLITUDE) {
        arbiter.claimUnconditionally(haptic.durationMs)
        player.play(timings = haptic.timings, amplitude = amplitude)
    }

    /**
     * Stops the pattern playing. A reserved rhythm is kept -- it is an alert, not a navigation prompt, and
     * the motor is free for it now -- and starts at once; [cancelReservation] drops it.
     */
    fun cancel() {
        arbiter.clear()
        player.cancel()
        arbiter.startReservedIfFree()
    }

    private companion object {
        const val DEFAULT_AMPLITUDE = 205
    }
}

private val GuidanceHaptic.durationMs: Long get() = timings.sum()

/**
 * Which vibration pattern owns the motor right now. Pure, so the rule is testable on the JVM:
 * a new pattern may interrupt only a strictly lower-priority one that is still playing.
 *
 * One pattern may also wait for the motor ([claimOrReserve]): it goes first the moment the motor is
 * free -- every claim checks for it before anything else -- and nothing cuts it once started. So the
 * longest it waits is what is playing now, plus the strictly higher patterns that interrupt that one
 * (at most one per priority level, and alarms), however often new rhythms are asked for.
 */
internal class HapticArbiter(private val clock: () -> Long) {
    private var activePriority: Int? = null
    private var activeUntilMs: Long = 0L
    private var reserved: Reservation? = null

    private class Reservation(val durationMs: Long, val start: () -> Unit)

    @Synchronized
    fun tryClaim(priority: Int, durationMs: Long): Boolean {
        val now = clock()
        startReservedIfFree(now)
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

    /**
     * Starts [start] now unless a pattern of equal or higher priority is playing -- a strictly lower one is
     * interrupted, as by [tryClaim] -- and otherwise reserves the motor for it: it starts in
     * [startReservedIfFree] the moment the motor is free. Either way nothing cuts it once started. A newer
     * reservation replaces an older one.
     *
     * @return whether it started now.
     */
    @Synchronized
    fun claimOrReserve(priority: Int, durationMs: Long, start: () -> Unit): Boolean {
        val now = clock()
        startReservedIfFree(now)
        val active = activePriority
        if (active != null && now < activeUntilMs && priority <= active) {
            reserved = Reservation(durationMs, start)
            return false
        }
        reserved = null
        protect(now, durationMs)
        start()
        return true
    }

    /** Starts the reserved pattern if the motor is free. @return whether it started. */
    @Synchronized
    fun startReservedIfFree(now: Long = clock()): Boolean {
        val waiting = reserved ?: return false
        if (activePriority != null && now < activeUntilMs) return false
        reserved = null
        protect(now, waiting.durationMs)
        waiting.start()
        return true
    }

    /** When the motor will be free for the reserved pattern; null when none waits. */
    @Synchronized
    fun reservationFreeAtMs(): Long? = reserved?.let { activeUntilMs }

    @Synchronized
    fun cancelReservation() {
        reserved = null
    }

    private fun protect(now: Long, durationMs: Long) {
        activePriority = Int.MAX_VALUE
        activeUntilMs = now + durationMs
    }

    @Synchronized
    fun clear() {
        activePriority = null
        activeUntilMs = 0L
    }
}
