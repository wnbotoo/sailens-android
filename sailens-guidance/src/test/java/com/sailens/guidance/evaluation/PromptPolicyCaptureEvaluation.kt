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
        val text: String,
        val evaluation: PromptPolicyEvaluation.Result?,
        val calibration: SpeakerModel.Calibration?,
    )

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
        val exact = RegressionReplay(perception, analysis, semantics).replay(
            session.regressionFrames, session.revokes, ::classMap, lost, geometry.exact,
            onFrame = { record, outcome -> frames += evaluation.frame(record, outcome, revoked, geometry.exact(record)?.pitchDownDegrees) },
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
            block.stats.utteranceEventsDropped > 0 -> LifecycleEvidence.INCOMPLETE
            else -> LifecycleEvidence.RECORDED
        }
        val credit = if (evidence.completionKnown) CompletionCredit.of(session.utterances) else null
        val cameraRealtime = manifest.camera?.toCameraFacts()?.clock == CameraClock.REALTIME
        val result = evaluation.evaluate(frames, evidence, credit, cameraRealtime, births)
        val calibration = calibrate(frames, session, evidence, credit)

        val text = buildString {
            appendLine(label)
            appendLine(
                "  frames ${session.regressionFrames.size}, evaluated $expected" +
                    (if (incomplete.isEmpty()) " (complete)" else " (INCOMPLETE: ${incomplete.joinToString("; ")})") +
                    "; exact replay ${exact.framesReplayed} frames, exact ${exact.exact}",
            )
            appendLine("  geometry: ${geometry.description}; phone height ${fmt(phone.heightMeters)} m (${phone.source.name.lowercase()})")
            appendLine("  track births identical in the exact and all_delivered runs: ${if (birthsPair) "yes" else "NO"} (${births.births.size} births)")
            append(PromptPolicyReport.render(listOf(result)))
            append(calibration.render(if (evidence.completionKnown) "against the lifecycle" else "against revokes only, default durations"))
        }
        return Result(id, true, incomplete.isEmpty() && expected > 0, problems, text, result, calibration)
    }

    /**
     * The speaker model on this session's offers, compared with what the device did. Without a
     * lifecycle the device's answer is "revoked or not", which also covers waiting for a
     * description and haptics-only delivery: descriptive, and labelled so.
     */
    private fun calibrate(
        frames: List<PromptPolicyEvaluation.Frame>,
        session: CaptureSession,
        evidence: LifecycleEvidence,
        credit: CompletionCredit?,
    ): SpeakerModel.Calibration {
        val durations = if (credit == null) {
            emptyMap()
        } else {
            val keyOf = frames.mapNotNull { f -> f.offeredId?.let { it to f.decided.first().messageKey } }.toMap()
            credit.byLogicalId.values.mapNotNull { logical ->
                val key = keyOf[logical.logicalId] ?: return@mapNotNull null
                val attempt = logical.attempts.firstOrNull { it.startedAtNanos != null && it.doneAtNanos != null } ?: return@mapNotNull null
                key to (attempt.doneAtNanos!! - attempt.startedAtNanos!!) / 1_000_000
            }.groupBy({ it.first }, { it.second }).mapValues { (_, d) -> d.sorted()[d.size / 2] }
        }
        val inputs = mutableListOf<SpeakerModel.Input>()
        val actual = HashMap<String, SpeakerModel.Actual>()
        val offered = HashSet<String>()
        for (f in frames) {
            val event = f.decided.firstOrNull() ?: continue
            val id = f.offeredId ?: continue
            offered += id
            inputs += SpeakerModel.Offer(f.decidedAt, id, event.priority.value, event.expiresAt, durations[event.messageKey] ?: SpeakerModel.DEFAULT_DURATION_MS)
            val critical = event.priority == EventPriority.CRITICAL
            actual[id] = if (credit == null) {
                SpeakerModel.Actual(accepted = f.offeredReceived, critical = critical)
            } else {
                val logical = credit[id]
                SpeakerModel.Actual(
                    accepted = logical?.neverAccepted == false,
                    startedAtMs = logical?.firstStartedAtNanos?.let { it / 1e6 },
                    doneAtMs = logical?.completedAtNanos?.let { it / 1e6 },
                    critical = critical,
                )
            }
        }
        if (credit != null && evidence.completionKnown) {
            // What else held the speaker: notices and descriptions, with their measured durations.
            for (logical in credit.byLogicalId.values) {
                if (logical.logicalId in offered) continue
                val attempt = logical.attempts.firstOrNull { it.accepted } ?: continue
                val submitted = attempt.records.first { it.event == UtteranceRecordEvents.SUBMITTED || it.event == UtteranceRecordEvents.REQUEUED }
                val duration = if (attempt.startedAtNanos != null && attempt.doneAtNanos != null) (attempt.doneAtNanos - attempt.startedAtNanos) / 1_000_000 else SpeakerModel.DEFAULT_DURATION_MS
                inputs += SpeakerModel.Queued(submitted.elapsedRealtimeNanos / 1_000_000, logical.logicalId, submitted.priority, duration, submitted.owner)
            }
        }
        return SpeakerModel.Calibration(SpeakerModel().run(inputs), actual)
    }

    private fun fmt(v: Float) = String.format(Locale.ROOT, "%.2f", v)
}
