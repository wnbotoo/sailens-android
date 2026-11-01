package com.sailens.guidance.delivery

import com.sailens.core.log.LogService
import com.sailens.guidance.model.analysis.VisionFaults
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.trace.PromptOutcomeTrace
import com.sailens.guidance.util.Timestamp

private const val TAG = "GuidanceOutputProtocol"

/**
 * What happens to a frame's decisions on their way to the user: the primary prompt is offered, the
 * vision faults are told, and the cooldown hears what became of the prompt. Pure and driven by frames,
 * so production and the closed-loop evaluation run the same code; everything outside goes through the
 * ports.
 *
 * @param recordOutcome receives exactly one `prompt_outcome` trace record per offered prompt.
 * @param onReachedUser the prompt was heard or felt, so it is what a replay repeats.
 * @param clock the monotonic clock Guidance stamps events with; drives [VisionFaultAlerts].
 * @param wallClock wall-clock milliseconds, the clock of the frame trace's `pipelineCompletedAt`.
 */
class GuidanceOutputProtocol(
    private val speech: GuidanceSpeechPort,
    private val screenReader: GuidanceScreenReaderPort,
    private val motor: GuidanceMotorPort,
    private val description: GuidanceDescriptionPort,
    private val cooldown: GuidanceCooldownPort,
    private val settings: GuidanceSettingsPort,
    private val logger: LogService,
    private val recordOutcome: (PromptOutcomeTrace) -> Unit,
    private val onReachedUser: (SceneEvent) -> Unit,
    private val clock: () -> Long,
    private val wallClock: () -> Long = Timestamp::now,
) {
    /** Everything said and felt about a covered lens, low light and an unrecognised floor; see [VisionFaultAlerts]. */
    private val visionFaultAlerts = VisionFaultAlerts(
        outputs = object : VisionFaultAlerts.Outputs {
            override fun speak(notice: VisionFaultAlerts.Notice): VisionFaultAlerts.Offer = speakVisionNotice(notice)

            // No switch here: VisionFaultAlerts has applied it, and its fallback ignores it. The motor still
            // decides when: an obstacle rhythm of equal or higher priority is not cut short, only waited for.
            override fun vibrate(priority: EventPriority, onPlayed: () -> Unit) =
                motor.vibrateVisionFault(priority, onPlayed)

            override fun cancelVibration() = motor.cancelVisionFaultVibration()
        },
    )

    /**
     * One frame's output.
     *
     * The frame's vision faults first, so a rhythm reserved for a state that has just ended is withdrawn
     * before the prompt's own rhythm asks for the motor; what is said and felt about them only after the
     * prompt, so a higher prompt in the same frame does not cut off a floor notice just begun.
     */
    fun onFrame(events: List<SceneEvent>, sequenceNumber: Long, visionFaults: VisionFaults) {
        val alertsNow = clock()
        visionFaultAlerts.observe(alertsNow, visionFaults, settings.visionFaults())
        onSceneEvents(events, sequenceNumber)
        visionFaultAlerts.act(alertsNow, settings.visionFaults())
    }

    /** A switch changed: an owed vision-fault rhythm whose switch was just turned off is dropped now, not at the next frame. */
    fun settingsChanged() {
        visionFaultAlerts.settingsChanged(settings.visionFaults())
    }

    /** Guidance stopped: no recovery is said for what was still faulty. */
    fun reset() {
        visionFaultAlerts.reset()
    }

    /**
     * Says one [VisionFaultAlerts] notice the way a navigation prompt of its priority is said: it waits
     * for or cuts off a description by the same rule, goes to the screen reader when one is active, and
     * otherwise to own speech, which reports when it was heard out.
     */
    private fun speakVisionNotice(notice: VisionFaultAlerts.Notice): VisionFaultAlerts.Offer {
        val current = settings.output()
        if (!current.speechEnabled) return VisionFaultAlerts.Offer.REFUSED
        if (description.waits(notice.priority)) return VisionFaultAlerts.Offer.REFUSED
        description.preempt(notice.messageKey)
        if (current.screenReaderActive) {
            screenReader.announceNotice(notice.messageKey)
            return VisionFaultAlerts.Offer.PUBLISHED
        }
        val accepted = speech.speakNotice(notice, onSpoken = { visionFaultAlerts.onSpoken(notice.id) })
        return if (accepted) VisionFaultAlerts.Offer.ACCEPTED else VisionFaultAlerts.Offer.REFUSED
    }

    private fun onSceneEvents(events: List<SceneEvent>, sequenceNumber: Long) {
        if (events.isEmpty()) return
        // Guidance 为这一条（且只为这一条）记了冷却，见 DecideEventsUseCase。
        val primaryEvent = events.first()

        val outputs = settings.output()

        logger.debug(TAG, "Scene events generated, ${primaryEvent.messageKey}", mapOf("count" to events.size))

        // 等描述 / 打断描述 / 送达就确认冷却、没送达就撤销冷却，并为这条提示写一条 prompt_outcome（实际
        // 送达的通道或撤回原因）——整条规则在 offerAndTraceGuidancePrompt 里，有单测。
        val delivery = offerAndTraceGuidancePrompt(
            event = primaryEvent,
            sourceSequenceNumber = sequenceNumber,
            outputs = outputs,
            waitsForDescription = description.waits(primaryEvent.priority),
            now = wallClock,
            preemptDescription = { description.preempt(primaryEvent.messageKey) },
            deliver = { deliver(primaryEvent, outputs) },
            confirm = { cooldown.confirm(primaryEvent) },
            revoke = { cooldown.revoke(primaryEvent) },
            record = recordOutcome,
        )
        // Replay history follows what the user actually heard or felt, not the state read before the
        // offer: the first prompt an engine speaks after an outage still had "unavailable" there.
        if (delivery.reachedUser) onReachedUser(primaryEvent)
    }

    /**
     * 把 [event] 送到用户可感知的通道，见 [deliverGuidanceEvent]。
     *
     * @return 真正接收了它的通道；为空表示没送达。
     */
    private fun deliver(event: SceneEvent, outputs: GuidanceOutputSettings): GuidanceDeliveryResult = deliverGuidanceEvent(
        speechEnabled = outputs.speechEnabled,
        screenReaderActive = outputs.screenReaderActive,
        speechEngineReady = { speech.isReady },
        hapticsEnabled = outputs.hapticsEnabled,
        announceToScreenReader = { screenReader.announce(event) },
        speak = { speech.speak(event) },
        vibrate = { motor.vibrate(event) },
    )
}
