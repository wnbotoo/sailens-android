package com.sailens.guidance.evaluation

import com.sailens.core.log.LogService
import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.delivery.GuidanceCooldownPort
import com.sailens.guidance.delivery.GuidanceDescriptionPort
import com.sailens.guidance.delivery.GuidanceMotorPort
import com.sailens.guidance.delivery.GuidanceOutputProtocol
import com.sailens.guidance.delivery.GuidanceOutputSettings
import com.sailens.guidance.delivery.GuidanceScreenReaderPort
import com.sailens.guidance.delivery.GuidanceSettingsPort
import com.sailens.guidance.delivery.GuidanceSpeechPort
import com.sailens.guidance.delivery.MotorOffer
import com.sailens.guidance.delivery.SpeechOutcome
import com.sailens.guidance.delivery.VisionFaultAlerts
import com.sailens.guidance.model.analysis.VisionFaults
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.processor.decision.CooldownManager
import com.sailens.guidance.processor.decision.EventConflictResolver
import com.sailens.guidance.processor.decision.EventGenerator
import com.sailens.guidance.processor.decision.EventMerger
import com.sailens.guidance.repository.DeviceSensorRepository
import com.sailens.guidance.trace.capture.RegressionUtteranceRecord
import com.sailens.guidance.trace.capture.UtteranceRecordEvents
import com.sailens.guidance.trace.regression.RegressionReplay
import com.sailens.guidance.usecase.decision.DecideEventsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One closed-loop run of the decisions under the nominal speaker model (threshold note A12 and the
 * two arms' nominal runs): the speaker's answers go back through a delivery protocol into a real
 * cooldown, so what is decided next depends on what the model did with what was decided before.
 *
 * Perception does not depend on delivery, so the frames come from outside ([onScene]). Each moment
 * follows one order: results due by then reach the protocol, the decision is made, the protocol runs
 * the frame. On a run with switches off nothing depends on the time a receipt arrives: it confirms or
 * revokes at the offer, before the next decision.
 *
 * @param queued the capture's notices and descriptions, from the frozen inputs.
 */
internal class ClosedLoopRun(
    private val analysis: AnalysisConfig,
    private val durations: SpeakerDurations,
    queued: List<SpeakerModel.Queued>,
    kind: Protocol,
) {
    enum class Protocol {
        /** What the device ran when C and S were recorded: the primary prompt offered as is, for A12. */
        LEGACY,

        /** What is about to ship: the arms run this, [GuidanceOutputProtocol]. */
        PRODUCTION,
    }

    private var now = 0L
    private val stationary = MutableStateFlow(false)
    private val cooldown = CooldownManager()
    private val generator = EventGenerator(analysis)
    private val resolver = EventConflictResolver()
    private val merger = EventMerger()
    private val decide = DecideEventsUseCase(
        eventGenerator = generator,
        conflictResolver = resolver,
        eventMerger = merger,
        cooldownManager = cooldown,
        deviceSensorRepository = object : DeviceSensorRepository {
            override val deviceRotation: StateFlow<Int> = MutableStateFlow(0)
            override val deviceRotationValue: Int = 0
            override val deviceRotationDegree: Int = 0
            override val isStationary: StateFlow<Boolean> = stationary
            override fun startObserving() = Unit
            override fun stopObserving() = Unit
        },
        clock = { now },
    )

    private val speech = ModelSpeech(durations, queued)
    private val delivery: Delivery = when (kind) {
        Protocol.LEGACY -> LegacyOutputProtocol(speech, cooldown)
        Protocol.PRODUCTION -> ProductionDelivery(speech, cooldown) { now }
    }
    private val frames = mutableListOf<PromptPolicyEvaluation.Frame>()

    fun onScene(scene: RegressionReplay.SceneFrame, pitchDownDegrees: Float?) {
        val at = scene.decidedAt
        speech.settle(at)
        now = at
        stationary.value = scene.stationary
        val before = cooldown.snapshot(at, scene.stationary)
        val events = decide(scene.snapshot)
        delivery.onFrame(events, scene.record.seq, scene.visionFaults)
        val generated = generator.generate(scene.snapshot, at)
        val resolved = resolver.resolve(generated)
        val offered = events.firstOrNull()
        frames += PromptPolicyEvaluation.Frame(
            seq = scene.record.seq,
            decidedAt = at,
            obstacles = scene.obstacles,
            groups = generator.announcedObstacleGroups(scene.snapshot.obstacles),
            generated = generated,
            resolved = resolved,
            merged = merger.merge(resolved),
            decided = events,
            cooldown = before,
            offeredId = offered?.id?.toString(),
            offeredReceived = offered != null && speech.accepted(offered.id.toString()),
            pitchDownDegrees = pitchDownDegrees,
        )
    }

    /** The run as the frozen evaluation measures the device: the same rows, from the model's lifecycle. */
    fun finish(births: BirthOrderTrackIds): Result {
        val credit = CompletionCredit.of(speech.lifecycle())
        val result = PromptPolicyEvaluation(analysis).evaluate(
            frames,
            PromptPolicyEvaluation.LifecycleEvidence.RECORDED,
            credit,
            cameraRealtime = false,
            births = births,
        )
        return Result(result, speech.spokenNotices(), speech.durationSources)
    }

    class Result(
        val evaluation: PromptPolicyEvaluation.Result,
        spokenNotices: List<ModelSpeech.SpokenNotice>,
        val durationSources: Map<String, Int>,
    ) {
        /**
         * When each stage was first spoken to the end, in ns. A notice of [VisionFaultAlerts] speaks for
         * the state episode of its wording (the device, before those alerts existed, spoke the event).
         */
        val completedAtNanos: Map<StageKey, Long> = buildMap {
            for (row in evaluation.stages) {
                if (row.completedAtNanos != null && !row.ambiguousOnly) put(StageKey(row.hazard, row.stage), row.completedAtNanos)
            }
            for (notice in spokenNotices) {
                val key = "sensor:${notice.messageKey.removePrefix("event_")}"
                val episode = evaluation.stages.firstOrNull { row ->
                    row.trackless && row.hazardClass == key && notice.offeredAtMs in row.appearedAt..row.endAt + STATE_EPISODE_GAP_MS
                } ?: continue
                val stage = StageKey(episode.hazard, episode.stage)
                val nanos = notice.endAtMs * 1_000_000
                if ((this[stage] ?: Long.MAX_VALUE) > nanos) put(stage, nanos)
            }
        }
    }

    private interface Delivery {
        fun onFrame(events: List<SceneEvent>, sequence: Long, faults: VisionFaults)
    }

    /**
     * The output path of the code C and S were recorded on: the primary prompt is offered whatever it
     * is, a refusal revokes its cooldown record and an acceptance confirms it. A12 has to compare with
     * that device, so it has its own protocol; the arms run the one that ships.
     */
    private class LegacyOutputProtocol(private val speech: ModelSpeech, private val cooldown: CooldownManager) : Delivery {
        override fun onFrame(events: List<SceneEvent>, sequence: Long, faults: VisionFaults) {
            val primary = events.firstOrNull() ?: return
            if (speech.offer(primary)) cooldown.confirm(primary.id) else cooldown.revoke(primary.id)
        }
    }

    /**
     * The production protocol on the model. Speech on, own TTS, engine ready: the settings C and S
     * were recorded with, and a session where they differ is excluded (X1, X3). The motor is a stub that
     * always starts at once; the timing of a rhythm changes no speech in this range.
     */
    private class ProductionDelivery(speech: ModelSpeech, cooldown: CooldownManager, clock: () -> Long) : Delivery {
        private val protocol = GuidanceOutputProtocol(
            speech = object : GuidanceSpeechPort {
                override val isReady: Boolean = true
                override fun settleFrame(): Long = clock()
                override fun speak(event: SceneEvent, atMs: Long?, onOutcome: ((SpeechOutcome) -> Unit)?): Boolean = speech.offer(event)
                override fun speakNotice(notice: VisionFaultAlerts.Notice, atMs: Long?, onSpoken: () -> Unit): Boolean = speech.offerNotice(notice, onSpoken)
                override fun speakModeNotice(id: String, messageKey: String, atMs: Long, expiresAtMs: Long, onOutcome: (SpeechOutcome) -> Unit): Boolean =
                    error("the nominal run has no path-complex switch")
            },
            screenReader = object : GuidanceScreenReaderPort {
                override fun announce(event: SceneEvent) = error("no screen reader in a closed-loop run")
                override fun announceNotice(messageKey: String) = error("no screen reader in a closed-loop run")
            },
            motor = object : GuidanceMotorPort {
                override fun vibrate(event: SceneEvent): Boolean = true
                override fun vibrateAlone(event: SceneEvent, onQueuedResult: (played: Boolean) -> Unit) = MotorOffer.PLAYED
                override fun vibrateVisionFault(priority: EventPriority, onPlayed: () -> Unit) = onPlayed()
                override fun cancelVisionFaultVibration() = Unit

                // Nothing waits for the motor here, and the evaluation holds no prompts.
                override fun dropQueuedNavigation() = Unit
            },
            description = object : GuidanceDescriptionPort {
                override fun waits(priority: EventPriority): Boolean = false
                override fun preempt(reason: String) = Unit
            },
            cooldown = object : GuidanceCooldownPort {
                override fun confirm(event: SceneEvent) = cooldown.confirm(event.id)
                override fun revoke(event: SceneEvent, reason: String) {
                    cooldown.revoke(event.id)
                }
                override fun reportCut(event: SceneEvent) {
                    cooldown.reportCut(event.id)
                }
                override fun reportHeardAfterCut(event: SceneEvent) = Unit
                override fun accepted(event: SceneEvent): Boolean = cooldown.promptAccepted(event.id)
            },
            settings = object : GuidanceSettingsPort {
                override fun output() = GuidanceOutputSettings(speechEnabled = true, screenReaderActive = false, hapticsEnabled = true)
                override fun visionFaults() = VisionFaultAlerts.Settings(speechEnabled = true, faultHapticsEnabled = true)
            },
            logger = SilentLog,
            recordOutcome = {},
            onReachedUser = {},
            wallClock = clock,
        )

        override fun onFrame(events: List<SceneEvent>, sequence: Long, faults: VisionFaults) {
            protocol.onFrame(events, sequence, faults)
        }
    }

    private object SilentLog : LogService {
        override fun debug(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun info(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun warning(tag: String, message: String, data: Map<String, Any>?, throwable: Throwable?) = Unit
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }

    companion object {
        /** [StateEpisodes]' own gap: a notice said just after the last frame of an episode still speaks for it. */
        private const val STATE_EPISODE_GAP_MS = 1_000L
    }
}

/** A stage of a hazard, as both a device run and a model run name it. */
internal data class StageKey(val hazard: String, val stage: Stage)

/**
 * The speech port of a nominal closed-loop run: wordings to durations, the speaker model behind them,
 * and the record of what it did in the form the frozen evaluation reads.
 */
internal class ModelSpeech(private val durations: SpeakerDurations, queued: List<SpeakerModel.Queued>) {
    class SpokenNotice(val messageKey: String, val offeredAtMs: Long, val endAtMs: Long)

    private class Notice(val messageKey: String, val offeredAtMs: Long, val onSpoken: () -> Unit)

    private val speaker = NominalSpeaker(queued)
    private val notices = HashMap<String, Notice>()
    private val accepted = HashSet<String>()
    private val decided = mutableListOf<SpeakerModel.Offer>()

    /** Where each offer's duration came from, counted: a fallback takes twice the ε, and is reported. */
    val durationSources = sortedMapOf<String, Int>()

    private var now = 0L

    /** Results due by [t]; a finished notice reports back. */
    fun settle(t: Long) {
        now = t
        for (p in speaker.settled(t)) {
            if (p.completed) notices[p.id]?.onSpoken?.invoke()
        }
    }

    fun offer(event: SceneEvent): Boolean = offer(
        id = event.id.toString(),
        priority = event.priority.value,
        expiresAtMs = event.expiresAt,
        wording = event.messageKey to event.isNear,
    )

    fun offerNotice(notice: VisionFaultAlerts.Notice, onSpoken: () -> Unit): Boolean {
        notices[notice.id] = Notice(notice.messageKey, now, onSpoken)
        return offer(notice.id, notice.priority.value, notice.expiresAtMs, notice.messageKey to false)
    }

    private fun offer(id: String, priority: Int, expiresAtMs: Long, wording: Wording): Boolean {
        val (source, duration) = durations.of(wording)
        durationSources.merge(source, 1, Int::plus)
        val offer = SpeakerModel.Offer(now, id, priority, expiresAtMs, duration)
        decided += offer
        val answer = speaker.offer(offer)
        if (answer.accepted) accepted += id
        return answer.accepted
    }

    fun accepted(id: String): Boolean = id in accepted

    /** The notices spoken to the end. */
    fun spokenNotices(): List<SpokenNotice> = speaker.finish().mapNotNull { p ->
        val notice = notices[p.id]
        if (notice != null && p.completed) SpokenNotice(notice.messageKey, notice.offeredAtMs, p.endAtMs!!) else null
    }

    /** What the model did, as lifecycle records, so that [CompletionCredit] judges it as it judges the device. */
    fun lifecycle(): List<RegressionUtteranceRecord> = speaker.finish().flatMap { p ->
        fun record(event: String, atMs: Long, reason: String? = null) = RegressionUtteranceRecord(
            event = event,
            attemptId = p.id,
            logicalId = p.id,
            engineGeneration = MODEL_ENGINE,
            elapsedRealtimeNanos = atMs * 1_000_000,
            reason = reason,
        )
        when {
            !p.accepted -> listOf(record(UtteranceRecordEvents.REFUSED, decided.first { it.id == p.id }.atMs, p.reason))
            else -> buildList {
                add(record(UtteranceRecordEvents.SUBMITTED, p.startAtMs!!))
                add(record(UtteranceRecordEvents.STARTED, p.startAtMs))
                add(record(if (p.completed) UtteranceRecordEvents.DONE else UtteranceRecordEvents.SUPERSEDED, p.endAtMs!!))
            }
        }
    }

    private companion object {
        const val MODEL_ENGINE = 1L
    }
}
