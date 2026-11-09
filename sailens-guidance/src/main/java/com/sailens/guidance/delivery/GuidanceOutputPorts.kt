package com.sailens.guidance.delivery

import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEvent

/**
 * What [GuidanceOutputProtocol] needs from the outside, in Guidance's own types: the module does not
 * depend on sailens-output or on any UI. The shell implements these with the speech engine, the motor,
 * the screen reader, the description coordinator and the copy; the closed-loop evaluation implements
 * them with its speaker model. The mapping to output priority numbers lives behind these ports.
 */
interface GuidanceSpeechPort {
    /** Whether the engine can take speech now. */
    val isReady: Boolean

    /**
     * Starts a frame, before its first offer: what the engine has ended by now no longer holds the speaker.
     *
     * @return the frame's time, which every offer of the frame is judged at.
     */
    fun settleFrame(): Long

    /**
     * Offers a navigation prompt. Returning true on the main thread only means it was taken; this is
     * always called there.
     *
     * @param atMs the frame's time, from [settleFrame].
     * @param onOutcome when given, what became of it if it is taken. It only changes the caller's state.
     * @return whether it was taken. False when a prompt of equal or higher priority is being spoken,
     *   when it expired, or when the engine is not ready (the attempt still asks the engine to start).
     */
    fun speak(event: SceneEvent, atMs: Long?, onOutcome: ((SpeechOutcome) -> Unit)?): Boolean

    /**
     * Offers a [VisionFaultAlerts] notice on the same terms.
     *
     * @param onSpoken called when the engine finished playing it, and not otherwise.
     */
    fun speakNotice(notice: VisionFaultAlerts.Notice, atMs: Long?, onSpoken: () -> Unit): Boolean

    /**
     * Offers the sentence saying which prompts this run gives ([ModeNotice]), on the same terms, at its own place
     * on the scale.
     *
     * @param id unique to this offer.
     */
    fun speakModeNotice(id: String, messageKey: String, atMs: Long, expiresAtMs: Long, onOutcome: (SpeechOutcome) -> Unit): Boolean
}

/** What became of a prompt the speech port took. */
enum class SpeechOutcome {
    /** The engine played it to the end. */
    SPOKEN,

    /** Given up on with no end known at that moment. */
    CUT,

    /** After [CUT]: the engine said it played to the end after all. */
    HEARD_AFTER_CUT,
}

/** The screen reader, when it is the spoken channel. It always takes a line and reports no completion. */
interface GuidanceScreenReaderPort {
    fun announce(event: SceneEvent)

    fun announceNotice(messageKey: String)
}

interface GuidanceMotorPort {
    /** Plays the navigation rhythm for [event] unless the motor refuses it; returns whether it played. */
    fun vibrate(event: SceneEvent): Boolean

    /**
     * Plays the rhythm for [event] where it may wait for the motor: always when it is the prompt's only channel, and,
     * with interrupted prompts not retried, when it goes with speech or the screen reader as well. Held off by an
     * alarm or a fault rhythm it waits for the motor instead of being refused.
     *
     * @param onQueuedResult for [MotorOffer.QUEUED]: whether it played in the end. Not called after a stop.
     */
    fun vibrateAlone(event: SceneEvent, onQueuedResult: (played: Boolean) -> Unit): MotorOffer

    /** See [VisionFaultAlerts.Outputs.vibrate]. */
    fun vibrateVisionFault(priority: EventPriority, onPlayed: () -> Unit)

    /** See [VisionFaultAlerts.Outputs.cancelVibration]. */
    fun cancelVisionFaultVibration()

    /**
     * Withdraws the rhythm waiting for the motor after [vibrateAlone] answered [MotorOffer.QUEUED], if one still waits:
     * its `onQueuedResult` hears that it did not play, before this returns. Nothing playing is cut. No default: an
     * implementation whose motor never queues says so with an empty body.
     */
    fun dropQueuedNavigation()
}

enum class MotorOffer { PLAYED, QUEUED, REFUSED }

/**
 * The scene description holding the floor. Whether a prompt waits for it is product policy between
 * Guidance and Describe, so the shell decides and this module only asks.
 */
interface GuidanceDescriptionPort {
    /** Whether a prompt at [priority] waits for the description instead of cutting it off. */
    fun waits(priority: EventPriority): Boolean

    /** Stops the description in flight, before a prompt is spoken. */
    fun preempt(reason: String)
}

/** The receipts that keep the cooldown honest about the record DecideEventsUseCase made provisionally. */
interface GuidanceCooldownPort {
    fun confirm(event: SceneEvent)

    /** @param reason one of `PromptRevokeReasons`. */
    fun revoke(event: SceneEvent, reason: String)

    /** Own speech took [event] and it was cut off: the cooldown takes it back or keeps it. */
    fun reportCut(event: SceneEvent)

    fun reportHeardAfterCut(event: SceneEvent)

    /** A channel took [event]. @return whether a rhythm goes with it: not for a prompt only saying again what was cut off. */
    fun accepted(event: SceneEvent): Boolean
}

/** The user's switches, read at the moment each decision is made. */
interface GuidanceSettingsPort {
    fun output(): GuidanceOutputSettings

    fun visionFaults(): VisionFaultAlerts.Settings
}
