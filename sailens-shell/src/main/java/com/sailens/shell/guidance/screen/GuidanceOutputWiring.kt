package com.sailens.shell.guidance.screen

import com.sailens.core.log.LogService
import com.sailens.guidance.delivery.GuidanceCooldownPort
import com.sailens.guidance.delivery.GuidanceDescriptionPort
import com.sailens.guidance.delivery.GuidanceMotorPort
import com.sailens.guidance.delivery.GuidanceOutputProtocol
import com.sailens.guidance.delivery.GuidanceOutputSettings
import com.sailens.guidance.delivery.GuidanceScreenReaderPort
import com.sailens.guidance.delivery.GuidanceSettingsPort
import com.sailens.guidance.delivery.GuidanceSpeechPort
import com.sailens.guidance.delivery.VisionFaultAlerts
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.trace.PromptOutcomeTrace
import com.sailens.output.Announcement

/**
 * Whether a Guidance prompt waits for a scene description instead of cutting it off.
 *
 * The user asked for the description, usually standing still. A prompt that is not urgent —
 * a traffic light, a side obstacle, "path alerts paused" — is worth less than the answer they are
 * listening to, and cutting a description off leaves them with half a sentence about where they
 * are. So below [GUIDANCE_PREEMPTS_DESCRIPTION_AT] the prompt waits: it is not delivered, its
 * cooldown record is revoked, and it is offered again on a later frame, after the description has
 * finished, if it still holds. At or above it — a vehicle or person ahead, a blocked path, a
 * covered lens — Guidance preempts as before.
 */
internal fun guidanceWaitsForDescription(
    priority: EventPriority,
    descriptionHoldsTheFloor: Boolean,
): Boolean = descriptionHoldsTheFloor && priority < GUIDANCE_PREEMPTS_DESCRIPTION_AT

internal val GUIDANCE_PREEMPTS_DESCRIPTION_AT: EventPriority = EventPriority.HIGH

/**
 * The shell's side of [GuidanceOutputProtocol]: the Android outputs behind its ports, the policy between
 * Guidance and Describe, and the mapping from Guidance's types to what sailens-output understands.
 * Takes plain functions so that the wiring, and the order of a stop, can be checked on the JVM.
 *
 * @param speakOwn own TTS; the callback, when given, says the engine finished playing it.
 * @param announcementFor the seam of `docs/architecture.md` §6.6, mapping an event to text and a priority number.
 * @param eventText the copy of an event, for the screen reader.
 * @param noticeText the copy of a [VisionFaultAlerts] notice or recovery, by message key.
 * @param announceToScreenReader the shell's one route to the screen reader: with the Describe screen on
 *   top this screen's UI is not composed at all, and collecting here would silently drop the line.
 */
internal class GuidanceOutputWiring(
    speakOwn: (Announcement, onSpoken: (() -> Unit)?) -> Boolean,
    speechReady: () -> Boolean,
    private val stopSpeech: () -> Unit,
    announcementFor: (SceneEvent) -> Announcement,
    eventText: (SceneEvent) -> String,
    noticeText: (messageKey: String) -> String,
    announceToScreenReader: (String) -> Unit,
    vibrateEvent: (SceneEvent) -> Boolean,
    playOrReserveVisionFault: (EventPriority, onPlayed: () -> Unit) -> Unit,
    cancelVisionFaultReservation: () -> Unit,
    private val cancelHaptics: () -> Unit,
    descriptionHoldsTheFloor: () -> Boolean,
    preemptDescription: (reason: String) -> Unit,
    confirmDelivered: (SceneEvent) -> Unit,
    revokeUndelivered: (SceneEvent) -> Unit,
    outputSettings: () -> GuidanceOutputSettings,
    visionFaultSettings: () -> VisionFaultAlerts.Settings,
    logger: LogService,
    recordOutcome: (PromptOutcomeTrace) -> Unit,
    onReachedUser: (SceneEvent) -> Unit,
    clock: () -> Long,
) {
    val protocol = GuidanceOutputProtocol(
        speech = object : GuidanceSpeechPort {
            override val isReady: Boolean get() = speechReady()

            override fun speak(event: SceneEvent): Boolean = speakOwn(announcementFor(event), null)

            override fun speakNotice(notice: VisionFaultAlerts.Notice, onSpoken: () -> Unit): Boolean = speakOwn(
                Announcement(
                    id = notice.id,
                    key = notice.messageKey,
                    text = noticeText(notice.messageKey),
                    priority = notice.priority.value,
                    expiresAtMs = notice.expiresAtMs,
                ),
                onSpoken,
            )
        },
        screenReader = object : GuidanceScreenReaderPort {
            override fun announce(event: SceneEvent) = announceToScreenReader(eventText(event))

            override fun announceNotice(messageKey: String) = announceToScreenReader(noticeText(messageKey))
        },
        motor = object : GuidanceMotorPort {
            override fun vibrate(event: SceneEvent): Boolean = vibrateEvent(event)

            override fun vibrateVisionFault(priority: EventPriority, onPlayed: () -> Unit) =
                playOrReserveVisionFault(priority, onPlayed)

            override fun cancelVisionFaultVibration() = cancelVisionFaultReservation()
        },
        description = object : GuidanceDescriptionPort {
            override fun waits(priority: EventPriority): Boolean =
                guidanceWaitsForDescription(priority, descriptionHoldsTheFloor())

            override fun preempt(reason: String) = preemptDescription(reason)
        },
        cooldown = object : GuidanceCooldownPort {
            override fun confirm(event: SceneEvent) = confirmDelivered(event)

            override fun revoke(event: SceneEvent) = revokeUndelivered(event)
        },
        settings = object : GuidanceSettingsPort {
            override fun output(): GuidanceOutputSettings = outputSettings()

            override fun visionFaults(): VisionFaultAlerts.Settings = visionFaultSettings()
        },
        logger = logger,
        recordOutcome = recordOutcome,
        onReachedUser = onReachedUser,
        clock = clock,
    )

    /**
     * Guidance stopped. The protocol is reset before the outputs stop, so a completion the stop itself
     * brings about finds nothing left to report to, and a reserved rhythm is withdrawn first.
     *
     * @param beforeOutputsStop whatever else the stop does between the two.
     */
    inline fun stop(beforeOutputsStop: () -> Unit = {}) {
        protocol.reset()
        beforeOutputsStop()
        stopSpeech()
        cancelHaptics()
    }
}
