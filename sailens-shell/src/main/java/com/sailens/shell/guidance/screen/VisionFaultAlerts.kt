package com.sailens.shell.guidance.screen

import com.sailens.guidance.model.analysis.FrameQuality
import com.sailens.guidance.model.analysis.VisionFaults
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.scene.SceneEventMessageKeys

/**
 * Tells the user, without their eyes, that Guidance cannot see (det-first plan D5; design in
 * docs/detfirst-batch1-design, section 2): a covered lens, too little light, a floor the model does not
 * recognise. "No prompt" and "nothing ahead" feel the same to someone who cannot see the screen, so each
 * of these states must reach them, and keep reaching them while it lasts.
 *
 * As ordinary navigation events they could not: a refused prompt left no vibration, vibration obeyed the
 * navigation switch, and only the frame's first event was ever offered, so the floor state (LOW) could
 * wait forever behind obstacle prompts. This class owns all of their speech and vibration instead:
 *
 * - **entering**: the state's notice is spoken and, with fault vibration on, the vision-unreliable rhythm
 *   plays at once;
 * - **every cycle** ([Fault.periodMs]) one notice heard out is wanted; a covered lens also vibrates each
 *   cycle;
 * - **heard out** means the engine finished playing it ([onSpoken]); the screen reader has no such
 *   callback, so publishing to it counts;
 * - **the fallback**: if a cycle's notice has not been heard out by [Fault.deadlineMs] — dropped, cut
 *   off, expired, never confirmed, engine not ready, waiting for a description — the rhythm plays once
 *   **whatever the switches say**, and again at every later cycle while that holds. Speech that cannot
 *   get through must never leave the state silent;
 * - **recovering**: one best-effort notice that it is over.
 *
 * With speech off there is nothing to hear out: each cycle's rhythm is the notice itself, and fault
 * vibration counts as on whatever the switch says (Q11a).
 *
 * Hysteresis: frame quality is already debounced upstream; on top of it, clear has to last
 * [Timing.recoveryHoldMs] before it counts, so a hand waving over the lens does not alternate "covered"
 * and "clear". The floor reading is already time-confirmed both ways. While frame quality is faulty the
 * floor track is frozen: a picture that cannot be trusted says nothing about the floor either.
 *
 * Pure and driven by frames ([update]) and completion callbacks ([onSpoken]), so every rule is testable
 * on the JVM. Without frames nothing happens here; the stall detector owns that failure.
 */
internal class VisionFaultAlerts(
    private val outputs: Outputs,
    private val timing: Timing = Timing(),
) {
    /**
     * The three states and how each is said. Periods and priorities are the cooldowns and event
     * priorities they had as navigation events. The deadlines leave room for the prompt still being
     * spoken plus this sentence at rate 1.30 (D1.7: 1.18 / 1.06 / 3.41 s in Chinese) with more than
     * that to spare; they are not calibrated on a phone.
     */
    enum class Fault(
        val priority: EventPriority,
        val messageKey: String,
        val recoveryKey: String,
        val periodMs: Long,
        val deadlineMs: Long,
        val vibratesEachCycle: Boolean,
    ) {
        OBSTRUCTED(
            EventPriority.CRITICAL, SceneEventMessageKeys.CAMERA_BLOCKED, SceneEventMessageKeys.CAMERA_RESTORED,
            periodMs = 8_000, deadlineMs = 5_000, vibratesEachCycle = true,
        ),
        TOO_DARK(
            EventPriority.HIGH, SceneEventMessageKeys.LOW_LIGHT, SceneEventMessageKeys.LIGHT_RESTORED,
            periodMs = 15_000, deadlineMs = 6_000, vibratesEachCycle = false,
        ),
        GROUND_UNRECOGNIZED(
            EventPriority.LOW, SceneEventMessageKeys.GROUND_UNRECOGNIZED, SceneEventMessageKeys.GROUND_RECOGNIZED,
            periodMs = 60_000, deadlineMs = 10_000, vibratesEachCycle = false,
        ),
    }

    /** Not calibrated on a phone. */
    data class Timing(
        /** Frame quality back to OK has to last this long to count as recovered. */
        val recoveryHoldMs: Long = 2_000,
        /** A refused offer is made again no sooner than this (a refusal is recorded too). */
        val retryIntervalMs: Long = 500,
        /**
         * Accepted attempts per cycle: the first, and one resend once the deadline has passed without it
         * being heard out. Before the deadline an accepted notice may still be playing, so it is not
         * offered again; and a notice cut off again and again is not repeated as endless half sentences.
         */
        val maxAcceptedAttemptsPerCycle: Int = 2,
        /** A recovery notice not taken within this is dropped. */
        val recoveryNoticeTtlMs: Long = 5_000,
    )

    /** One line to say. [id] is new for every attempt; [expiresAtMs] bounds how long it holds the floor. */
    data class Notice(val id: String, val messageKey: String, val priority: EventPriority, val expiresAtMs: Long)

    enum class Offer {
        /** Nothing took it: equal or higher speech in progress, engine not ready, waiting for a description. */
        REFUSED,

        /** Own TTS started it; heard out only once [onSpoken] says so. */
        ACCEPTED,

        /** The screen reader took it. It reports no completion, so taking it is as far as can be known. */
        SPOKEN,
    }

    interface Outputs {
        fun speak(notice: Notice): Offer

        /** Plays the vision-unreliable rhythm, at [priority]'s strength, whatever the switches say. */
        fun vibrate(priority: EventPriority)
    }

    /**
     * @param speechEnabled the user's voice alerts.
     * @param faultHapticsEnabled the user's fault alert vibration; with speech off it counts as on.
     */
    data class Settings(val speechEnabled: Boolean, val faultHapticsEnabled: Boolean)

    private class Track(val fault: Fault) {
        var cycleStartMs = 0L
        var heardOut = false
        var acceptedAttempts = 0
        var fallbackPlayed = false
        var lastOfferAtMs: Long? = null

        /** This cycle's attempts that may be playing: a late completion of an older one does not count. */
        val attemptIds = mutableSetOf<String>()
    }

    private class Recovery(val messageKey: String, val expiresAtMs: Long, val ofFrameQuality: Boolean) {
        var lastOfferAtMs: Long? = null
    }

    private var frame: Track? = null

    /** When frame quality last turned clear while [frame] still holds; null while it is faulty. */
    private var frameClearSinceMs: Long? = null
    private var ground: Track? = null
    /** Recovery notices not said yet, oldest first: the lens and the floor can both recover in one frame. */
    private val recoveries = ArrayDeque<Recovery>()
    private var nextNoticeId = 0L

    /** One frame's states, after that frame's navigation prompt was offered. */
    fun update(now: Long, faults: VisionFaults, settings: Settings) {
        updateFrameQuality(now, faults.frameQuality, settings)
        // Frozen while frame quality is faulty, its hold included.
        if (frame == null) updateGround(now, faults.groundUnrecognized, settings)

        offerRecovery(now, settings)
        val active = if (frame != null) frame.takeIf { frameClearSinceMs == null } else ground
        active?.let { drive(it, now, settings) }
    }

    /** The engine finished playing the notice [id] (the screen reader never reports this). */
    fun onSpoken(id: String) {
        for (track in listOfNotNull(frame, ground)) {
            if (id in track.attemptIds) track.heardOut = true
        }
    }

    fun reset() {
        frame = null
        frameClearSinceMs = null
        ground = null
        recoveries.clear()
    }

    private fun updateFrameQuality(now: Long, quality: FrameQuality, settings: Settings) {
        val current = frame
        if (quality != FrameQuality.OK) {
            frameClearSinceMs = null
            val fault = if (quality == FrameQuality.OBSTRUCTED) Fault.OBSTRUCTED else Fault.TOO_DARK
            if (current?.fault == fault) return
            // A frame fault makes every recovery notice stale, the floor's included.
            recoveries.clear()
            // From one frame fault to the other: the new notice, but no entry rhythm and no recovery.
            frame = Track(fault).also { startCycle(it, now, settings, entering = current == null) }
            return
        }
        if (current == null) return
        val clearSince = frameClearSinceMs ?: now.also { frameClearSinceMs = it }
        if (now - clearSince < timing.recoveryHoldMs) return
        frame = null
        frameClearSinceMs = null
        recoveries.addLast(Recovery(current.fault.recoveryKey, now + timing.recoveryNoticeTtlMs, ofFrameQuality = true))
    }

    private fun updateGround(now: Long, unrecognized: Boolean, settings: Settings) {
        val current = ground
        if (unrecognized) {
            if (current != null) return
            // The floor failing again makes its own recovery stale, not the lens'.
            recoveries.removeAll { !it.ofFrameQuality }
            ground = Track(Fault.GROUND_UNRECOGNIZED).also { startCycle(it, now, settings, entering = true) }
        } else if (current != null) {
            ground = null
            recoveries.addLast(Recovery(current.fault.recoveryKey, now + timing.recoveryNoticeTtlMs, ofFrameQuality = false))
        }
    }

    private fun startCycle(track: Track, now: Long, settings: Settings, entering: Boolean) {
        track.cycleStartMs = now
        track.heardOut = false
        track.acceptedAttempts = 0
        track.fallbackPlayed = false
        track.lastOfferAtMs = null
        track.attemptIds.clear()
        // With speech off the rhythm is the notice: every cycle, and the switch cannot turn it off.
        val vibrates = if (!settings.speechEnabled) {
            true
        } else {
            settings.faultHapticsEnabled && (entering || track.fault.vibratesEachCycle)
        }
        if (vibrates) outputs.vibrate(track.fault.priority)
    }

    private fun drive(track: Track, now: Long, settings: Settings) {
        if (now - track.cycleStartMs >= track.fault.periodMs) startCycle(track, now, settings, entering = false)
        if (!settings.speechEnabled) return

        val pastDeadline = now - track.cycleStartMs >= track.fault.deadlineMs
        // Not yet taken: offered until it is. Taken: it may still be playing, so the resend waits for
        // the deadline.
        val mayOffer = track.acceptedAttempts == 0 || pastDeadline
        if (!track.heardOut && mayOffer && track.acceptedAttempts < timing.maxAcceptedAttemptsPerCycle &&
            retryDue(track.lastOfferAtMs, now)
        ) {
            track.lastOfferAtMs = now
            val notice = Notice(newNoticeId(), track.fault.messageKey, track.fault.priority, now + track.fault.deadlineMs)
            // Known before speaking, so a completion reported at once still counts.
            track.attemptIds += notice.id
            when (outputs.speak(notice)) {
                Offer.REFUSED -> track.attemptIds -= notice.id
                Offer.ACCEPTED -> track.acceptedAttempts++
                Offer.SPOKEN -> track.heardOut = true
            }
        }
        if (!track.heardOut && !track.fallbackPlayed && pastDeadline) {
            track.fallbackPlayed = true
            outputs.vibrate(track.fault.priority)
        }
    }

    /** The oldest recovery not said yet, one offer per frame; best effort, not tracked to completion. */
    private fun offerRecovery(now: Long, settings: Settings) {
        if (!settings.speechEnabled) recoveries.clear()
        recoveries.removeAll { now > it.expiresAtMs }
        val pending = recoveries.firstOrNull() ?: return
        if (!retryDue(pending.lastOfferAtMs, now)) return
        pending.lastOfferAtMs = now
        val notice = Notice(newNoticeId(), pending.messageKey, EventPriority.LOW, pending.expiresAtMs)
        if (outputs.speak(notice) != Offer.REFUSED) recoveries.removeFirst()
    }

    private fun retryDue(lastOfferAtMs: Long?, now: Long): Boolean =
        lastOfferAtMs == null || now - lastOfferAtMs >= timing.retryIntervalMs

    private fun newNoticeId(): String = "$NOTICE_ID_PREFIX${nextNoticeId++}"

    companion object {
        /** Notice ids, distinct from event ids (UUIDs) and system notices in the speech lifecycle record. */
        const val NOTICE_ID_PREFIX: String = "vision_notice_"
    }
}

/** Events whose state [VisionFaultAlerts] tells the user about: the shell does not speak them itself. */
internal fun SceneEvent.isToldByVisionFaultAlerts(): Boolean =
    category == EventCategory.SENSOR_QUALITY || category == EventCategory.GROUND_UNRECOGNIZED
