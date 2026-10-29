package com.sailens.shell.guidance.screen

import com.sailens.guidance.model.analysis.FrameQuality
import com.sailens.guidance.model.analysis.VisionFaults
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.scene.SceneEventMessageKeys
import java.util.UUID

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
 *   plays;
 * - **every cycle** ([Fault.periodMs]) the state wants to be told once; a covered lens also vibrates each
 *   cycle;
 * - **told** means: own speech finished playing a notice ([onSpoken]); or the screen reader took one
 *   (it reports no completion, so this is acceptance, not evidence it was heard); or, with speech off,
 *   the rhythm played — with speech off the rhythm is the notice, and fault vibration counts as on
 *   whatever the switch says (Q11a);
 * - **the fallback**: if the user has not been told within [Fault.deadlineMs] — notice dropped, cut off,
 *   expired, never confirmed, engine not ready, waiting for a description — the rhythm plays **whatever
 *   the switches say**, and again in every later cycle while that holds;
 * - **recovering**: one best-effort notice that it is over.
 *
 * The time since the user was last told is a debt per track (frame quality, floor) that no change of
 * state pays: switching between a covered lens and darkness, or recovering and failing again, keeps it.
 * Only being told, or the fallback playing, clears it. Otherwise a state that keeps changing could put
 * the fallback off for ever.
 *
 * Every rhythm goes through the motor's arbitration at the state's priority ([Outputs.vibrate]): it never
 * cuts a rhythm of equal or higher priority, so an obstacle rhythm already playing is felt whole. A
 * rhythm refused for that is still owed, and offered again on the next frame. "Whatever the switches
 * say" is about the switches, not about the motor.
 *
 * Hysteresis: frame quality is already debounced upstream; on top of it, clear has to last
 * [Timing.recoveryHoldMs] before it counts, so a hand waving over the lens does not alternate "covered"
 * and "clear". The floor reading is already time-confirmed both ways. While frame quality is faulty the
 * floor track is frozen: a picture that cannot be trusted says nothing about the floor either.
 *
 * Pure and driven by frames ([update]) and completion callbacks ([onSpoken]), so every rule is testable
 * on the JVM. Without frames nothing happens here; the stall detector owns that failure.
 *
 * @param noticeIdPrefix distinct per instance: the speech engine is shared, so a late completion of an
 *   earlier instance's notice must never match this one's.
 */
internal class VisionFaultAlerts(
    private val outputs: Outputs,
    private val timing: Timing = Timing(),
    private val noticeIdPrefix: String = "$NOTICE_ID_PREFIX${UUID.randomUUID()}_",
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
         * Accepted attempts per cycle: the first, and one resend once the first has had its whole
         * deadline window without being heard out. Within that window it may still be playing, so it is
         * not offered again; and a notice cut off again and again is not repeated as endless half
         * sentences.
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

        /** Own TTS started it; it counts as told only once [onSpoken] says it finished playing. */
        ACCEPTED,

        /**
         * The screen reader took it. It reports no completion, so this is as far as can be known; it is
         * not evidence the user heard it, and the fallback's guarantee does not extend to it.
         */
        PUBLISHED,
    }

    interface Outputs {
        fun speak(notice: Notice): Offer

        /**
         * Plays the vision-unreliable rhythm at [priority]'s strength, unless a rhythm of equal or higher
         * priority is still playing. Switches are not consulted here: the caller has applied them.
         *
         * @return whether it played; when not, it is offered again on a later frame.
         */
        fun vibrate(priority: EventPriority): Boolean
    }

    /**
     * @param speechEnabled the user's voice alerts.
     * @param faultHapticsEnabled the user's fault alert vibration; with speech off it counts as on.
     */
    data class Settings(val speechEnabled: Boolean, val faultHapticsEnabled: Boolean)

    /** Since when the user has not been told about a track's state; null when they have, or it is clear. */
    private class Debt {
        var sinceMs: Long? = null
    }

    private class Track(val fault: Fault, val debt: Debt) {
        var cycleStartMs = 0L
        var told = false
        var acceptedAttempts = 0
        var lastAcceptedAtMs: Long? = null
        var lastOfferAtMs: Long? = null
        var fallbackRequested = false

        /** A rhythm this cycle owes the user (entering, each cycle, the fallback), not played yet. */
        var vibrationOwed = false

        /** This cycle's attempts that may be playing: a late completion of an older one does not count. */
        val attemptIds = mutableSetOf<String>()
    }

    private class Recovery(val messageKey: String, val expiresAtMs: Long, val ofFrameQuality: Boolean) {
        var lastOfferAtMs: Long? = null
    }

    private val frameDebt = Debt()
    private val groundDebt = Debt()
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
            if (id in track.attemptIds) told(track)
        }
    }

    fun reset() {
        frame = null
        frameClearSinceMs = null
        ground = null
        frameDebt.sinceMs = null
        groundDebt.sinceMs = null
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
            // From one frame fault to the other: the new notice, but no entry rhythm and no recovery. The
            // debt carries over: changing kind does not count as telling.
            frame = Track(fault, frameDebt).also { startCycle(it, now, settings, entering = current == null) }
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
            ground = Track(Fault.GROUND_UNRECOGNIZED, groundDebt).also { startCycle(it, now, settings, entering = true) }
        } else if (current != null) {
            ground = null
            recoveries.addLast(Recovery(current.fault.recoveryKey, now + timing.recoveryNoticeTtlMs, ofFrameQuality = false))
        }
    }

    private fun startCycle(track: Track, now: Long, settings: Settings, entering: Boolean) {
        track.cycleStartMs = now
        track.told = false
        track.acceptedAttempts = 0
        track.lastAcceptedAtMs = null
        track.lastOfferAtMs = null
        track.fallbackRequested = false
        track.attemptIds.clear()
        // Kept if the user was never told about an earlier cycle, an earlier kind or an earlier episode.
        if (track.debt.sinceMs == null) track.debt.sinceMs = now
        // With speech off the rhythm is the notice: every cycle, and the switch cannot turn it off.
        track.vibrationOwed = if (!settings.speechEnabled) {
            true
        } else {
            settings.faultHapticsEnabled && (entering || track.fault.vibratesEachCycle)
        }
    }

    private fun drive(track: Track, now: Long, settings: Settings) {
        if (now - track.cycleStartMs >= track.fault.periodMs) startCycle(track, now, settings, entering = false)
        if (settings.speechEnabled) offerNotice(track, now)

        val untoldSince = track.debt.sinceMs
        if (!track.told && !track.fallbackRequested && untoldSince != null && now - untoldSince >= track.fault.deadlineMs) {
            track.fallbackRequested = true
            track.vibrationOwed = true
        }
        if (track.vibrationOwed && outputs.vibrate(track.fault.priority)) {
            track.vibrationOwed = false
            // The rhythm tells the user when it is the fallback, or the notice itself with speech off.
            if (track.fallbackRequested || !settings.speechEnabled) told(track)
        }
    }

    private fun offerNotice(track: Track, now: Long) {
        if (track.told || track.acceptedAttempts >= timing.maxAcceptedAttemptsPerCycle) return
        // Taken once already: it may still be playing until its own deadline window is over.
        val lastAccepted = track.lastAcceptedAtMs
        if (lastAccepted != null && now - lastAccepted < track.fault.deadlineMs) return
        if (!retryDue(track.lastOfferAtMs, now)) return
        track.lastOfferAtMs = now
        val notice = Notice(newNoticeId(), track.fault.messageKey, track.fault.priority, now + track.fault.deadlineMs)
        // Known before speaking, so a completion reported at once still counts.
        track.attemptIds += notice.id
        when (outputs.speak(notice)) {
            Offer.REFUSED -> track.attemptIds -= notice.id
            Offer.ACCEPTED -> {
                track.acceptedAttempts++
                track.lastAcceptedAtMs = now
            }
            Offer.PUBLISHED -> told(track)
        }
    }

    private fun told(track: Track) {
        track.told = true
        track.debt.sinceMs = null
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

    private fun newNoticeId(): String = "$noticeIdPrefix${nextNoticeId++}"

    companion object {
        /** Notice ids, distinct from event ids (UUIDs) and system notices in the speech lifecycle record. */
        const val NOTICE_ID_PREFIX: String = "vision_notice_"
    }
}

/** Events whose state [VisionFaultAlerts] tells the user about: the shell does not speak them itself. */
internal fun SceneEvent.isToldByVisionFaultAlerts(): Boolean =
    category == EventCategory.SENSOR_QUALITY || category == EventCategory.GROUND_UNRECOGNIZED
