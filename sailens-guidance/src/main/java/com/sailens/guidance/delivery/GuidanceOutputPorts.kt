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
     * Offers a navigation prompt.
     *
     * @return whether it was taken. False when a prompt of equal or higher priority is being spoken,
     *   when it expired, or when the engine is not ready (the attempt still asks the engine to start).
     */
    fun speak(event: SceneEvent): Boolean

    /**
     * Offers a [VisionFaultAlerts] notice on the same terms.
     *
     * @param onSpoken called when the engine finished playing it, and not otherwise.
     */
    fun speakNotice(notice: VisionFaultAlerts.Notice, onSpoken: () -> Unit): Boolean
}

/** The screen reader, when it is the spoken channel. It always takes a line and reports no completion. */
interface GuidanceScreenReaderPort {
    fun announce(event: SceneEvent)

    fun announceNotice(messageKey: String)
}

interface GuidanceMotorPort {
    /** Plays the navigation rhythm for [event] unless the motor refuses it; returns whether it played. */
    fun vibrate(event: SceneEvent): Boolean

    /** See [VisionFaultAlerts.Outputs.vibrate]. */
    fun vibrateVisionFault(priority: EventPriority, onPlayed: () -> Unit)

    /** See [VisionFaultAlerts.Outputs.cancelVibration]. */
    fun cancelVisionFaultVibration()
}

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

    fun revoke(event: SceneEvent)
}

/** The user's switches, read at the moment each decision is made. */
interface GuidanceSettingsPort {
    fun output(): GuidanceOutputSettings

    fun visionFaults(): VisionFaultAlerts.Settings
}
