package com.sailens.guidance.delivery

import com.sailens.core.log.LogService
import com.sailens.guidance.model.analysis.VisionFaults
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.trace.GuidanceAlertChannels
import com.sailens.guidance.model.trace.GuidanceAlertKinds
import com.sailens.guidance.model.trace.GuidanceAlertResults
import com.sailens.guidance.model.trace.PromptDeliveryChannels
import com.sailens.guidance.model.trace.PromptOutcomeTrace
import com.sailens.guidance.model.trace.PromptRevokeReasons
import com.sailens.guidance.util.Timestamp

private const val TAG = "GuidanceOutputProtocol"

/** What [VisionFaultAlerts] says when a state is over; every other notice tells of a state that holds. */
private val RECOVERY_KEYS: Set<String> = VisionFaultAlerts.Fault.entries.mapTo(HashSet()) { it.recoveryKey }

/**
 * What happens to a frame's decisions on their way to the user: the primary prompt is offered, the
 * vision faults are told, and the cooldown hears what became of the prompt. Pure and driven by frames,
 * so production and the closed-loop evaluation run the same code; everything outside goes through the
 * ports.
 *
 * @param recordOutcome receives exactly one `prompt_outcome` trace record per offered prompt.
 * @param onReachedUser the prompt was heard or felt, so it is what a replay repeats.
 * @param wallClock wall-clock milliseconds, the clock of the frame trace's `pipelineCompletedAt`.
 * @param retryInterrupted a prompt own speech took is confirmed once heard, and one cut off goes to
 *   [GuidanceCooldownPort.reportCut]. Off, it is confirmed when taken, and nobody asks what became of it.
 * @param recordAlert receives, for the trace, every vision-fault sentence offered (once the output answered)
 *   and every rhythm as it is asked for, starts and is withdrawn. Observation only: it changes nothing the
 *   outputs do.
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
    private val wallClock: () -> Long = Timestamp::now,
    private val retryInterrupted: Boolean = false,
    pathComplexOff: Boolean = false,
    /** Fixed only by an evaluation in which two runs have to name a notice alike. */
    noticeIdPrefix: String? = null,
    private val recordAlert: (VisionAlert) -> Unit = {},
) {
    /** The running frame's time, while one runs. */
    private var frameAtMs: Long? = null

    /** Outcomes of a stopped run find nothing to report to: its cooldown was cleared. */
    private var run = 0L

    /** The vision-fault rhythm asked for and not yet played or withdrawn, for the trace; and its request. */
    private var pendingRhythm: EventPriority? = null
    private var rhythmRequest = 0L

    /** Everything said and felt about a covered lens, low light and an unrecognised floor; see [VisionFaultAlerts]. */
    private val visionFaultAlerts = VisionFaultAlerts(
        outputs = object : VisionFaultAlerts.Outputs {
            override fun speak(notice: VisionFaultAlerts.Notice): VisionFaultAlerts.Offer = speakVisionNotice(notice)

            // No switch here: VisionFaultAlerts has applied it, and its fallback ignores it. The motor still
            // decides when: an obstacle rhythm of equal or higher priority is not cut short, only waited for.
            // Traced as requested before the motor is asked: the motor may start it, and say so, before it returns.
            override fun vibrate(priority: EventPriority, onPlayed: () -> Unit) {
                val request = ++rhythmRequest
                pendingRhythm = priority
                recordAlert(rhythm(priority, GuidanceAlertResults.REQUESTED))
                motor.vibrateVisionFault(priority) {
                    if (request == rhythmRequest) pendingRhythm = null
                    recordAlert(rhythm(priority, GuidanceAlertResults.PLAYED))
                    onPlayed()
                }
            }

            override fun cancelVibration() {
                pendingRhythm?.let { recordAlert(rhythm(it, GuidanceAlertResults.CANCELLED)) }
                pendingRhythm = null
                rhythmRequest++
                motor.cancelVisionFaultVibration()
            }
        },
        noticeIdPrefix = noticeIdPrefix ?: VisionFaultAlerts.newNoticeIdPrefix(),
    )

    /** Says that path-complex prompts are off; null when they are not. */
    private val modeNotice = if (pathComplexOff) ModeNotice(::offerModeNotice) else null

    /**
     * One frame's output.
     *
     * The frame's vision faults first, so a rhythm reserved for a state that has just ended is withdrawn
     * before the prompt's own rhythm asks for the motor; what is said and felt about them only after the
     * prompt, so a higher prompt in the same frame does not cut off a floor notice just begun.
     */
    fun onFrame(events: List<SceneEvent>, sequenceNumber: Long, visionFaults: VisionFaults) {
        val frameAt = speech.settleFrame()
        frameAtMs = frameAt
        visionFaultAlerts.observe(frameAt, visionFaults, settings.visionFaults())
        onSceneEvents(events, sequenceNumber)
        visionFaultAlerts.act(frameAt, settings.visionFaults())
        modeNotice?.act(frameAt)
        frameAtMs = null
    }

    /** A switch changed: an owed vision-fault rhythm whose switch was just turned off is dropped now, not at the next frame. */
    fun settingsChanged() {
        visionFaultAlerts.settingsChanged(settings.visionFaults())
    }

    /** Guidance stopped: no recovery is said for what was still faulty. */
    fun reset() {
        run++
        visionFaultAlerts.reset()
        modeNotice?.reset()
    }

    /** Everything that decides what this protocol does next, for an evaluation that follows several possible futures. */
    internal data class State(val alerts: VisionFaultAlerts.State, val mode: ModeNotice.State?, val run: Long)

    internal fun state(): State = State(visionFaultAlerts.state(), modeNotice?.state(), run)

    internal fun restore(state: State) {
        visionFaultAlerts.restore(state.alerts)
        state.mode?.let { modeNotice?.restore(it) }
        run = state.run
        pendingRhythm = null
    }

    /**
     * Says one [VisionFaultAlerts] notice the way a navigation prompt of its priority is said: it waits
     * for or cuts off a description by the same rule, goes to the screen reader when one is active, and
     * otherwise to own speech, which reports when it was heard out.
     */
    private fun speakVisionNotice(notice: VisionFaultAlerts.Notice): VisionFaultAlerts.Offer {
        val current = settings.output()
        if (!current.speechEnabled) return told(notice, GuidanceAlertChannels.NONE, VisionFaultAlerts.Offer.REFUSED)
        if (description.waits(notice.priority)) return told(notice, GuidanceAlertChannels.NONE, VisionFaultAlerts.Offer.REFUSED)
        description.preempt(notice.messageKey)
        if (current.screenReaderActive) {
            screenReader.announceNotice(notice.messageKey)
            return told(notice, GuidanceAlertChannels.SCREEN_READER, VisionFaultAlerts.Offer.PUBLISHED)
        }
        val accepted = speech.speakNotice(notice, frameAtMs, onSpoken = { visionFaultAlerts.onSpoken(notice.id) })
        return told(notice, GuidanceAlertChannels.SPEECH, if (accepted) VisionFaultAlerts.Offer.ACCEPTED else VisionFaultAlerts.Offer.REFUSED)
    }

    /** Traces what became of [notice] and returns it unchanged. */
    private fun told(notice: VisionFaultAlerts.Notice, channel: String, offer: VisionFaultAlerts.Offer): VisionFaultAlerts.Offer {
        recordAlert(
            VisionAlert(
                kind = if (notice.messageKey in RECOVERY_KEYS) GuidanceAlertKinds.FAULT_RECOVERY else GuidanceAlertKinds.FAULT_NOTICE,
                messageKey = notice.messageKey,
                priority = notice.priority,
                channel = channel,
                result = when (offer) {
                    VisionFaultAlerts.Offer.REFUSED -> GuidanceAlertResults.REFUSED
                    VisionFaultAlerts.Offer.ACCEPTED -> GuidanceAlertResults.ACCEPTED
                    VisionFaultAlerts.Offer.PUBLISHED -> GuidanceAlertResults.PUBLISHED
                },
            ),
        )
        return offer
    }

    private fun rhythm(priority: EventPriority, result: String) =
        VisionAlert(GuidanceAlertKinds.FAULT_RHYTHM, messageKey = null, priority = priority, channel = GuidanceAlertChannels.HAPTICS, result = result)

    private fun offerModeNotice(id: String, messageKey: String, atMs: Long, expiresAtMs: Long, onOutcome: (SpeechOutcome) -> Unit): ModeNotice.Offer {
        val current = settings.output()
        if (!current.speechEnabled) return ModeNotice.Offer.REFUSED
        if (description.waits(EventPriority.MEDIUM)) return ModeNotice.Offer.REFUSED
        if (current.screenReaderActive) {
            screenReader.announceNotice(messageKey)
            return ModeNotice.Offer.PUBLISHED
        }
        val thisRun = run
        val taken = speech.speakModeNotice(id, messageKey, atMs, expiresAtMs) { outcome -> if (thisRun == run) onOutcome(outcome) }
        return if (taken) ModeNotice.Offer.ACCEPTED else ModeNotice.Offer.REFUSED
    }

    private fun onSceneEvents(events: List<SceneEvent>, sequenceNumber: Long) {
        if (events.isEmpty()) return
        // Guidance 为这一条（且只为这一条）记了冷却，见 DecideEventsUseCase。
        val primaryEvent = events.first()

        val outputs = settings.output()

        logger.debug(TAG, "Scene events generated, ${primaryEvent.messageKey}", mapOf("count" to events.size))

        // 自带语音接收了、又要等结果时，这里不确认：结果来了再说。
        val prompt = PromptSpeech(primaryEvent)

        // 等描述 / 打断描述 / 送达就确认冷却、没送达就撤销冷却，并为这条提示写一条 prompt_outcome（实际
        // 送达的通道或撤回原因）——整条规则在 offerAndTraceGuidancePrompt 里，有单测。
        val delivery = offerAndTraceGuidancePrompt(
            event = primaryEvent,
            sourceSequenceNumber = sequenceNumber,
            outputs = outputs,
            waitsForDescription = description.waits(primaryEvent.priority),
            now = wallClock,
            preemptDescription = { description.preempt(primaryEvent.messageKey) },
            deliver = { deliver(primaryEvent, outputs, prompt) },
            confirm = { if (!prompt.awaitsOutcome) cooldown.confirm(primaryEvent) },
            revoke = { reason -> if (!prompt.waitsForMotor) cooldown.revoke(primaryEvent, reason) },
            record = { trace -> if (prompt.waitsForMotor) prompt.traceWhenMotorAnswers = trace else recordOutcome(trace) },
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
    private fun deliver(event: SceneEvent, outputs: GuidanceOutputSettings, prompt: PromptSpeech): GuidanceDeliveryResult = deliverGuidanceEvent(
        speechEnabled = outputs.speechEnabled,
        screenReaderActive = outputs.screenReaderActive,
        speechEngineReady = { speech.isReady },
        hapticsEnabled = outputs.hapticsEnabled,
        announceToScreenReader = {
            screenReader.announce(event)
            prompt.taken()
        },
        speak = { prompt.speak() },
        vibrate = { prompt.vibrate() },
    )

    /** One prompt's way through the channels: which took it, and whether a rhythm goes with it. */
    private inner class PromptSpeech(private val event: SceneEvent) {
        /** Own speech took it and its outcome is yet to come. */
        var awaitsOutcome = false
            private set
        private var takenByVoice = false
        private var rhythmGoesWithIt = true

        /** Vibration is its only channel and the motor is not free yet: its receipts and its trace wait. */
        var waitsForMotor = false
            private set
        var traceWhenMotorAnswers: PromptOutcomeTrace? = null

        fun taken() {
            if (!retryInterrupted) return
            takenByVoice = true
            rhythmGoesWithIt = cooldown.accepted(event)
        }

        fun speak(): Boolean {
            val taken = speech.speak(event, frameAtMs, if (retryInterrupted) outcomeHandler(event) else null)
            if (taken) {
                awaitsOutcome = retryInterrupted
                taken()
            }
            return taken
        }

        /** Going with speech or the screen reader it can stay silent; on its own it is the channel, and always plays. */
        fun vibrate(): Boolean {
            if (takenByVoice) return rhythmGoesWithIt && motor.vibrate(event)
            val thisRun = run
            val offer = motor.vibrateAlone(event) { played -> if (thisRun == run) motorAnswered(played) }
            if (offer == MotorOffer.PLAYED && retryInterrupted) cooldown.accepted(event)
            waitsForMotor = offer == MotorOffer.QUEUED
            return offer == MotorOffer.PLAYED
        }

        private fun motorAnswered(played: Boolean) {
            val at = wallClock()
            val trace = traceWhenMotorAnswers
            if (played) {
                if (retryInterrupted) cooldown.accepted(event)
                cooldown.confirm(event)
                onReachedUser(event)
                trace?.let { recordOutcome(it.copy(deliveredAt = at, revokedAt = null, revokeReason = null, deliveredVia = listOf(PromptDeliveryChannels.HAPTICS))) }
            } else {
                cooldown.revoke(event, PromptRevokeReasons.HAPTIC_DROPPED)
                trace?.let { recordOutcome(it.copy(revokedAt = at, revokeReason = PromptRevokeReasons.HAPTIC_DROPPED)) }
            }
        }
    }

    private fun outcomeHandler(event: SceneEvent): (SpeechOutcome) -> Unit {
        val thisRun = run
        return { outcome ->
            if (thisRun == run) {
                when (outcome) {
                    SpeechOutcome.SPOKEN -> cooldown.confirm(event)
                    SpeechOutcome.CUT -> cooldown.reportCut(event)
                    SpeechOutcome.HEARD_AFTER_CUT -> cooldown.reportHeardAfterCut(event)
                }
            }
        }
    }
}
