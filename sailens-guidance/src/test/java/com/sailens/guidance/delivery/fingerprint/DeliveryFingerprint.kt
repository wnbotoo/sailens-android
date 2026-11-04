package com.sailens.guidance.delivery.fingerprint

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
import com.sailens.guidance.model.analysis.SceneSnapshot
import com.sailens.guidance.model.analysis.VisionFaults
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.model.trace.PromptOutcomeTrace
import com.sailens.guidance.processor.decision.CooldownManager
import com.sailens.guidance.processor.decision.EventConflictResolver
import com.sailens.guidance.processor.decision.EventGenerator
import com.sailens.guidance.processor.decision.EventMerger
import com.sailens.guidance.processor.decision.SpeechRanks
import com.sailens.guidance.processor.decision.SpeechScale
import com.sailens.guidance.repository.DeviceSensorRepository
import com.sailens.guidance.safety.visionFaults
import com.sailens.guidance.trace.capture.CaptureSchema
import com.sailens.guidance.trace.capture.EventRecord
import com.sailens.guidance.trace.capture.RegressionStats
import com.sailens.guidance.trace.regression.RegressionReplay
import com.sailens.guidance.trace.regression.toRecord
import com.sailens.guidance.trace.regression.withoutId
import com.sailens.guidance.usecase.decision.DecideEventsUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** One frame as the decision step receives it: from a replayed capture, or built by a boundary script. */
internal data class FingerprintFrame(
    val seq: Long,
    val snapshot: SceneSnapshot,
    val visionFaults: VisionFaults,
    val decidedAt: Long,
    val stationary: Boolean,
) {
    companion object {
        fun of(scene: RegressionReplay.SceneFrame) =
            FingerprintFrame(scene.record.seq, scene.snapshot, scene.visionFaults, scene.decidedAt, scene.stationary)
    }
}

/**
 * The outputs a fingerprint delivers through. [V1] is what the calibration captures were recorded with. With
 * [engineOutage], own speech is not ready on the frames the pipeline is told are in the outage.
 */
internal enum class OutputCondition(
    val speechEnabled: Boolean,
    val screenReaderActive: Boolean,
    val hapticsEnabled: Boolean,
    val engineOutage: Boolean,
) {
    /** Speech on, own TTS ready, haptics on. */
    V1(speechEnabled = true, screenReaderActive = false, hapticsEnabled = true, engineOutage = false),

    /** A screen reader is the spoken channel. */
    V2(speechEnabled = true, screenReaderActive = true, hapticsEnabled = true, engineOutage = false),

    /** Speech off, haptics on. */
    V3(speechEnabled = false, screenReaderActive = false, hapticsEnabled = true, engineOutage = false),

    /** As [V1], with the engine not ready for a stretch mid-session. */
    V4(speechEnabled = true, screenReaderActive = false, hapticsEnabled = true, engineOutage = true),

    /** As [V4], with navigation haptics off: fault haptics stay on, as the settings default them. */
    V5(speechEnabled = true, screenReaderActive = false, hapticsEnabled = false, engineOutage = true),
}

/** What a boundary script makes the outside do. [NONE] is the plain behaviour every capture run uses. */
internal interface FingerprintScript {
    /** When a scene description holds the floor, by frame time; a preemption ends the window it is in. */
    val descriptions: List<LongRange> get() = emptyList()

    /** What the motor does with a prompt it is the only channel for, offered at [atMs]. */
    fun motorAlone(atMs: Long): MotorAnswer = MotorAnswer.Played

    /** For a prompt own speech took and then cut off at [cutAtMs]: when the engine says it was heard after all, or null. */
    fun heardAfterCut(cutAtMs: Long): Long? = null

    companion object {
        val NONE: FingerprintScript = object : FingerprintScript {}
    }
}

internal sealed interface MotorAnswer {
    data object Played : MotorAnswer

    data object Refused : MotorAnswer

    /** Waits for the motor; at [atMs] it plays or is dropped. A stop before then drops it with no answer, as the motor does. */
    data class Queued(val atMs: Long, val plays: Boolean) : MotorAnswer
}

/**
 * One decision and delivery path over a sequence of frames, written down as text: the real [DecideEventsUseCase]
 * (generator, resolver, merger, cooldown, wired as the app wires them) and the real [GuidanceOutputProtocol], with
 * deterministic outputs behind its ports. Two builds that write the same lines for the same frames decided the same
 * events, told the cooldown the same things in the same order and made the same calls on the outputs.
 *
 * The outputs:
 * - speech takes a sentence unless it expired, the engine is not ready, or the sentence being spoken stands as high
 *   or higher on [SpeechScale]; a higher one cuts the current one off. A wording lasts [durationMs]. As the speech
 *   engine's settlement does (`UtteranceSettlement.flushIn` and `removed`), the sentence a new one flushes, and the one
 *   a stop ends, is told it was cut on the spot, inside that call; what the engine ends by itself -- a sentence heard
 *   to the end, or heard after it was cut -- is reported at the next [GuidanceSpeechPort.settleFrame], in time order;
 * - the screen reader takes every line; the motor starts at once unless the script says otherwise;
 * - a description holds the floor only where the script says, and a prompt below HIGH waits for it, as the shell's
 *   policy has it;
 * - the clock is the frame's decision time, and nothing random reaches a line: event ids are numbered by first
 *   appearance, track ids come from the replay's birth order, notice ids have a fixed prefix.
 *
 * A port is handed the event itself, and an output reads more of it than its id (the text, the priority a rhythm
 * claims the motor with), so each port line -- and the line for a prompt that reached the user, whose whole event the
 * shell keeps -- names the event by its number when it is the event as decided, and writes the whole record when it
 * is not ([argument]). Speech lines carry the frame time and the expiry they were offered with.
 *
 * Not modelled, so not covered here: the log, a switch changed mid-session (`settingsChanged`), a speech engine that
 * fails while speaking, the motor's reservations and priorities, the screen reader's buffer, and real asynchronous
 * timing. The fingerprint shows equal delivery for fixed inputs under this model; the alert characterization tests
 * and the device timeline cover the rest.
 *
 * Every line starts with the frame's seq. Lines starting with [QUALIFICATION_PREFIX] are not delivery and are left out
 * of a comparison: what Guidance observed about the frame (its qualification) and about the alerts (their trace
 * records), each followed by its frame's seq.
 *
 * @param inOutage whether a frame is in the engine outage of an [OutputCondition.engineOutage] condition.
 */
internal class FingerprintPipeline(
    analysis: AnalysisConfig,
    private val condition: OutputCondition,
    private val inOutage: (seq: Long) -> Boolean = { false },
    private val script: FingerprintScript = FingerprintScript.NONE,
) {
    private val out = mutableListOf<String>()
    val lines: List<String> get() = out

    private val detFirst = analysis.detFirst
    private var now = 0L
    private var seq = 0L
    private val stationary = MutableStateFlow(false)
    private val cooldown = CooldownManager(
        proximityEscalation = detFirst.proximityEscalation,
        retryInterrupted = detFirst.retryInterrupted,
        metricOnlyRepeatMs = detFirst.metricGate?.metricOnlyRepeatMs ?: 6_000L,
    )
    private val decide = DecideEventsUseCase(
        eventGenerator = EventGenerator(analysis),
        conflictResolver = EventConflictResolver(detFirst.keepBlockedWithObstacle),
        eventMerger = EventMerger(),
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

    private val ids = HashMap<UUID, String>()
    private val idsByText = HashMap<String, String>()

    /** What each event was decided as, by id: what a port is expected to be handed. */
    private val decided = HashMap<UUID, String>()

    private fun name(event: SceneEvent): String = ids.getOrPut(event.id) {
        "e${ids.size + 1}".also { idsByText[event.id.toString()] = it }
    }

    /** [event] as a port line names it: its number when it is the event as decided, and its whole record when not. */
    internal fun argument(event: SceneEvent): String {
        val record = record(event)
        return if (decided[event.id] == record) name(event) else "${name(event)} as=$record"
    }

    private val due = Due()
    private val speaker = Speaker()
    private val motor = Motor()
    private val description = Description()

    private val protocol = GuidanceOutputProtocol(
        speech = object : GuidanceSpeechPort {
            override val isReady: Boolean get() = speaker.ready

            override fun settleFrame(): Long {
                due.runUntil(now)
                return now
            }

            override fun speak(event: SceneEvent, atMs: Long?, onOutcome: ((SpeechOutcome) -> Unit)?): Boolean =
                speaker.offer("speak", argument(event), event.messageKey, SpeechScale.of(event), event.expiresAt, atMs, onOutcome, null)

            override fun speakNotice(notice: VisionFaultAlerts.Notice, atMs: Long?, onSpoken: () -> Unit): Boolean = speaker.offer(
                "speak_notice",
                "${notice.id} priority=${notice.priority.name}",
                notice.messageKey,
                SpeechScale.of(notice.priority, SpeechRanks.ofNotice(notice.messageKey, detFirst)),
                notice.expiresAtMs,
                atMs,
                null,
                onSpoken,
            )

            override fun speakModeNotice(id: String, messageKey: String, atMs: Long, expiresAtMs: Long, onOutcome: (SpeechOutcome) -> Unit): Boolean =
                speaker.offer("speak_mode", id, messageKey, SpeechRanks.MODE_NOTICE_SCALE, expiresAtMs, atMs, onOutcome, null)
        },
        screenReader = object : GuidanceScreenReaderPort {
            override fun announce(event: SceneEvent) = line("screen_reader", argument(event))

            override fun announceNotice(messageKey: String) = line("screen_reader_notice", messageKey)
        },
        motor = object : GuidanceMotorPort {
            override fun vibrate(event: SceneEvent): Boolean {
                line("vibrate", argument(event))
                return true
            }

            override fun vibrateAlone(event: SceneEvent, onQueuedResult: (played: Boolean) -> Unit): MotorOffer =
                motor.alone(argument(event), onQueuedResult)

            override fun vibrateVisionFault(priority: EventPriority, onPlayed: () -> Unit) {
                line("vibrate_fault", priority.name)
                onPlayed()
            }

            override fun cancelVisionFaultVibration() = line("cancel_fault_vibration", "")
        },
        description = object : GuidanceDescriptionPort {
            override fun waits(priority: EventPriority): Boolean = description.waits(priority)

            override fun preempt(reason: String) = description.preempt(reason)
        },
        cooldown = object : GuidanceCooldownPort {
            override fun confirm(event: SceneEvent) {
                line("receipt", "confirm ${name(event)}")
                cooldown.confirm(event.id)
            }

            override fun revoke(event: SceneEvent, reason: String) {
                line("receipt", "revoke ${name(event)} $reason")
                cooldown.revoke(event.id)
            }

            override fun reportCut(event: SceneEvent) {
                val receipt = cooldown.reportCut(event.id)
                line("receipt", "cut ${name(event)} ${receipt.result}")
            }

            override fun reportHeardAfterCut(event: SceneEvent) {
                line("receipt", "heard_after_cut ${name(event)}")
                cooldown.reportHeardAfterCut(event.id)
            }

            override fun accepted(event: SceneEvent): Boolean {
                val rhythm = cooldown.promptAccepted(event.id)
                line("receipt", "accepted ${name(event)} rhythm=$rhythm")
                return rhythm
            }
        },
        settings = object : GuidanceSettingsPort {
            override fun output() = GuidanceOutputSettings(
                speechEnabled = condition.speechEnabled,
                screenReaderActive = condition.screenReaderActive,
                hapticsEnabled = condition.hapticsEnabled,
            )

            override fun visionFaults() = VisionFaultAlerts.Settings(speechEnabled = condition.speechEnabled, faultHapticsEnabled = true)
        },
        logger = SilentLog,
        recordOutcome = { trace -> line("outcome", outcome(trace)) },
        onReachedUser = ::onReachedUser,
        wallClock = { now },
        retryInterrupted = detFirst.retryInterrupted,
        pathComplexOff = detFirst.pathComplexOff,
        noticeIdPrefix = NOTICE_ID_PREFIX,
        recordAlert = { alert -> observed("alert", alert.toString()) },
    )

    /**
     * The prompt the user heard or felt. The shell keeps the whole event for "say it again", so the line is by
     * [argument], as a port's is.
     */
    internal fun onReachedUser(event: SceneEvent) = line("reached", argument(event))

    /** The events the last frame decided. */
    var lastDecided: List<SceneEvent> = emptyList()
        private set

    fun onFrame(frame: FingerprintFrame) {
        seq = frame.seq
        now = frame.decidedAt
        stationary.value = frame.stationary
        speaker.ready = !(condition.engineOutage && inOutage(frame.seq))
        val events = decide(frame.snapshot)
        lastDecided = events
        line("decided", events.joinToString(prefix = "[", postfix = "]") { event -> "${name(event)}=${record(event).also { decided[event.id] = it }}" })
        val qualification = decide.qualification(frame.snapshot)
        // The projection is lossless: what the shell is told equals what the generator tested, on every frame.
        check(qualification.visionFaults() == frame.visionFaults) {
            "frame ${frame.seq}: $qualification projects to ${qualification.visionFaults()}, the generator said ${frame.visionFaults}"
        }
        observed("qualification", qualification.toRecord().toString())
        protocol.onFrame(events, frame.seq, frame.visionFaults)
    }

    /** The protocol's and the cooldown's state at one moment of a stop. */
    data class StoppedState(val protocol: GuidanceOutputProtocol.State, val cooldown: CooldownManager.Canonical)

    /** A stop: the state just after the reset, and after everything that arrived later. A stopped run finds them equal. */
    data class Stop(val afterReset: StoppedState, val afterLateCallbacks: StoppedState)

    /**
     * After the last frame: whatever is still due happens in time order and is written down; then Guidance stops as
     * the shell stops it (protocol reset, then speech and motor stopped).
     */
    fun finish(): Stop {
        due.runAll()
        return stop()
    }

    /** Guidance stopped now, as [finish] stops it, with what was still due left to arrive after the reset. */
    fun stop(): Stop {
        line("stop", "")
        protocol.reset()
        val afterReset = StoppedState(protocol.state(), cooldown.canonicalState())
        speaker.stop()
        motor.stop()
        due.runAll()
        return Stop(afterReset, StoppedState(protocol.state(), cooldown.canonicalState()))
    }

    private fun line(kind: String, detail: String) {
        out += if (detail.isEmpty()) "$seq $kind" else "$seq $kind $detail"
    }

    /** What Guidance observed rather than delivered: a line a comparison leaves out. */
    private fun observed(kind: String, detail: String) {
        out += "$QUALIFICATION_PREFIX$seq $kind $detail"
    }

    /**
     * Every field of [event] but its random id: the regression record, and its components, which the record leaves
     * out but which decide a metric-only prompt's speech band and what the cooldown credits.
     */
    private fun record(event: SceneEvent): String {
        val components = event.components.joinToString(prefix = "[", postfix = "]") { c ->
            "${c.zone}/${c.priority}/${c.primaryTrackId}/${c.cooldownKeys.sorted()}/${c.admission}/${c.hazards}"
        }
        return CaptureSchema.json.encodeToString(EventRecord.serializer(), event.toRecord().withoutId()) + " components=$components"
    }

    private fun outcome(trace: PromptOutcomeTrace): String {
        return trace.copy(eventId = idsByText[trace.eventId] ?: trace.eventId).toString()
    }

    /** Callbacks due at a time, run in time order and, at one time, in the order they were scheduled. */
    private inner class Due {
        private inner class Entry(val atMs: Long, val order: Long, val action: () -> Unit)

        private val entries = mutableListOf<Entry>()
        private var scheduled = 0L

        fun schedule(atMs: Long, action: () -> Unit) {
            entries += Entry(atMs, scheduled++, action)
        }

        fun runUntil(t: Long) {
            while (true) {
                val next = entries.filter { it.atMs <= t }.minWithOrNull(compareBy<Entry> { it.atMs }.thenBy { it.order }) ?: return
                entries.remove(next)
                next.action()
            }
        }

        fun runAll() {
            while (entries.isNotEmpty()) {
                val next = entries.minWithOrNull(compareBy<Entry> { it.atMs }.thenBy { it.order })!!
                entries.remove(next)
                now = maxOf(now, next.atMs)
                next.action()
            }
        }
    }

    private inner class Speaker {
        var ready = true

        private inner class Utterance(
            val label: String,
            val scale: Int,
            val endAtMs: Long,
            val onOutcome: ((SpeechOutcome) -> Unit)?,
            val onSpoken: (() -> Unit)?,
        ) {
            var cut = false
        }

        private var current: Utterance? = null

        /**
         * One offer. Its line, [kind] and [what] with what it was offered with and the answer, is written when the call
         * returns, after whatever the call itself reported.
         */
        fun offer(
            kind: String,
            what: String,
            messageKey: String,
            scale: Int,
            expiresAtMs: Long,
            atMs: Long?,
            onOutcome: ((SpeechOutcome) -> Unit)?,
            onSpoken: (() -> Unit)?,
        ): Boolean {
            val at = atMs ?: now
            val playing = current?.takeIf { !it.cut && it.endAtMs > at }
            val refusal = when {
                at > expiresAtMs -> "expired"
                !ready -> "not_ready"
                playing != null && playing.scale >= scale -> "outranked"
                else -> null
            }
            if (refusal == null) {
                val utterance = Utterance("$kind $what", scale, at + durationMs(messageKey), onOutcome, onSpoken)
                // The new sentence is taken first, then the flush tells the one it took off that it was cut.
                current = utterance
                playing?.let { cutOff(it, at) }
                due.schedule(utterance.endAtMs) {
                    if (!utterance.cut) {
                        if (current === utterance) current = null
                        line("spoken", utterance.label)
                        utterance.onSpoken?.invoke()
                        utterance.onOutcome?.invoke(SpeechOutcome.SPOKEN)
                    }
                }
            }
            line(kind, "$what key=$messageKey scale=$scale at=$at expires=$expiresAtMs -> ${refusal ?: "taken"}")
            return refusal == null
        }

        /** Told it was cut on the spot. When the script says so, the engine reports later that it was heard after all. */
        private fun cutOff(utterance: Utterance, atMs: Long) {
            utterance.cut = true
            if (current === utterance) current = null
            line("cut", utterance.label)
            utterance.onOutcome?.invoke(SpeechOutcome.CUT)
            script.heardAfterCut(atMs)?.let { heardAt ->
                due.schedule(heardAt) {
                    line("heard_after_cut", utterance.label)
                    utterance.onOutcome?.invoke(SpeechOutcome.HEARD_AFTER_CUT)
                }
            }
        }

        /**
         * What the engine was still saying is cut now. One that has already ended is not: its end is reported as
         * heard, at the next settlement.
         */
        fun stop() {
            current?.takeIf { !it.cut && it.endAtMs > now }?.let { cutOff(it, now) }
        }
    }

    private inner class Motor {
        private var stopped = false

        fun alone(event: String, onQueuedResult: (played: Boolean) -> Unit): MotorOffer {
            val answer = script.motorAlone(now)
            line("vibrate_alone", "$event -> $answer")
            return when (answer) {
                MotorAnswer.Played -> MotorOffer.PLAYED
                MotorAnswer.Refused -> MotorOffer.REFUSED
                is MotorAnswer.Queued -> {
                    due.schedule(answer.atMs) {
                        if (!stopped) {
                            line("motor_answered", "$event played=${answer.plays}")
                            onQueuedResult(answer.plays)
                        }
                    }
                    MotorOffer.QUEUED
                }
            }
        }

        fun stop() {
            stopped = true
        }
    }

    private inner class Description {
        private val preemptedIn = HashSet<LongRange>()

        private fun holding(): LongRange? = script.descriptions.firstOrNull { now in it && it !in preemptedIn }

        fun waits(priority: EventPriority): Boolean {
            val waits = holding() != null && priority < GUIDANCE_PREEMPTS_DESCRIPTION_AT
            line("description_waits", "${priority.name} -> $waits")
            return waits
        }

        fun preempt(reason: String) {
            line("preempt_description", reason)
            holding()?.let { preemptedIn += it }
        }
    }

    private object SilentLog : LogService {
        override fun debug(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun info(tag: String, message: String, data: Map<String, Any>?) = Unit
        override fun warning(tag: String, message: String, data: Map<String, Any>?, throwable: Throwable?) = Unit
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }

    companion object {
        const val QUALIFICATION_PREFIX = "Q "
        private const val NOTICE_ID_PREFIX = "fingerprint_notice_"

        /** The shell's rule (`GuidanceOutputWiring`): at or above it a prompt cuts a description off. */
        private val GUIDANCE_PREEMPTS_DESCRIPTION_AT = EventPriority.HIGH

        /** How long a wording takes: fixed per message key, so that a longer sentence holds the speaker longer. */
        fun durationMs(messageKey: String): Long = 600L + 40L * messageKey.length
    }
}

/**
 * What keeps a replay from being a fingerprint of the whole capture, or null. A prefix is not a baseline, so this asks
 * the manifest as well as the replay: a capture that lost a frame anywhere (even after the last one kept, which the
 * replay never reaches), a file with other than the frames the manifest counts, a mismatch, an approximate frame, a stop
 * before a lost frame, or fewer frames replayed than read.
 *
 * @param readFrames the frames read from the capture's file.
 */
internal fun replayProblem(report: RegressionReplay.Report, readFrames: Int, stats: RegressionStats): String? = when {
    stats.framesLost > 0 || stats.firstLostSeq != null -> "the capture lost ${stats.framesLost} frame(s), the first at seq ${stats.firstLostSeq}"
    stats.framesRecorded != readFrames.toLong() -> "the manifest counts ${stats.framesRecorded} frames, the file has $readFrames"
    report.firstMismatch != null -> "first mismatch at seq ${report.firstMismatch.seq}"
    report.approximateFrames.isNotEmpty() -> "${report.approximateFrames.size} approximate frames"
    report.stoppedAtLostSeq != null -> "stopped before lost frame ${report.stoppedAtLostSeq}"
    report.framesReplayed != readFrames -> "replayed ${report.framesReplayed} of $readFrames frames"
    else -> null
}

/**
 * Fingerprint files: written to [out], and each compared first with the file of the same name in [baseline] when
 * there is one. A baseline that is [out] itself is refused before anything is written, since writing would replace
 * the baseline with what it is compared with.
 */
internal class FingerprintFiles(private val out: File, private val baseline: File?) {
    init {
        require(baseline == null || !sameDirectory(out, baseline)) {
            "-PfingerprintBaseline is the output directory ${out.path}: copy the baseline elsewhere first"
        }
        out.mkdirs()
    }

    /** @return where [lines] first differ from the baseline's [name], or null when they do not or there is no baseline. */
    fun record(name: String, lines: List<String>): String? {
        val difference = baseline?.let { directory ->
            val expected = File(directory, name)
            if (expected.isFile) FingerprintComparison.firstDifference(expected.readLines(), lines) else "no baseline file"
        }
        File(out, name).writeText(lines.joinToString("\n", postfix = "\n"))
        return difference
    }

    private companion object {
        fun sameDirectory(a: File, b: File): Boolean =
            a.canonicalFile == b.canonicalFile ||
                (a.exists() && b.exists() && runCatching { Files.isSameFile(a.toPath(), b.toPath()) }.getOrDefault(false))
    }
}

/** Where two fingerprints first differ, by frame and line kind only: a capture's content stays out of the report. */
internal object FingerprintComparison {
    fun firstDifference(expected: List<String>, actual: List<String>): String? {
        val e = expected.filterNot { it.startsWith(FingerprintPipeline.QUALIFICATION_PREFIX) }
        val a = actual.filterNot { it.startsWith(FingerprintPipeline.QUALIFICATION_PREFIX) }
        val index = e.indices.firstOrNull { it >= a.size || e[it] != a[it] }
            ?: return if (a.size > e.size) "extra lines from frame ${frameOf(a[e.size])} (${kindOf(a[e.size])})" else null
        val line = e[index]
        val other = a.getOrNull(index)
        return if (other == null) {
            "missing lines from frame ${frameOf(line)} (${kindOf(line)})"
        } else {
            "line ${index + 1}: frame ${frameOf(line)} ${kindOf(line)} vs frame ${frameOf(other)} ${kindOf(other)}"
        }
    }

    private fun frameOf(line: String) = line.substringBefore(' ')

    private fun kindOf(line: String) = line.substringAfter(' ').substringBefore(' ')
}
