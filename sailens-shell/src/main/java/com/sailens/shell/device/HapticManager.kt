package com.sailens.shell.device

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.processor.decision.SpeechScale
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
        if (!arbiter.tryClaim(SpeechScale.of(event), haptic.durationMs)) return false
        player.play(timings = haptic.timings, amplitude = GuidanceHaptic.amplitudeFor(event))
        return true
    }

    /**
     * Plays the rhythm for [event] when vibration is its only channel. Refused by a protected pattern, an alarm
     * or a fault rhythm, it waits for the motor rather than for the next frame, whose distance from now has
     * nothing to do with how soon the motor is free.
     *
     * @param onQueuedResult for [NavigationClaim.QUEUED], on the main thread: whether it played. Not called after a stop.
     */
    fun triggerOrQueue(event: SceneEvent, onQueuedResult: (played: Boolean) -> Unit): NavigationClaim {
        val haptic = GuidanceHaptic.forEvent(event)
        val claim = arbiter.claimOrQueueNavigation(
            priority = SpeechScale.of(event),
            durationMs = haptic.durationMs,
            expiresAtMs = event.expiresAt,
            start = {
                player.play(timings = haptic.timings, amplitude = GuidanceHaptic.amplitudeFor(event))
            },
            onQueuedResult = onQueuedResult,
        )
        if (claim == NavigationClaim.QUEUED) scheduleReserved()
        return claim
    }

    /**
     * Plays [haptic] for an alert that must be felt whole and must not wait for ever (the vision-fault
     * alerts, whose switches the caller has already applied). It does not cut a pattern of
     * equal or higher priority that is still playing: it reserves the motor instead, and starts the moment
     * that pattern ends, ahead of any rhythm asked for after it -- on the main thread, with or without new
     * frames. Once started nothing cuts it. One reservation at a time: a newer one replaces it.
     *
     * @param speechPriority on [SpeechScale], as the navigation rhythms it competes with.
     * @param onPlayed called on the main thread when it starts, which may be before this returns.
     */
    fun playOrReserve(haptic: GuidanceHaptic, speechPriority: Int, amplitude: Int, onPlayed: () -> Unit) {
        val started = arbiter.claimOrReserve(speechPriority, haptic.durationMs) {
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
     * Navigation vibration was turned off: stops the navigation rhythm playing, if that is what plays. An
     * alarm or a fault rhythm that has started plays to its end -- the switch does not govern them -- and a
     * reserved one still starts when the motor is free.
     */
    fun cancelNavigation() {
        arbiter.dropQueuedNavigation()
        if (arbiter.releaseNavigation()) player.cancel()
        arbiter.startReservedIfFree()
    }

    /** Guidance stopped: stops whatever plays and drops a reserved rhythm. */
    fun cancel() {
        mainHandler.removeCallbacks(startReserved)
        arbiter.stop()
        player.cancel()
    }

    private companion object {
        const val DEFAULT_AMPLITUDE = 205
    }
}

private val GuidanceHaptic.durationMs: Long get() = timings.sum()

/** What became of a navigation rhythm asked for by [HapticManager.triggerOrQueue]. */
enum class NavigationClaim { STARTED, QUEUED, REFUSED }

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
    private var queuedNavigation: QueuedNavigation? = null

    private class Reservation(val durationMs: Long, val start: () -> Unit)

    private class QueuedNavigation(
        val priority: Int,
        val durationMs: Long,
        val expiresAtMs: Long,
        val start: () -> Unit,
        val onResult: (played: Boolean) -> Unit,
    )

    /**
     * A navigation rhythm that is its prompt's only channel: as [tryClaim], except that held off by a protected
     * pattern it waits for the motor, behind the reservation. One waits at a time. A valid one of strictly
     * higher priority keeps its place and the new one is refused; otherwise the new one takes the place, and the
     * old one's result is that it did not play. Held off by another navigation rhythm it is refused, as ever.
     */
    @Synchronized
    fun claimOrQueueNavigation(priority: Int, durationMs: Long, expiresAtMs: Long, start: () -> Unit, onQueuedResult: (played: Boolean) -> Unit): NavigationClaim {
        val now = clock()
        startReservedIfFree(now)
        val active = activePriority
        if (active != null && now < activeUntilMs && priority <= active) {
            if (active != Int.MAX_VALUE) return NavigationClaim.REFUSED
            val waiting = queuedNavigation
            if (waiting != null && now <= waiting.expiresAtMs && waiting.priority > priority) return NavigationClaim.REFUSED
            queuedNavigation = QueuedNavigation(priority, durationMs, expiresAtMs, start, onQueuedResult)
            waiting?.onResult?.invoke(false)
            return NavigationClaim.QUEUED
        }
        activePriority = priority
        activeUntilMs = now + durationMs
        start()
        return NavigationClaim.STARTED
    }

    @Synchronized
    fun dropQueuedNavigation() {
        val waiting = queuedNavigation ?: return
        queuedNavigation = null
        waiting.onResult(false)
    }

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

    /** Starts what waits for the motor if it is free: the reservation first. @return whether anything started. */
    @Synchronized
    fun startReservedIfFree(now: Long = clock()): Boolean {
        if (activePriority != null && now < activeUntilMs) return false
        reserved?.let { waiting ->
            reserved = null
            protect(now, waiting.durationMs)
            waiting.start()
            return true
        }
        val navigation = queuedNavigation ?: return false
        queuedNavigation = null
        if (now > navigation.expiresAtMs) {
            navigation.onResult(false)
            return false
        }
        activePriority = navigation.priority
        activeUntilMs = now + navigation.durationMs
        navigation.start()
        navigation.onResult(true)
        return true
    }

    /** When the motor will be free for what waits for it; null when nothing does. */
    @Synchronized
    fun reservationFreeAtMs(): Long? = if (reserved != null || queuedNavigation != null) activeUntilMs else null

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

    /** Guidance stopped: what waits goes, and nobody is told, as its cooldown is being cleared. */
    @Synchronized
    fun stop() {
        reserved = null
        queuedNavigation = null
        clear()
    }

    /**
     * Navigation vibration was turned off. A protected pattern still playing (an alarm, or a reserved
     * pattern once started) is not navigation and keeps the motor; anything else is released.
     *
     * @return whether the motor should be stopped.
     */
    @Synchronized
    fun releaseNavigation(now: Long = clock()): Boolean {
        if (activePriority == Int.MAX_VALUE && now < activeUntilMs) return false
        clear()
        return true
    }
}
