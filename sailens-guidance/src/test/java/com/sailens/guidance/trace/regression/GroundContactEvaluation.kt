package com.sailens.guidance.trace.regression

import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.config.PerceptionConfig
import com.sailens.guidance.depth.ContactValidity
import com.sailens.guidance.geometry.CameraClock
import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.CameraGeometryResolver
import com.sailens.guidance.geometry.FrameFacts
import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.SensorSample
import com.sailens.guidance.geometry.Vec3
import com.sailens.guidance.geometry.toCameraFacts
import com.sailens.guidance.model.common.DistanceLevel
import com.sailens.guidance.model.perception.DetectedObstacle
import com.sailens.guidance.semantics.DefaultNavigationSemanticsProvider
import com.sailens.guidance.trace.capture.CaptureModes
import com.sailens.guidance.trace.capture.CaptureSchema
import com.sailens.guidance.trace.capture.CaptureSensor
import com.sailens.guidance.trace.capture.CaptureSession
import com.sailens.guidance.trace.capture.CaptureSessionReader
import com.sailens.guidance.trace.capture.EventRecord
import com.sailens.guidance.trace.capture.RegressionFrameRecord
import com.sailens.guidance.trace.capture.orThrow
import com.sailens.guidance.trace.regression.RegressionReplay.CounterfactualDelivery
import java.util.Locale
import kotlin.math.abs

/**
 * M3a's evaluation of one model-regression capture: local captures through
 * [GroundContactCaptureEvaluationTest], synthetic ones in CI through `RegressionReplayRoundTripTest`.
 *
 * Each capture is replayed through the real decision path, three ways:
 * - **exact**: the recorded config and the recorded geometry, untouched. It must reproduce the
 *   device over every frame the record allows.
 * - **baseline**: M3a explicitly off, the recorded thresholds kept, the evaluation's phone height;
 *   once per [CounterfactualDelivery] assumption. When the recording itself had M3a off, the
 *   `UNCHANGED_AS_RECORDED` baseline must equal the device (checked).
 * - **treatment**: the same with M3a explicitly on.
 *
 * M3a's effect is treatment against baseline *under the same assumption*. A prompt counts as heard
 * only if the device made the identical offer and delivered it; any other prompt's delivery is
 * unknown and counted apart. A prompt's change is judged on what is spoken -- key, priority, the
 * NEAR prefix, the direction suffix -- not on event metadata (no event string has a placeholder;
 * `SceneEventStringsTest` in the shell checks that).
 *
 * Geometry: a capture's recorded geometry (schema 1.4), with only the phone height replaced outside
 * the exact run; for an older capture, resolved from its sensor log -- possible only on the camera's
 * realtime clock, since the regression record keeps no receipt time. A capture with neither is
 * reported as not evaluable.
 *
 * Completeness: a capture counts as complete only if its manifest says so, it lost no frames, the
 * reader warned of nothing, and the records read match the manifest's count. Anything else is
 * evaluated as far as it goes and labelled; the caller excludes it from a pass.
 */
internal class GroundContactEvaluation(private val phone: PhoneGeometrySettings) {

    /** Whether M3a on decided anything differently from M3a off, under one assumption. */
    data class Change(val decisionsDiffer: Boolean)

    class Result(
        val id: String,
        val evaluable: Boolean,
        val complete: Boolean,
        val recordedOn: Boolean,
        /** Failures of the replay itself: coverage, mismatches, an off baseline that is not the device. */
        val problems: List<String>,
        val text: String,
        val changes: Map<CounterfactualDelivery, Change>,
    )

    /** Null for a capture that is not a model-regression record. */
    fun evaluate(session: CaptureSession): Result? {
        val manifest = session.manifest
        if (manifest.captureMode != CaptureModes.MODEL_REGRESSION) return null
        val problems = mutableListOf<String>()
        val id = manifest.sessionId
        val geometry = geometrySource(session, phone)
        if (geometry is GeometrySource.NotEvaluable) {
            return Result(
                id, evaluable = false, complete = false, recordedOn = false, problems = emptyList(),
                text = "capture $id (${manifest.sceneLabel ?: "?"}): NOT EVALUABLE -- ${geometry.why}\n", changes = emptyMap(),
            )
        }
        geometry as GeometrySource.Available
        val block = checkNotNull(manifest.regression)
        val semantics = DefaultNavigationSemanticsProvider().semanticSegmentationSemantics()
        val recordedConfig = CaptureSchema.json.decodeFromJsonElement(PerceptionConfig.serializer(), block.perceptionConfig!!)
        val analysis = CaptureSchema.json.decodeFromJsonElement(AnalysisConfig.serializer(), block.analysisConfig!!)
        fun classMap(record: RegressionFrameRecord) = CaptureSessionReader.classMap(session, record)
        val lost = block.stats.firstLostSeq
        val expected = session.regressionFrames.count { lost == null || it.seq < lost }
        val incomplete = buildList {
            if (!manifest.complete) add("manifest not complete")
            if (lost != null || block.stats.framesLost > 0) add("${block.stats.framesLost} frames lost from seq $lost")
            if (block.stats.framesRecorded != session.regressionFrames.size.toLong()) {
                add("manifest counts ${block.stats.framesRecorded} records, ${session.regressionFrames.size} read")
            }
            session.warnings.forEach { add("reader: $it") }
        }

        val exact = Run()
        val exactReport = RegressionReplay(recordedConfig, analysis, semantics).replay(
            session.regressionFrames, session.revokes, ::classMap, lost, geometry.exact, exact::record,
        )
        val offConfig = recordedConfig.copy(groundContact = recordedConfig.groundContact.copy(enabled = false))
        val onConfig = recordedConfig.copy(groundContact = recordedConfig.groundContact.copy(enabled = true))
        fun counterfactual(config: PerceptionConfig, delivery: CounterfactualDelivery): Pair<Run, RegressionReplay.Report> {
            val run = Run()
            return run to RegressionReplay(config, analysis, semantics).replay(
                session.regressionFrames, session.revokes, ::classMap, lost, geometry.evaluation, run::record, counterfactual = delivery,
            )
        }
        val base = CounterfactualDelivery.entries.associateWith { counterfactual(offConfig, it) }
        val on = CounterfactualDelivery.entries.associateWith { counterfactual(onConfig, it) }

        // Coverage: every run must have gone as far as the record allows, and that must be something.
        fun check(label: String, report: RegressionReplay.Report) {
            if (report.firstMismatch != null) problems += "$id $label: mismatch ${report.firstMismatch}"
            if (report.framesReplayed != expected) problems += "$id $label: replayed ${report.framesReplayed} of $expected frames"
        }
        check("exact", exactReport)
        if (!exactReport.exact) problems += "$id exact: not exact (approximate frames ${exactReport.approximateFrames.size})"
        base.forEach { (delivery, result) -> check("off/${delivery.name.lowercase()}", result.second) }
        on.forEach { (delivery, result) -> check("on/${delivery.name.lowercase()}", result.second) }
        // Recorded with M3a off, the baseline taking each revoke where the event is unchanged is the
        // device itself. (Recorded with it on, the device is no baseline: nothing to compare.)
        if (!recordedConfig.groundContact.enabled) {
            val asRecorded = base.getValue(CounterfactualDelivery.UNCHANGED_AS_RECORDED).first
            if (asRecorded.decisions() != exact.decisions()) problems += "$id: the off baseline differs from the device"
        }
        if (expected == 0) problems += "$id: no frame to evaluate"

        val text = report(session, geometry, recordedConfig.groundContact.enabled, expected, incomplete, exactReport, base, on)
        val changes = CounterfactualDelivery.entries.associateWith { delivery ->
            Change(decisionsDiffer = base.getValue(delivery).first.decisions() != on.getValue(delivery).first.decisions())
        }
        return Result(
            id, evaluable = true, complete = incomplete.isEmpty() && expected > 0, recordedOn = recordedConfig.groundContact.enabled,
            problems = problems, text = text, changes = changes,
        )
    }

    private sealed interface GeometrySource {
        /** [exact]: as the device had it; [evaluation]: the same with the evaluation's phone settings. */
        class Available(
            val description: String,
            val exact: (RegressionFrameRecord) -> CameraGeometry?,
            val evaluation: (RegressionFrameRecord) -> CameraGeometry?,
        ) : GeometrySource

        class NotEvaluable(val why: String) : GeometrySource
    }

    private fun geometrySource(session: CaptureSession, phone: PhoneGeometrySettings): GeometrySource {
        val frames = session.regressionFrames
        if (frames.isNotEmpty() && frames.all { it.geometry != null }) {
            return GeometrySource.Available(
                "recorded (schema 1.4); phone height replaced outside the exact run",
                exact = { record -> record.geometry?.toGeometry() },
                evaluation = { record -> record.geometry?.toGeometry()?.copy(phone = phone) },
            )
        }
        val camera = session.manifest.camera?.toCameraFacts()
            ?: return GeometrySource.NotEvaluable("no recorded geometry and no camera record")
        if (camera.clock != CameraClock.REALTIME) {
            // The regression record keeps no receipt time, which is what the device used on such a camera.
            return GeometrySource.NotEvaluable("no recorded geometry, and the camera clock is ${camera.clock}, not realtime")
        }
        val transform = session.frames.firstOrNull { it.sensorToBufferTransform != null }?.sensorToBufferTransform
        val gravity = session.sensors.filter { it.sensor == CaptureSensor.GRAVITY }.sortedBy { it.timestampNanos }
        if (gravity.isEmpty()) return GeometrySource.NotEvaluable("no recorded geometry and no gravity samples")
        val times = LongArray(gravity.size) { gravity[it].timestampNanos }
        fun nearest(t: Long): SensorSample {
            val i = times.binarySearch(t).let { if (it >= 0) it else -it - 1 }
            val best = listOfNotNull(gravity.getOrNull(i - 1), gravity.getOrNull(i)).minBy { abs(it.timestampNanos - t) }
            return SensorSample(best.timestampNanos, Vec3(best.values[0], best.values[1], best.values[2]))
        }
        val resolver = CameraGeometryResolver()
        val resolve = { record: RegressionFrameRecord ->
            val facts = FrameFacts(record.width, record.height, record.rotationDegrees, record.sensorTimestampNanos, 0, transform)
            resolver.resolve(camera, facts, ::nearest, phone)
        }
        // Without recorded geometry the capture predates M3a, so the phone settings decided nothing on
        // the device: the exact run may use the evaluation's.
        return GeometrySource.Available("resolved from the sensor log (camera realtime clock)", exact = resolve, evaluation = resolve)
    }

    /** What one replay saw: per frame, the tracked obstacles and the decided events. */
    private class Run {
        val frames = mutableListOf<Pair<RegressionFrameRecord, RegressionReplay.FrameOutcome>>()
        fun record(record: RegressionFrameRecord, outcome: RegressionReplay.FrameOutcome) {
            frames += record to outcome
        }

        fun decisions(): List<List<EventRecord>> = frames.map { it.second.events.map { e -> e.toRecord().withoutId() } }

        /** Obstacle prompts: the offered (first) event of each frame, when it is about an obstacle. */
        fun obstaclePrompts(): List<Prompt> = frames.mapNotNull { (record, outcome) ->
            outcome.events.firstOrNull()?.takeIf { it.messageKey.startsWith(OBSTACLE_PREFIX) }?.let {
                Prompt(record, it.toRecord().withoutId(), outcome.obstacles)
            }
        }
    }

    /**
     * What a prompt makes the user hear and how it competes for the speaker: the message, the NEAR
     * prefix, the direction suffix, and the priority. Event metadata such as `messageParams` is not
     * spoken.
     */
    private data class Spoken(val messageKey: String, val priority: String, val near: Boolean, val direction: String?)

    /** An offered obstacle prompt, [event] without its id, and the device's own offer on that frame. */
    private class Prompt(val record: RegressionFrameRecord, val event: EventRecord, val obstacles: List<DetectedObstacle>) {
        val seq: Long get() = record.seq
        val deviceOffer: EventRecord? = record.events.firstOrNull()
        val spoken = Spoken(event.messageKey, event.priority, event.distance == DistanceLevel.NEAR.name, event.directionHint)

        /** The obstacle behind the prompt: the nearest tracked one in the zones it speaks of. */
        val obstacle: DetectedObstacle? = obstacles
            .filter { it.zone.name in event.relatedZones }
            .minWithOrNull(compareBy<DetectedObstacle> { it.distance.value }.thenByDescending { it.boundingBox.maxY })
    }

    private fun report(
        session: CaptureSession,
        geometry: GeometrySource.Available,
        recordedOn: Boolean,
        expected: Int,
        incomplete: List<String>,
        exactReport: RegressionReplay.Report,
        base: Map<CounterfactualDelivery, Pair<Run, RegressionReplay.Report>>,
        on: Map<CounterfactualDelivery, Pair<Run, RegressionReplay.Report>>,
    ): String = buildString {
        val m = session.manifest
        appendLine("capture ${m.sessionId} (${m.group ?: "?"}: ${m.sceneLabel ?: "?"})")
        appendLine(
            "  frames recorded ${session.regressionFrames.size}, evaluated $expected" +
                (if (incomplete.isEmpty()) " (complete)" else " (INCOMPLETE, excluded from the pass: ${incomplete.joinToString("; ")})") +
                "; exact replay ${exactReport.framesReplayed} frames, exact ${exactReport.exact}",
        )
        appendLine("  recorded with M3a ${if (recordedOn) "ON" else "off"}; geometry: ${geometry.description}")

        // Shadow statistics from the off baseline that takes the device's revokes.
        val baseline = base.getValue(CounterfactualDelivery.UNCHANGED_AS_RECORDED).first
        val geometries = baseline.frames.mapNotNull { geometry.evaluation(it.first) }
        val quality = geometries.groupingBy { it.quality.javaClass.simpleName.lowercase() }.eachCount()
        val pitches = geometries.mapNotNull { it.pitchDownDegrees }.sorted()
        appendLine(
            "  geometry quality ${quality.entries.joinToString { "${it.key} ${it.value}" }}; pitch down p10/median/p90 " +
                (if (pitches.isEmpty()) "-" else listOf(0.1, 0.5, 0.9).joinToString("/") { fmt(pitches[(it * (pitches.size - 1)).toInt()]) }),
        )
        val tracked = baseline.frames.flatMap { it.second.obstacles }
        val validity = tracked.groupingBy { it.distanceEstimate?.contact?.validity ?: ContactValidity.NO_GEOMETRY }.eachCount()
        appendLine(
            "  tracked obstacle-frames ${tracked.size}; contact validity: " +
                ContactValidity.entries.filter { validity[it] != null }.joinToString { "${it.name.lowercase()} ${pct(validity.getValue(it), tracked.size)}" },
        )
        val wouldRaise = tracked.count { o ->
            val e = o.distanceEstimate ?: return@count false
            e.contact.validity == ContactValidity.VALID && e.contactLevel != null && e.contactLevel.value < e.today.value
        }
        appendLine("  valid contacts nearer than today: $wouldRaise (${pct(wouldRaise, tracked.size)})")

        // Heard: the device made this very offer and did not revoke it (speech busy).
        val revoked = session.revokes.map { it.eventId }.toSet()
        fun heardOnDevice(p: Prompt) = p.deviceOffer != null && p.deviceOffer.id !in revoked && p.event == p.deviceOffer.withoutId()
        fun sameAsDevice(p: Prompt) = p.deviceOffer != null && p.event == p.deviceOffer.withoutId()
        val basePrompts = baseline.obstaclePrompts()
        val heardBase = basePrompts.filter(::heardOnDevice)
        appendLine(
            "  obstacle prompts, M3a off (taking the device's revokes): offered ${basePrompts.size}, heard ${heardBase.size}, " +
                "not made by the device ${basePrompts.count { !sameAsDevice(it) }} (delivery unknown)",
        )
        for (p in basePrompts) {
            val e = p.obstacle?.distanceEstimate
            appendLine(
                "    seq ${p.seq} ${p.event.messageKey} ${p.event.priority} distance=${p.event.distance ?: "-"}" +
                    (when { heardOnDevice(p) -> ""; sameAsDevice(p) -> " [revoked]"; else -> " [not on device]" }) +
                    " obstacle=${p.obstacle?.category ?: "?"} y=${p.obstacle?.boundingBox?.maxY?.let(::fmt) ?: "-"}" +
                    " contact=${e?.contact?.validity?.name?.lowercase() ?: "none"} point=${fmt(e?.contact?.pointM)} lower=${fmt(e?.contact?.lowerM)}",
            )
        }
        val points = heardBase.mapNotNull { p -> p.obstacle?.distanceEstimate?.contact?.takeIf { it.validity == ContactValidity.VALID }?.pointM }.sorted()
        if (points.isNotEmpty()) {
            appendLine("  heard prompt distance (valid contacts, point estimate): n ${points.size}, min ${fmt(points.first())}, median ${fmt(points[points.size / 2])}, max ${fmt(points.last())}")
        }

        for (delivery in CounterfactualDelivery.entries) {
            val off = base.getValue(delivery).first
            val run = on.getValue(delivery).first
            val offPrompts = off.obstaclePrompts()
            val prompts = run.obstaclePrompts()
            val bySeq = prompts.associateBy { it.seq }
            // Which off prompts count as heard: under unchanged_as_recorded, those the device made and
            // delivered; assuming all delivered, all of them.
            val heard = when (delivery) {
                CounterfactualDelivery.UNCHANGED_AS_RECORDED -> offPrompts.filter(::heardOnDevice)
                CounterfactualDelivery.ALL_DELIVERED -> offPrompts
            }
            appendLine(
                "  assuming ${delivery.name.lowercase()}: obstacle prompts offered, M3a off ${offPrompts.size} -> on ${prompts.size}; " +
                    "on-run prompts the device never made: ${prompts.count { !sameAsDevice(it) }} (delivery unknown)",
            )
            fun fate(p: Prompt): String {
                val now = bySeq[p.seq] ?: return "gone"
                return when {
                    now.spoken == p.spoken -> "kept"
                    now.spoken.messageKey == p.spoken.messageKey -> "reworded"
                    else -> "replaced"
                }
            }
            val fates = heard.groupBy(::fate)
            val offSeqs = offPrompts.map { it.seq }.toSet()
            appendLine(
                "    of ${heard.size} heard with M3a off: kept ${fates["kept"].orEmpty().size}, reworded ${fates["reworded"].orEmpty().size}, " +
                    "replaced ${fates["replaced"].orEmpty().size}, gone ${fates["gone"].orEmpty().size}; " +
                    "new where M3a off offered none: ${prompts.count { it.seq !in offSeqs }}",
            )
            for (fate in listOf("reworded", "replaced", "gone")) {
                fates[fate].orEmpty().forEach { p ->
                    val now = bySeq[p.seq]?.spoken
                    appendLine("      $fate seq ${p.seq} ${p.spoken}" + (if (now == null) "" else " -> $now"))
                }
            }
            appendLine("    all decided events: M3a off ${off.frames.sumOf { it.second.events.size }}, on ${run.frames.sumOf { it.second.events.size }}")
        }
    }

    private fun pct(n: Int, of: Int) = if (of == 0) "-" else String.format(Locale.ROOT, "%.1f%%", 100.0 * n / of)
    private fun fmt(v: Float?) = v?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "-"

    private companion object {
        const val OBSTACLE_PREFIX = "event_obstacle_"
    }
}
