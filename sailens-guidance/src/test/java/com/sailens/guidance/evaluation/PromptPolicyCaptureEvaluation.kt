package com.sailens.guidance.evaluation

import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.config.PerceptionConfig
import com.sailens.guidance.evaluation.PromptPolicyEvaluation.LifecycleEvidence
import com.sailens.guidance.geometry.CameraClock
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.toCameraFacts
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.semantics.DefaultNavigationSemanticsProvider
import com.sailens.guidance.trace.capture.CaptureModes
import com.sailens.guidance.trace.capture.CaptureSchema
import com.sailens.guidance.trace.capture.CaptureSession
import com.sailens.guidance.trace.capture.CaptureSessionReader
import com.sailens.guidance.trace.capture.RegressionFrameRecord
import com.sailens.guidance.trace.capture.RegressionUtteranceRecord
import com.sailens.guidance.trace.capture.UtteranceRecordEvents
import com.sailens.guidance.trace.regression.GroundContactEvaluation
import com.sailens.guidance.trace.regression.RegressionReplay
import java.util.Locale

/**
 * PR-0's baseline over one model-regression capture: the exact replay measured with
 * [PromptPolicyEvaluation], plus the checks that make the numbers trustworthy -- the replay is exact,
 * the record is complete, a second run with other delivery gives birth to the same tracks (so ids
 * pair across runs), and what the lifecycle can and cannot say.
 */
internal class PromptPolicyCaptureEvaluation(private val phone: PhoneGeometrySettings) {

    class Result(
        val id: String,
        val evaluable: Boolean,
        val complete: Boolean,
        val problems: List<String>,
        /** The report without the speaker model, whose lines depend on the duration table ([report]). */
        val text: String,
        val evaluation: PromptPolicyEvaluation.Result?,
        val speaker: SpeakerInputs?,
    ) {
        fun report(calibration: SpeakerModel.Calibration?, set: D18Sets.Group?): String = when {
            speaker == null -> text
            set == null -> text + "  speaker model: not scored -- the capture is in neither frozen D1.8 set (D18Sets)\n"
            calibration == null -> text
            else -> text + calibration.render("${speaker.label}, ${set.name.lowercase()} set")
        }
    }

    /**
     * One capture's offers and what the device did with each, for the speaker model under a duration
     * table chosen by the caller ([SpeakerDurations]: never this capture's own). [measured] is this
     * capture's `started`-to-`done` of every wording it spoke to the end -- for scoring *other*
     * captures only.
     */
    class SpeakerInputs internal constructor(
        private val offers: List<OfferInput>,
        private val queued: List<SpeakerModel.Queued>,
        private val actual: Map<String, SpeakerModel.Actual>,
        /** Offer id to an exclusion that holds whatever the model predicts (X2). */
        private val excluded: Map<String, String>,
        /** Windows that start an exclusion (X3, X5), each running on until both speakers are idle. */
        private val windows: List<Pair<LongRange, String>>,
        /** When the device's speaker held something: each accepted attempt, submission to its last record. */
        private val deviceBusy: List<LongRange>,
        val measured: List<Pair<Wording, Long>>,
        val label: String,
    ) {
        fun calibrate(durations: SpeakerDurations): SpeakerModel.Calibration {
            val sources = sortedMapOf<String, Int>()
            val inputs = offers.map { o ->
                val (source, duration) = durations.of(o.wording)
                sources.merge(source, 1, Int::plus)
                SpeakerModel.Offer(o.atMs, o.id, o.priority, o.expiresAtMs, duration)
            } + queued
            val predictions = SpeakerModel().run(inputs)
            // An exclusion ends only where neither speaker holds anything: until then what the model
            // predicts can still rest on the excluded stretch -- a notice the model frees 10 ms after
            // the device did still decides the next offer.
            val modelBusy = predictions.mapNotNull { p -> if (p.accepted && p.startAtMs != null && p.endAtMs != null) p.startAtMs..p.endAtMs else null }
            val busy = deviceBusy + modelBusy
            val all = HashMap(excluded)
            for ((window, why) in windows) {
                val end = idleFrom(window.last, busy)
                for (o in offers) if (o.id !in all && o.atMs >= window.first && o.atMs <= end) all[o.id] = why
            }
            return SpeakerModel.Calibration(predictions, actual, sources, all)
        }
    }

    internal class OfferInput(val atMs: Long, val id: String, val priority: Int, val expiresAtMs: Long, val wording: Wording)

    /** Null for a capture that is not a model-regression record. */
    fun evaluate(session: CaptureSession): Result? {
        val manifest = session.manifest
        if (manifest.captureMode != CaptureModes.MODEL_REGRESSION) return null
        val id = manifest.sessionId
        val label = "capture $id (${manifest.group ?: "?"}: ${manifest.sceneLabel ?: "?"})"
        val geometry = GroundContactEvaluation(phone).geometrySource(session, phone)
        if (geometry is GroundContactEvaluation.GeometrySource.NotEvaluable) {
            return Result(id, false, false, emptyList(), "$label: NOT EVALUABLE -- ${geometry.why}\n", null, null)
        }
        geometry as GroundContactEvaluation.GeometrySource.Available
        val block = checkNotNull(manifest.regression)
        val semantics = DefaultNavigationSemanticsProvider().semanticSegmentationSemantics()
        val perception = CaptureSchema.json.decodeFromJsonElement(PerceptionConfig.serializer(), block.perceptionConfig!!)
        val analysis = CaptureSchema.json.decodeFromJsonElement(AnalysisConfig.serializer(), block.analysisConfig!!)
        val lost = block.stats.firstLostSeq
        val expected = session.regressionFrames.count { lost == null || it.seq < lost }
        fun classMap(record: RegressionFrameRecord) = CaptureSessionReader.classMap(session, record)

        val evaluation = PromptPolicyEvaluation(analysis)
        val revoked = session.revokes.map { it.eventId }.toSet()
        val frames = mutableListOf<PromptPolicyEvaluation.Frame>()
        val births = BirthOrderTrackIds()
        // The phone height the exact run's distances used, frame by frame (null: no geometry).
        val heights = sortedMapOf<String, Int>()
        val exact = RegressionReplay(perception, analysis, semantics).replay(
            session.regressionFrames, session.revokes, ::classMap, lost, geometry.exact,
            onFrame = { record, outcome ->
                val used = geometry.exact(record)
                heights.merge(used?.phone?.let { "${fmt(it.heightMeters)} m (${it.source.name.lowercase()})" } ?: "no geometry", 1, Int::plus)
                frames += evaluation.frame(record, outcome, revoked, used?.pitchDownDegrees)
            },
            trackIds = births,
        )
        // A run that delivers differently must give birth to the same tracks, or ids do not pair.
        val otherBirths = BirthOrderTrackIds()
        val other = RegressionReplay(perception, analysis, semantics).replay(
            session.regressionFrames, session.revokes, ::classMap, lost, geometry.exact,
            counterfactual = RegressionReplay.CounterfactualDelivery.ALL_DELIVERED,
            trackIds = otherBirths,
        )
        val birthsPair = BirthOrderTrackIds.sameBirths(births, otherBirths)

        val problems = buildList {
            if (exact.firstMismatch != null) add("$id exact: mismatch ${exact.firstMismatch}")
            if (!exact.exact) add("$id exact: not exact (approximate frames ${exact.approximateFrames.size})")
            if (exact.framesReplayed != expected) add("$id exact: replayed ${exact.framesReplayed} of $expected frames")
            if (other.framesReplayed != expected) add("$id all_delivered: replayed ${other.framesReplayed} of $expected frames")
            if (!birthsPair) add("$id: track births differ between the exact and the all_delivered run; ids do not pair")
            if (expected == 0) add("$id: no frame to evaluate")
        }
        val incomplete = buildList {
            if (!manifest.complete) add("manifest not complete")
            if (lost != null || block.stats.framesLost > 0) add("${block.stats.framesLost} frames lost from seq $lost")
            if (block.stats.framesRecorded != session.regressionFrames.size.toLong()) {
                add("manifest counts ${block.stats.framesRecorded} records, ${session.regressionFrames.size} read")
            }
            session.warnings.forEach { add("reader: $it") }
        }

        val evidence = when {
            !block.utteranceLifecycle -> LifecycleEvidence.NONE
            session.utterances.any { it.event == UtteranceRecordEvents.SCREEN_READER_PUBLISHED } -> LifecycleEvidence.NOT_MODELLABLE
            block.stats.utteranceEventsDropped > 0 || block.stats.utteranceEventsUnreconciled > 0 -> LifecycleEvidence.INCOMPLETE
            else -> LifecycleEvidence.RECORDED
        }
        val credit = if (evidence.completionKnown) CompletionCredit.of(session.utterances) else null
        val cameraRealtime = manifest.camera?.toCameraFacts()?.clock == CameraClock.REALTIME
        val result = evaluation.evaluate(frames, evidence, credit, cameraRealtime, births)
        val speaker = speakerInputs(frames, session, evidence, credit)

        val text = buildString {
            appendLine(label)
            appendLine(
                "  frames ${session.regressionFrames.size}, evaluated $expected" +
                    (if (incomplete.isEmpty()) " (complete)" else " (INCOMPLETE: ${incomplete.joinToString("; ")})") +
                    "; exact replay ${exact.framesReplayed} frames, exact ${exact.exact}",
            )
            // Every run here is the device's own decision stream, so distances use the phone height the
            // device used where geometry is recorded, the evaluation's only where it is resolved here.
            appendLine("  geometry: ${geometry.description}; phone height used for distances, frames: ${heights.entries.joinToString { "${it.key} ${it.value}" }}")
            appendLine("  track births identical in the exact and all_delivered runs: ${if (birthsPair) "yes" else "NO"} (${births.births.size} births)")
            append(PromptPolicyReport.render(listOf(result)))
        }
        return Result(id, true, incomplete.isEmpty() && expected > 0, problems, text, result, speaker)
    }

    /**
     * The speaker model's inputs for this session's offers, and what the device did with each.
     * Without a lifecycle the device's answer is "revoked or not", which also covers waiting for a
     * description and haptics-only delivery: descriptive, and labelled so.
     *
     * Exclusions named in advance (threshold note §3.3) leave an offer unscored: X2 its lifecycle
     * contradicts itself; X3 it was decided in an engine failure window ([engineFailureWindows]; the
     * record does not give the model the engine's readiness); X5 it was decided while a notice or a
     * description held the speaker -- their durations can only come from this capture's own
     * lifecycle, which must not decide a scored offer. X3 and X5 run on until neither the device's
     * speaker nor the model's holds anything ([SpeakerInputs.calibrate]).
     */
    private fun speakerInputs(
        frames: List<PromptPolicyEvaluation.Frame>,
        session: CaptureSession,
        evidence: LifecycleEvidence,
        credit: CompletionCredit?,
    ): SpeakerInputs {
        val offers = mutableListOf<OfferInput>()
        val actual = HashMap<String, SpeakerModel.Actual>()
        val wordingOf = HashMap<String, Wording>()
        for (f in frames) {
            val event = f.decided.firstOrNull() ?: continue
            val id = f.offeredId ?: continue
            // Keyed by wording, not only by message key: the NEAR prefix adds about 0.6 s (D1.7).
            val wording = event.messageKey to event.isNear
            wordingOf[id] = wording
            offers += OfferInput(f.decidedAt, id, event.priority.value, event.expiresAt, wording)
            val critical = event.priority == EventPriority.CRITICAL
            actual[id] = if (credit == null) {
                SpeakerModel.Actual(accepted = f.offeredReceived, critical = critical)
            } else {
                val logical = credit[id]
                SpeakerModel.Actual(
                    accepted = logical?.neverAccepted == false,
                    refusal = logical?.attempts?.firstNotNullOfOrNull { a -> a.records.firstOrNull { it.event == UtteranceRecordEvents.REFUSED }?.reason } ?: if (logical == null) "no_record" else null,
                    startedAtMs = logical?.firstStartedAtNanos?.let { it / 1e6 },
                    doneAtMs = logical?.completedAtNanos?.let { it / 1e6 },
                    critical = critical,
                )
            }
        }
        val queued = mutableListOf<SpeakerModel.Queued>()
        val excluded = HashMap<String, String>()
        val windows = mutableListOf<Pair<LongRange, String>>()
        val deviceBusy = mutableListOf<LongRange>()
        val measured = mutableListOf<Pair<Wording, Long>>()
        if (credit != null && evidence.completionKnown) {
            for (logical in credit.byLogicalId.values) {
                for (a in logical.attempts) {
                    val from = a.submittedAtNanos ?: continue
                    deviceBusy += from / 1_000_000..a.records.maxOf { it.elapsedRealtimeNanos } / 1_000_000
                }
                val wording = wordingOf[logical.logicalId]
                if (wording != null) {
                    if (logical.evidenceConflict) excluded[logical.logicalId] = "x2 evidence conflict"
                    val attempt = logical.attempts.firstOrNull { it.startedAtNanos != null && it.doneAtNanos != null } ?: continue
                    measured += wording to (attempt.doneAtNanos!! - attempt.startedAtNanos!!) / 1_000_000
                    continue
                }
                // What else held the speaker: notices and descriptions, with their recorded durations.
                val attempt = logical.attempts.firstOrNull { it.accepted } ?: continue
                val submitted = attempt.records.first { it.event == UtteranceRecordEvents.SUBMITTED || it.event == UtteranceRecordEvents.REQUEUED }
                val duration = if (attempt.startedAtNanos != null && attempt.doneAtNanos != null) (attempt.doneAtNanos - attempt.startedAtNanos) / 1_000_000 else SpeakerModel.DEFAULT_DURATION_MS
                queued += SpeakerModel.Queued(submitted.elapsedRealtimeNanos / 1_000_000, logical.logicalId, submitted.priority, duration, submitted.owner)
                val end = attempt.records.maxOf { it.elapsedRealtimeNanos } / 1_000_000
                windows += submitted.elapsedRealtimeNanos / 1_000_000..maxOf(end, submitted.elapsedRealtimeNanos / 1_000_000 + duration) to "x5 notice or description speaking"
            }
            engineFailureWindows(session.utterances).forEach { windows += it to "x3 engine failure window" }
            windows.sortBy { if (it.second.startsWith("x3")) 0 else 1 }
        }
        val label = if (evidence.completionKnown) "against the lifecycle" else "against revokes only"
        return SpeakerInputs(offers, queued, actual, excluded, windows, deviceBusy, measured, label)
    }

    private fun fmt(v: Float) = String.format(Locale.ROOT, "%.2f", v)

    internal companion object {
        // sailens-output's `UtteranceReasons` (not a dependency of this module).
        private const val ENGINE_NOT_READY = "engine_not_ready"
        private const val SPEAK_ERROR = "speak_error"
        private const val SPEAK_FAILED = "speak_failed"

        /** The first moment at or after [t] that lies inside none of [busy]. */
        fun idleFrom(t: Long, busy: List<LongRange>): Long {
            var end = t
            while (true) end = busy.filter { it.first <= end && it.last > end }.maxOfOrNull { it.last } ?: return end
        }

        /**
         * X3: from a generation's first sign of failure (`error`, `unknown_terminal`, a refusal for
         * "engine not ready" or `speak_error`, a queue forgotten for `speak_failed`) to the first
         * `started` of a later generation, or the end.
         */
        fun engineFailureWindows(utterances: List<RegressionUtteranceRecord>): List<LongRange> {
            val records = utterances.sortedBy { it.elapsedRealtimeNanos }
            val windows = mutableListOf<LongRange>()
            var latest = 0L
            var failedFrom: Pair<Long, Long>? = null
            for (r in records) {
                r.engineGeneration?.let { latest = maxOf(latest, it) }
                val at = r.elapsedRealtimeNanos / 1_000_000
                val open = failedFrom
                if (open != null) {
                    if (r.event == UtteranceRecordEvents.STARTED && (r.engineGeneration ?: 0) > open.second) {
                        windows += open.first until at
                        failedFrom = null
                    }
                    continue
                }
                val failing = r.event == UtteranceRecordEvents.ERROR || r.event == UtteranceRecordEvents.UNKNOWN_TERMINAL ||
                    (r.event == UtteranceRecordEvents.REFUSED && (r.reason == ENGINE_NOT_READY || r.reason == SPEAK_ERROR)) ||
                    (r.event == UtteranceRecordEvents.CANCELLED && r.reason == SPEAK_FAILED)
                if (failing) failedFrom = at to (r.engineGeneration ?: latest)
            }
            failedFrom?.let { windows += it.first..Long.MAX_VALUE }
            return windows
        }
    }
}
