package com.sailens.guidance.evaluation

import com.sailens.core.log.LogService
import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.config.DetFirstConfig
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
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.processor.decision.CooldownManager
import com.sailens.guidance.processor.decision.EventConflictResolver
import com.sailens.guidance.processor.decision.EventGenerator
import com.sailens.guidance.processor.decision.EventMerger
import com.sailens.guidance.processor.decision.SpeechRanks
import com.sailens.guidance.processor.decision.SpeechScale
import com.sailens.guidance.repository.DeviceSensorRepository
import com.sailens.guidance.trace.regression.RegressionReplay
import com.sailens.guidance.usecase.decision.DecideEventsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Which sentences are measured and how long a wording takes, for both arms: one sentence has one length and one error. */
internal class SentenceDurations(private val durations: SpeakerDurations, private val measuredLabel: String) {
    private val fallback = HashSet<String>()

    fun isFallback(id: String): Boolean = id in fallback

    /** How long [wording] takes, noting for sentence [id] whether that was measured on recordings. */
    fun of(id: String, wording: Wording): Long {
        val (source, ms) = durations.of(wording)
        val supplementary = SupplementaryTtsDurations.ZH_CN_RATE_130[wording.first]
        if (source != measuredLabel) fallback += id
        return if (source == measuredLabel || supplementary == null) ms else supplementary
    }

    /** R10: a wording not measured on recordings is twice as uncertain. */
    fun epsilonMs(id: String): Long = if (id in fallback) 2 * SpeakerModel.END_ERROR_BOUND_MS else SpeakerModel.END_ERROR_BOUND_MS
}

/**
 * What one arm does on a frame: the decision path and the production delivery protocol, with a speaker that
 * splits. One protocol instance serves every branch: its state goes in and out as a value, and the callbacks it
 * registered, which only change its own state, are called with the branch's state restored.
 *
 * The speech port is where a frame can go more than one way, so the frame is run once per combination of the
 * outcomes its offers can have, each from the same start. Speech on, own TTS, engine ready: the settings C and S
 * were recorded with. A session that differs is excluded (X1, X3). The motor is a stub that always starts at once; the
 * timing of a rhythm changes no speech in this range.
 *
 * Receipts for a prompt are not the protocol's callbacks but go by the writing decision's time: a branch's cooldown
 * holds its own id for the same decision.
 *
 * @param alter what a control arm changes about the policy.
 */
internal class ProtocolDriver(
    private val kernel: ClosedLoopSpeakerBranches,
    analysis: AnalysisConfig,
    private val durations: SentenceDurations,
    private val alter: Alteration = Alteration.NONE,
) : PairedBranchRun.FrameDriver {
    interface Alteration {
        fun withholds(primary: SceneEvent): Boolean = false

        /** A sentence of its own, offered before the frame's prompt. */
        fun extra(scene: RegressionReplay.SceneFrame): SpeakerModel.Offer? = null

        companion object {
            val NONE = object : Alteration {}
        }
    }

    private val detFirst: DetFirstConfig = analysis.detFirst
    private val generator = EventGenerator(analysis)
    private val resolver = EventConflictResolver(detFirst.keepBlockedWithObstacle)
    private val merger = EventMerger()
    private val episodes = StateEpisodes()

    override val credits = HashMap<String, List<StageKey>>()

    private sealed interface Sentence {
        class Prompt(val atMs: Long) : Sentence

        class Notice(val onSpoken: () -> Unit) : Sentence

        class Mode(val onOutcome: (SpeechOutcome) -> Unit) : Sentence
    }

    private val sentences = HashMap<String, Sentence>()

    /** One sentence offered, in a run with one branch: for counting, and for saying what became of a stage. */
    class Offered(val id: String, val atMs: Long, val accepted: Boolean, val scale: Int, val event: SceneEvent?, val stationary: Boolean) {
        var endAtMs: Long? = null
        var cutAtMs: Long? = null
        var regivenStages: Set<Any> = emptySet()
    }

    val offered = LinkedHashMap<String, Offered>()

    private var scene: RegressionReplay.SceneFrame? = null
    private var at = 0L
    private var decidedFrame = HashMap<CooldownManager.Canonical, Pair<CooldownManager, List<SceneEvent>>>()

    /** One execution of the frame. */
    private inner class Execution(private val choices: List<Int>, var speaker: ClosedLoopSpeakerBranches.Speaker, var cooldown: CooldownManager) {
        val options = ArrayList<Int>()
        val inputs = ArrayList<SpeakerModel.Input>()
        val outcomes = ArrayList<ClosedLoopSpeakerBranches.Outcome>()

        /** @return whether it was taken. */
        fun offer(offer: SpeakerModel.Offer, event: SceneEvent? = null): Boolean {
            inputs += offer
            val steps = kernel.submit(speaker, offer)
            val pick = choices.getOrElse(options.size) { 0 }
            options += steps.size
            val step = steps[pick]
            speaker = step.speaker
            outcomes += step.outcomes
            for (outcome in step.outcomes) {
                if (outcome is ClosedLoopSpeakerBranches.Cut) {
                    offered[outcome.id]?.cutAtMs = outcome.atMs
                    deliver(outcome, cooldown)
                }
            }
            val taken = step.outcomes.any { it is ClosedLoopSpeakerBranches.Accepted && it.id == offer.id }
            offered[offer.id] = Offered(offer.id, offer.atMs, taken, offer.priority, event, scene?.stationary == true)
            return taken
        }
    }

    private var running: Execution? = null

    private val protocol = GuidanceOutputProtocol(
        speech = object : GuidanceSpeechPort {
            override val isReady: Boolean = true

            override fun settleFrame(): Long = at

            override fun speak(event: SceneEvent, atMs: Long?, onOutcome: ((SpeechOutcome) -> Unit)?): Boolean {
                val id = idOf(event)
                sentences[id] = Sentence.Prompt(at)
                return running!!.offer(SpeakerModel.Offer(at, id, SpeechScale.of(event), event.expiresAt, durations.of(id, event.messageKey to event.isNear)), event)
            }

            override fun speakNotice(notice: VisionFaultAlerts.Notice, atMs: Long?, onSpoken: () -> Unit): Boolean {
                val id = "notice|${notice.messageKey}|$at"
                sentences[id] = Sentence.Notice(onSpoken)
                val key = "sensor:${notice.messageKey.removePrefix("event_")}"
                credits[id] = listOfNotNull(episodes.current(key, at)?.let { StageKey(it.id, Stage.FIRST) })
                val priority = SpeechScale.of(notice.priority, SpeechRanks.ofNotice(notice.messageKey, detFirst))
                return running!!.offer(SpeakerModel.Offer(at, id, priority, notice.expiresAtMs, durations.of(id, notice.messageKey to false)))
            }

            override fun speakModeNotice(id: String, messageKey: String, atMs: Long, expiresAtMs: Long, onOutcome: (SpeechOutcome) -> Unit): Boolean {
                val sentence = "mode|$at"
                sentences[sentence] = Sentence.Mode(onOutcome)
                return running!!.offer(SpeakerModel.Offer(at, sentence, SpeechRanks.MODE_NOTICE_SCALE, expiresAtMs, durations.of(sentence, messageKey to false)))
            }
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
        },
        description = object : GuidanceDescriptionPort {
            override fun waits(priority: EventPriority): Boolean = false

            override fun preempt(reason: String) = Unit
        },
        cooldown = object : GuidanceCooldownPort {
            override fun confirm(event: SceneEvent) = running!!.cooldown.confirm(event.id)

            override fun revoke(event: SceneEvent, reason: String) {
                running!!.cooldown.revoke(event.id)
            }

            override fun reportCut(event: SceneEvent) = Unit

            override fun reportHeardAfterCut(event: SceneEvent) = Unit

            override fun accepted(event: SceneEvent): Boolean {
                offered[idOf(event)]?.regivenStages = running!!.cooldown.stagesAwaitingRegive(event.id)
                return running!!.cooldown.promptAccepted(event.id)
            }
        },
        settings = object : GuidanceSettingsPort {
            override fun output() = GuidanceOutputSettings(speechEnabled = true, screenReaderActive = false, hapticsEnabled = true)

            override fun visionFaults() = VisionFaultAlerts.Settings(speechEnabled = true, faultHapticsEnabled = true)
        },
        logger = SilentLog,
        recordOutcome = {},
        onReachedUser = {},
        wallClock = { at },
        retryInterrupted = detFirst.retryInterrupted,
        pathComplexOff = detFirst.pathComplexOff,
        noticeIdPrefix = "notice_",
    )

    fun startExtra(): Any = protocol.state()

    fun startCooldown(): CooldownManager = CooldownManager(
        proximityEscalation = detFirst.proximityEscalation,
        retryInterrupted = detFirst.retryInterrupted,
        metricOnlyRepeatMs = detFirst.metricGate?.metricOnlyRepeatMs ?: 6_000L,
    )

    /** The same in every branch for the same words on the same frame, and in both arms, so one sentence has one error. */
    private fun idOf(event: SceneEvent): String = "${at}|${event.messageKey}|${event.isNear}|${event.priority.value}|${event.relatedZones}"

    /** A new frame: what can be known without a branch. */
    fun onScene(frame: RegressionReplay.SceneFrame) {
        scene = frame
        at = frame.decidedAt
        decidedFrame = HashMap()
        for (event in generator.generate(frame.snapshot, at)) {
            if (event.category in StateEpisodes.TRACKLESS) episodes.observe(StateEpisodes.keyOf(event), at, frame.record.seq)
        }
    }

    private fun decide(cooldown: CooldownManager, live: CooldownManager.Canonical): Pair<CooldownManager, List<SceneEvent>> = decidedFrame.getOrPut(live) {
        val frame = scene!!
        val fresh = cooldown.copy()
        val sensors = object : DeviceSensorRepository {
            override val deviceRotation: StateFlow<Int> = MutableStateFlow(0)
            override val deviceRotationValue: Int = 0
            override val deviceRotationDegree: Int = 0
            override val isStationary: StateFlow<Boolean> = MutableStateFlow(frame.stationary)
            override fun startObserving() = Unit
            override fun stopObserving() = Unit
        }
        val all = DecideEventsUseCase(generator, resolver, merger, fresh, sensors, clock = { at })(frame.snapshot)
        val primary = all.firstOrNull()
        if (primary != null && alter.withholds(primary)) {
            fresh.revoke(primary.id)
            return@getOrPut fresh to emptyList()
        }
        if (primary != null) {
            credits.getOrPut(idOf(primary)) {
                if (primary.category in StateEpisodes.TRACKLESS) {
                    listOfNotNull(episodes.current(StateEpisodes.keyOf(primary), at)?.let { StageKey(it.id, Stage.FIRST) })
                } else {
                    StageCredit.credits(StageCredit.spoken(primary), generator.announcedObstacleGroups(frame.snapshot.obstacles), generator).filter { it.counts }.map { StageKey(it.trackId.toString(), it.stage) }
                }
            }
        }
        fresh to all
    }

    override fun frame(t: Long, speaker: ClosedLoopSpeakerBranches.Speaker, cooldown: CooldownManager, live: CooldownManager.Canonical, extra: Any?): List<PairedBranchRun.FrameResult> {
        val (afterDecision, events) = decide(cooldown, live)
        val frame = scene!!
        val results = ArrayList<PairedBranchRun.FrameResult>()
        val pending = ArrayDeque<List<Int>>()
        pending.addLast(emptyList())
        while (pending.isNotEmpty()) {
            val choices = pending.removeLast()
            val execution = Execution(choices, speaker, afterDecision.copy())
            running = execution
            protocol.restore(extra as GuidanceOutputProtocol.State)
            alter.extra(frame)?.let { execution.offer(it) }
            protocol.onFrame(events, frame.record.seq, frame.visionFaults)
            running = null
            for (i in choices.size until execution.options.size) {
                for (other in 1 until execution.options[i]) pending.addLast(choices + List(i - choices.size) { 0 } + other)
            }
            results += PairedBranchRun.FrameResult(
                execution.speaker,
                execution.outcomes,
                execution.cooldown,
                protocol.state(),
                execution.inputs,
                "$t: ${execution.inputs.map { (it as SpeakerModel.Offer).id }} ${choices.ifEmpty { null } ?: ""}".takeIf { execution.options.any { it > 1 } },
            )
        }
        return results
    }

    private fun matters(sentence: Sentence?): Boolean = when (sentence) {
        null -> false
        is Sentence.Prompt -> detFirst.retryInterrupted
        is Sentence.Notice, is Sentence.Mode -> true
    }

    override fun waitsFor(outcome: ClosedLoopSpeakerBranches.Outcome): Boolean = matters(sentences[outcome.id])

    override fun onOutcomes(outcomes: List<ClosedLoopSpeakerBranches.Outcome>, cooldown: CooldownManager, extra: Any?): Pair<CooldownManager, Any?>? {
        for (outcome in outcomes) {
            if (outcome is ClosedLoopSpeakerBranches.Spoken) offered[outcome.id]?.endAtMs = outcome.endAtMs
        }
        if (outcomes.none { matters(sentences[it.id]) }) return null
        val own = cooldown.copy()
        protocol.restore(extra as GuidanceOutputProtocol.State)
        for (outcome in outcomes) deliver(outcome, own)
        return own to protocol.state()
    }

    /** What one sentence's end does in the arm. The protocol's state is the branch's. */
    private fun deliver(outcome: ClosedLoopSpeakerBranches.Outcome, cooldown: CooldownManager) {
        val speech = when (outcome) {
            is ClosedLoopSpeakerBranches.Spoken -> SpeechOutcome.SPOKEN
            is ClosedLoopSpeakerBranches.Cut -> SpeechOutcome.CUT
            is ClosedLoopSpeakerBranches.HeardAfterCut -> SpeechOutcome.HEARD_AFTER_CUT
            else -> return
        }
        when (val sentence = sentences[outcome.id]) {
            null -> Unit
            is Sentence.Notice -> if (speech != SpeechOutcome.CUT) sentence.onSpoken()
            is Sentence.Mode -> sentence.onOutcome(speech)
            is Sentence.Prompt -> if (detFirst.retryInterrupted) {
                val write = cooldown.writeAt(sentence.atMs) ?: return
                when (speech) {
                    SpeechOutcome.SPOKEN -> cooldown.confirm(write)
                    SpeechOutcome.CUT -> cooldown.reportCut(write)
                    SpeechOutcome.HEARD_AFTER_CUT -> cooldown.reportHeardAfterCut(write)
                }
            }
        }
    }

    private object SilentLog : LogService {
        override fun debug(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun info(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun warning(tag: String, message: String, data: Map<String, Any>?, throwable: Throwable?) = Unit
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }
}

/** The recorded notices and descriptions on today's speech scale, for an arm with these switches. */
internal fun List<SpeakerModel.Queued>.onSpeechScale(detFirst: DetFirstConfig): List<SpeakerModel.Queued> = map { q ->
    q.copy(
        priority = q.priority?.let { old ->
            when {
                old == Int.MAX_VALUE -> old
                old == EventPriority.HIGH.value -> SpeechScale.of(EventPriority.HIGH, SpeechRanks.ofStalledNotice(detFirst))
                else -> old * SpeechScale.FACTOR
            }
        },
    )
}
