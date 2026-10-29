package com.sailens.shell.guidance.screen

import com.sailens.guidance.model.analysis.FrameQuality
import com.sailens.guidance.model.analysis.VisionFaults
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.scene.SceneEventMessageKeys
import java.util.UUID

/**
 * Tells the user, without their eyes, that Guidance cannot see: a covered lens, too little light, a floor
 * the model does not recognise. "No prompt" and "nothing ahead" feel the same to someone who cannot see the screen, so each
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
 * - **heard** means own speech finished playing a notice ([onSpoken]), or the screen reader took one (it
 *   reports no completion, so this is acceptance, not evidence it was heard). Speech is retried until
 *   heard, within [Timing.maxAcceptedAttemptsPerCycle];
 * - **told** means heard, or a rhythm that stands for the notice played: the fallback, or, with speech
 *   off, the cycle's rhythm (with speech off the rhythm is the notice, and fault vibration counts as on
 *   whatever the switch says: otherwise a user with speech off could not be told at all). Being told by
 *   a rhythm does not stop the speech retries;
 * - **the fallback**: if the user has not been told within [Fault.deadlineMs] — notice dropped, cut off,
 *   expired, never confirmed, engine not ready, waiting for a description — the rhythm plays **whatever
 *   the switches say**, and again in every later cycle while that holds. A notice heard before it plays
 *   cancels it;
 * - **recovering**: one best-effort notice that it is over.
 *
 * The time since the user was last told is a debt per track (frame quality, floor) that no change of
 * state pays: switching between a covered lens and darkness, or recovering and failing again, keeps it.
 * Only being told clears it. Otherwise a state that keeps changing could put the fallback off for ever.
 *
 * Rhythms go through the motor ([Outputs.vibrate]): one never cuts a rhythm of equal or higher priority
 * that is playing, but waits for it and then goes first, without waiting for a frame, so obstacle rhythms
 * asked for every frame cannot starve it. An owed rhythm is checked again whenever its reason may have
 * gone — the notice heard, a switch changed — and dropped if so. "Whatever the switches say" is about the
 * switches, not the motor.
 *
 * Hysteresis: frame quality is already debounced upstream; on top of it, clear has to last
 * [Timing.recoveryHoldMs] before it counts, so a hand waving over the lens does not alternate "covered"
 * and "clear". The floor reading is already time-confirmed both ways. While frame quality is faulty the
 * floor track is frozen: a picture that cannot be trusted says nothing about the floor either.
 *
 * Pure and driven by frames ([update]), settings ([settingsChanged]) and completion callbacks ([onSpoken],
 * the motor's), so every rule is testable on the JVM. Without frames no new state is seen here; the stall
 * detector owns that failure.
 *
 * Known limits, each accepted on purpose:
 *
 * - With the screen reader, a published notice counts as told: acceptance, not evidence it was heard.
 *   For screen-reader users the guarantee that does not rest on speech is the entry vibration, while
 *   fault vibration is on.
 * - The debt never expires: a short fault the user was never told about makes the next one, however
 *   much later, fall back at once. Any expiry could be got round by failing, recovering and failing again.
 * - A fault rhythm makes navigation rhythms wait. It holds the motor for the length of its long-short-long
 *   rhythm (under a second), and a new navigation rhythm is refused meanwhile. With speech on only the buzz that accompanies a prompt is
 *   lost; a prompt delivered by vibration alone waits for the next frame, its delay being what is left
 *   of the hold plus the time to that frame (about 3.4 s with frames up to 2.5 s apart, polling and
 *   scheduling on top). That is the price of a fault rhythm navigation rhythms can neither starve nor cut.
 * - With speech off there is no recovery notice: the periodic vibration stopping is the only sign.
 * - With speech off fault vibration is locked on, so Guidance cannot be made completely silent.
 * - The deadlines, [Timing.recoveryHoldMs] and [Timing.retryIntervalMs] are not calibrated on a phone.
 * - Among people indoors the floor notice is often cut off by obstacle prompts; the user then feels a
 *   fallback every cycle and may hear half a sentence. Its priority stays LOW: raising it would suppress
 *   obstacle prompts.
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
     * spoken plus this sentence at rate 1.30 (measured with the debug speech-duration tool: 1.18 /
     * 1.06 / 3.41 s in Chinese) with more than
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

        /** Own TTS started it; it counts as heard only once [onSpoken] says it finished playing. */
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
         * Plays the vision-unreliable rhythm at [priority]'s strength as soon as the motor allows: at once,
         * or — if a rhythm of equal or higher priority is playing — the moment it ends, ahead of anything
         * asked for later and without waiting for a frame. Once started nothing cuts it. [onPlayed] is
         * called when it starts, possibly before this returns. One request at a time: a newer one replaces
         * it. Switches are not consulted: the caller has applied them.
         */
        fun vibrate(priority: EventPriority, onPlayed: () -> Unit)

        /** Drops a requested rhythm that has not started. */
        fun cancelVibration()
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

        /** This cycle's notice was heard out (or published to the screen reader). Ends the speech retries. */
        var heard = false
        var acceptedAttempts = 0
        var lastAcceptedAtMs: Long? = null
        var lastOfferAtMs: Long? = null
        var fallbackRequested = false

        // Why a rhythm is owed this cycle; it is played once for all of them, and each can lapse alone.

        /** Entering, or each cycle for a covered lens; governed by the fault vibration switch. */
        var noticeBuzzOwed = false

        /** With speech off, the cycle's rhythm is the notice. */
        var speechOffBuzzOwed = false

        /** The fallback: the user not told by the deadline. Lapses when the notice is heard. */
        var fallbackBuzzOwed = false

        val buzzOwed: Boolean get() = noticeBuzzOwed || speechOffBuzzOwed || fallbackBuzzOwed

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

    /** The track whose rhythm is waiting for the motor, and the request's token (a stale start is ignored). */
    private var buzzRequestedBy: Track? = null
    private var buzzRequest = 0L

    /** The track whose state is told now, as of the last [observe]. */
    private var active: Track? = null

    /**
     * A frame, part 1, **before** its navigation prompt is offered: what holds now. A rhythm waiting for the
     * motor for a state that has just ended (or is frozen, or holding) is withdrawn here, before the prompt's
     * own rhythm asks for the motor -- otherwise the stale reservation would start first and refuse it.
     */
    fun observe(now: Long, faults: VisionFaults, settings: Settings) {
        updateFrameQuality(now, faults.frameQuality, settings)
        // Frozen while frame quality is faulty, its hold included.
        if (frame == null) updateGround(now, faults.groundUnrecognized, settings)
        active = if (frame != null) frame.takeIf { frameClearSinceMs == null } else ground
        // Only the active track's rhythm may wait for the motor: one that ended, is frozen or is holding
        // keeps what it owes but does not play it now.
        if (buzzRequestedBy != null && buzzRequestedBy !== active) cancelBuzzRequest()
    }

    /**
     * A frame, part 2, **after** its navigation prompt was offered: speak, owe and ask for rhythms. After,
     * so a higher prompt in the same frame does not cut off a notice just begun, and an obstacle rhythm is
     * not kept waiting by a rhythm asked for first.
     */
    fun act(now: Long, settings: Settings) {
        offerRecovery(now, settings)
        active?.let { drive(it, now, settings) }
    }

    /** Both parts of a frame, for one with no navigation prompt in between. */
    fun update(now: Long, faults: VisionFaults, settings: Settings) {
        observe(now, faults, settings)
        act(now, settings)
    }

    /** A switch changed: an owed rhythm that no longer has a reason is dropped now, not at the next frame. */
    fun settingsChanged(settings: Settings) {
        for (track in listOfNotNull(frame, ground)) {
            recheckOwedBuzz(track, settings)
            if (!track.buzzOwed && buzzRequestedBy === track) cancelBuzzRequest()
        }
    }

    /** The engine finished playing the notice [id] (the screen reader never reports this). */
    fun onSpoken(id: String) {
        for (track in listOfNotNull(frame, ground)) {
            if (id in track.attemptIds) heard(track)
        }
    }

    fun reset() {
        if (buzzRequestedBy != null) cancelBuzzRequest()
        frame = null
        frameClearSinceMs = null
        ground = null
        active = null
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
        track.heard = false
        track.acceptedAttempts = 0
        track.lastAcceptedAtMs = null
        track.lastOfferAtMs = null
        track.fallbackRequested = false
        track.fallbackBuzzOwed = false
        track.attemptIds.clear()
        // Kept if the user was never told about an earlier cycle, an earlier kind or an earlier episode.
        if (track.debt.sinceMs == null) track.debt.sinceMs = now
        // With speech off the rhythm is the notice: every cycle, and the switch cannot turn it off.
        track.speechOffBuzzOwed = !settings.speechEnabled
        track.noticeBuzzOwed = settings.speechEnabled && settings.faultHapticsEnabled &&
            (entering || track.fault.vibratesEachCycle)
    }

    private fun drive(track: Track, now: Long, settings: Settings) {
        if (now - track.cycleStartMs >= track.fault.periodMs) startCycle(track, now, settings, entering = false)
        recheckOwedBuzz(track, settings)
        if (settings.speechEnabled) offerNotice(track, now)

        val untoldSince = track.debt.sinceMs
        if (!track.heard && !track.fallbackRequested && untoldSince != null && now - untoldSince >= track.fault.deadlineMs) {
            track.fallbackRequested = true
            track.fallbackBuzzOwed = true
        }
        requestOwedBuzz(track)
    }

    /** Drops each reason for an owed rhythm that no longer holds; the fallback's own stays regardless of switches. */
    private fun recheckOwedBuzz(track: Track, settings: Settings) {
        if (!(settings.speechEnabled && settings.faultHapticsEnabled)) track.noticeBuzzOwed = false
        if (settings.speechEnabled) track.speechOffBuzzOwed = false
        if (track.heard) track.fallbackBuzzOwed = false
    }

    private fun requestOwedBuzz(track: Track) {
        if (!track.buzzOwed) {
            if (buzzRequestedBy === track) cancelBuzzRequest()
            return
        }
        if (buzzRequestedBy === track) return
        val request = ++buzzRequest
        buzzRequestedBy = track
        outputs.vibrate(track.fault.priority) { if (request == buzzRequest) buzzPlayed(track) }
    }

    private fun buzzPlayed(track: Track) {
        buzzRequestedBy = null
        // The fallback, or the notice itself with speech off, tells the user. The speech retries go on.
        if (track.fallbackBuzzOwed || track.speechOffBuzzOwed) track.debt.sinceMs = null
        track.noticeBuzzOwed = false
        track.speechOffBuzzOwed = false
        track.fallbackBuzzOwed = false
    }

    private fun cancelBuzzRequest() {
        buzzRequestedBy = null
        buzzRequest++
        outputs.cancelVibration()
    }

    private fun offerNotice(track: Track, now: Long) {
        if (track.heard || track.acceptedAttempts >= timing.maxAcceptedAttemptsPerCycle) return
        // Taken once already: it may still be playing, and holds the floor, until its deadline window is over.
        val lastAccepted = track.lastAcceptedAtMs
        if (lastAccepted != null && now - lastAccepted <= track.fault.deadlineMs) return
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
            Offer.PUBLISHED -> heard(track)
        }
    }

    private fun heard(track: Track) {
        track.heard = true
        track.debt.sinceMs = null
        // A fallback not played yet has lost its reason.
        track.fallbackBuzzOwed = false
        if (!track.buzzOwed && buzzRequestedBy === track) cancelBuzzRequest()
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
