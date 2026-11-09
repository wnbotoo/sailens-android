package com.sailens.shell.sim

import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.processor.decision.SpeechScale
import com.sailens.shell.device.GuidanceHaptic
import com.sailens.shell.device.HapticArbiter
import com.sailens.shell.device.MotorWait
import com.sailens.shell.device.NavigationClaim

/**
 * `HapticManager`'s seven calls onto the motor, mirrored: the same calls on the real [HapticArbiter] and [MotorWait],
 * in the same order, with the vibrator replaced by a recorder that reports a motor ([vibrates], default true). Each
 * step names its line in `HapticManager.kt`. `MotorWait`'s delayed `motorFree` is an event in the director's queue
 * ([schedule]), as the main handler's post is on a phone; removing the post takes it out.
 *
 * Each call is one `motor` line with what it got: played, refused, started, queued, reserved. A rhythm that waited
 * for the motor and starts later (a reservation, a queued navigation rhythm) is a `start` line when it does, one
 * that was waiting and is given up a `drop` line.
 *
 * Kept in step with the file by `SimulatorMirrorPinTest`.
 */
internal class SimHaptics(
    clock: () -> Long,
    private val schedule: (atMs: Long, action: () -> Unit) -> (() -> Unit),
    private val record: (TimelineEntry.Motor) -> Unit,
    private val now: () -> Long,
    private val vibrates: Boolean = true,
) : HapticDevice {
    // HapticManager.kt:35
    private val arbiter = HapticArbiter(clock = clock)

    private val posted = mutableListOf<() -> Unit>()

    // HapticManager.kt:238-244
    private val motorWait: MotorWait = MotorWait(
        arbiter,
        clock = clock,
        post = { delayMs -> posted += schedule(clock() + delayMs) { motorFree() } },
        removePosted = {
            posted.forEach { it() }
            posted.clear()
        },
    )
    private val motorFree: () -> Unit = { motorWait.onDue() }

    /** The call being made, so a rhythm that starts inside it is known to be its own. */
    private var call = 0L
    private var nextCall = 0L

    private inline fun <T> calling(block: (token: Long) -> T): T {
        val outer = call
        val token = ++nextCall
        call = token
        try {
            return block(token)
        } finally {
            call = outer
        }
    }

    private fun line(kind: String, rhythm: GuidanceHaptic? = null, result: String? = null) =
        record(TimelineEntry.Motor(now(), kind, rhythm, result))

    /** The vibrator: whether it was asked to play. */
    private fun vibrator(haptic: GuidanceHaptic): Boolean = vibrates

    /** A rhythm that had waited for the motor starts. */
    private fun started(token: Long, kind: String, haptic: GuidanceHaptic): Boolean {
        val own = token == call
        line(if (own) kind else "start", haptic, if (own) "started" else null)
        return own
    }

    // HapticManager.kt:253-259
    override fun trigger(event: SceneEvent): Boolean = calling {
        val haptic = GuidanceHaptic.forEvent(event)
        val claimed = arbiter.tryClaim(SpeechScale.of(event), haptic.durationMs)
        if (claimed) vibrator(haptic)
        motorWait.reschedule()
        line("trigger", haptic, if (claimed) "played" else "refused")
        claimed
    }

    // HapticManager.kt:268-281
    override fun triggerOrQueue(event: SceneEvent, onQueuedResult: (played: Boolean) -> Unit): NavigationClaim = calling { token ->
        val haptic = GuidanceHaptic.forEvent(event)
        var startedNow = false
        val claim = arbiter.claimOrQueueNavigation(
            priority = SpeechScale.of(event),
            durationMs = haptic.durationMs,
            expiresAtMs = event.expiresAt,
            start = {
                if (started(token, "queue", haptic)) startedNow = true
                vibrator(haptic)
            },
            onQueuedResult = { played ->
                if (!played) line("drop", haptic)
                onQueuedResult(played)
            },
        )
        motorWait.reschedule()
        if (!startedNow) line("queue", haptic, claim.name.lowercase())
        claim
    }

    // HapticManager.kt:293-299
    override fun playOrReserve(haptic: GuidanceHaptic, speechPriority: Int, amplitude: Int, onPlayed: () -> Unit) = calling { token ->
        val startedNow = arbiter.claimOrReserve(speechPriority, haptic.durationMs) {
            started(token, "reserve", haptic)
            vibrator(haptic)
            onPlayed()
        }
        motorWait.reschedule()
        if (!startedNow) line("reserve", haptic, "reserved")
    }

    // HapticManager.kt:302-305
    override fun cancelReservation() = calling {
        line("cancel_reservation")
        arbiter.cancelReservation()
        motorWait.reschedule()
    }

    // HapticManager.kt:314-319
    override fun play(haptic: GuidanceHaptic): Boolean = calling {
        arbiter.claimUnconditionally(haptic.durationMs)
        val vibrated = vibrator(haptic)
        motorWait.reschedule()
        line("alarm", haptic, if (vibrated) "played" else "refused")
        vibrated
    }

    // HapticManager.kt:326-331
    override fun cancelNavigation() = calling {
        line("cancel_navigation")
        arbiter.dropQueuedNavigation()
        // The vibrator's cancel, when the navigation rhythm is released, stops nothing a recorder keeps.
        arbiter.releaseNavigation()
        arbiter.startReservedIfFree()
        motorWait.reschedule()
    }

    // HapticManager.kt:334-338
    override fun cancel() = calling {
        line("cancel")
        arbiter.stop()
        motorWait.reschedule()
    }

    /** `HapticManager.kt:345`, private there. */
    private val GuidanceHaptic.durationMs: Long get() = timings.sum()
}
