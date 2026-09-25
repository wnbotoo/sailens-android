package com.sailens.shell.guidance.screen

import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.trace.PromptDeliveryChannels
import com.sailens.guidance.model.trace.PromptOutcomeTrace
import com.sailens.guidance.model.trace.PromptRevokeReasons

/**
 * Which channels accepted a Guidance prompt, from [PromptDeliveryChannels]. Empty means it reached
 * no one. This is the evidence the trace records: the output *settings* cannot say whether a prompt
 * was heard or only felt.
 */
internal data class GuidanceDeliveryResult(val channels: List<String>) {
    val delivered: Boolean get() = channels.isNotEmpty()

    companion object {
        val NOT_DELIVERED = GuidanceDeliveryResult(emptyList())
    }
}

/**
 * Offers one Guidance prompt to the output channels the user has enabled and reports which of
 * them actually accepted it.
 *
 * "Accepted by at least one" is what the cooldown is allowed to count (DecideEventsUseCase records
 * the prompt provisionally; an empty result revokes that record, so the prompt is offered again next
 * frame). The answer therefore has to be honest:
 *
 * - **Screen reader active**: it is the spoken channel and always accepts. Vibration accompanies,
 *   and is listed only if the motor took it.
 * - **Own TTS, engine ready**: speech decides. [speak] is false when a prompt of equal or higher
 *   priority is still being spoken; then nothing vibrates either, because a rhythm felt without
 *   its words would still count as delivered and the words would never come.
 * - **Own TTS, engine not ready**: speech cannot deliver (the attempt still asks the engine to
 *   start). Vibration, if enabled, is the channel that remains, and counts on its own — the prompt
 *   was felt, not heard.
 * - **Speech off**: vibration decides; with vibration off too, the status card is the channel
 *   the user chose and the prompt counts as delivered.
 *
 * Pure, so the rule is testable without Android.
 */
internal inline fun deliverGuidanceEvent(
    speechEnabled: Boolean,
    screenReaderActive: Boolean,
    speechEngineReady: Boolean,
    hapticsEnabled: Boolean,
    announceToScreenReader: () -> Unit,
    speak: () -> Boolean,
    vibrate: () -> Boolean,
): GuidanceDeliveryResult {
    if (speechEnabled && screenReaderActive) {
        announceToScreenReader()
        val vibrated = hapticsEnabled && vibrate()
        return GuidanceDeliveryResult(
            listOfNotNull(PromptDeliveryChannels.SCREEN_READER, PromptDeliveryChannels.HAPTICS.takeIf { vibrated })
        )
    }
    if (speechEnabled && speechEngineReady) {
        if (!speak()) return GuidanceDeliveryResult.NOT_DELIVERED
        val vibrated = hapticsEnabled && vibrate()
        return GuidanceDeliveryResult(
            listOfNotNull(PromptDeliveryChannels.SPEECH, PromptDeliveryChannels.HAPTICS.takeIf { vibrated })
        )
    }
    if (speechEnabled) speak() // not ready: refused, but asks the engine to start
    if (hapticsEnabled) {
        return if (vibrate()) {
            GuidanceDeliveryResult(listOf(PromptDeliveryChannels.HAPTICS))
        } else {
            GuidanceDeliveryResult.NOT_DELIVERED
        }
    }
    return if (speechEnabled) {
        GuidanceDeliveryResult.NOT_DELIVERED
    } else {
        GuidanceDeliveryResult(listOf(PromptDeliveryChannels.STATUS_CARD))
    }
}

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
 * Offers the frame's primary Guidance prompt, and keeps the cooldown honest either way.
 *
 * DecideEventsUseCase has already recorded the prompt in the cooldown, provisionally. Only a prompt
 * that reached the user may keep that record, so every path that does not deliver revokes it and the
 * prompt is offered again on a later frame while its condition still holds:
 *
 * 1. A description holds the floor and the prompt is not urgent ([guidanceWaitsForDescription]):
 *    wait. The description is left alone and nothing is delivered.
 * 2. Otherwise the description gives way first ([preemptDescription]) — before the prompt is
 *    spoken, so no clause of it can be left in front of the prompt or behind it.
 * 3. No enabled output channel accepted it (see [deliverGuidanceEvent]: speech outranked or still
 *    starting, the motor refusing, ...): not delivered, revoked as [PromptRevokeReasons.OUTPUT_REFUSED].
 *
 * @param revoke called with the reason, one of [PromptRevokeReasons], so the trace can say why.
 * @return the channels that accepted it; empty when it was not delivered.
 */
internal inline fun offerGuidancePrompt(
    priority: EventPriority,
    descriptionHoldsTheFloor: Boolean,
    preemptDescription: () -> Unit,
    deliver: () -> GuidanceDeliveryResult,
    revoke: (reason: String) -> Unit,
): GuidanceDeliveryResult {
    if (guidanceWaitsForDescription(priority, descriptionHoldsTheFloor)) {
        revoke(PromptRevokeReasons.WAITING_FOR_DESCRIPTION)
        return GuidanceDeliveryResult.NOT_DELIVERED
    }
    preemptDescription()
    val result = deliver()
    if (!result.delivered) {
        revoke(PromptRevokeReasons.OUTPUT_REFUSED)
    }
    return result
}

/** The output settings at the moment a prompt is offered; recorded as context, not as evidence. */
internal data class GuidanceOutputSettings(
    val speechEnabled: Boolean,
    val screenReaderActive: Boolean,
    val hapticsEnabled: Boolean,
)

/**
 * [offerGuidancePrompt] for [event], plus exactly one `prompt_outcome` trace record of what became of
 * it: delivered through which channels, or revoked and why.
 *
 * Labelling false alarms has to be done against what the user received, not against the frame's
 * candidates; this is where that record is made.
 *
 * @param now wall-clock milliseconds, the clock of the frame trace's `pipelineCompletedAt`.
 * @param revoke takes back the provisional cooldown record.
 * @return whether the prompt was delivered.
 */
internal inline fun offerAndTraceGuidancePrompt(
    event: SceneEvent,
    sourceSequenceNumber: Long,
    outputs: GuidanceOutputSettings,
    descriptionHoldsTheFloor: Boolean,
    now: () -> Long,
    preemptDescription: () -> Unit,
    deliver: () -> GuidanceDeliveryResult,
    revoke: () -> Unit,
    record: (PromptOutcomeTrace) -> Unit,
): Boolean {
    var revokeReason: String? = null
    val result = offerGuidancePrompt(
        priority = event.priority,
        descriptionHoldsTheFloor = descriptionHoldsTheFloor,
        preemptDescription = preemptDescription,
        deliver = deliver,
        revoke = { reason ->
            revokeReason = reason
            revoke()
        },
    )
    val at = now()
    record(
        PromptOutcomeTrace(
            eventId = event.id.toString(),
            sourceSequenceNumber = sourceSequenceNumber,
            messageKey = event.messageKey,
            category = event.category.name.lowercase(),
            priority = event.priority.name.lowercase(),
            deliveredAt = at.takeIf { result.delivered },
            revokedAt = at.takeUnless { result.delivered },
            revokeReason = revokeReason,
            deliveredVia = result.channels,
            speechEnabled = outputs.speechEnabled,
            screenReaderActive = outputs.screenReaderActive,
            hapticsEnabled = outputs.hapticsEnabled,
        )
    )
    return result.delivered
}
