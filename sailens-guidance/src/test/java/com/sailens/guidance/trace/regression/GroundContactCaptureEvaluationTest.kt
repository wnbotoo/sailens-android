package com.sailens.guidance.trace.regression

import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.config.PerceptionConfig
import com.sailens.guidance.depth.ContactValidity
import com.sailens.guidance.depth.GroundContactDistanceConfig
import com.sailens.guidance.geometry.CameraClock
import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.CameraGeometryResolver
import com.sailens.guidance.geometry.FrameFacts
import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.SensorSample
import com.sailens.guidance.geometry.Vec3
import com.sailens.guidance.geometry.toCameraFacts
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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * M3a's offline evaluation on real model-regression captures. Skipped unless a path is given --
 * captures contain faces and places and never enter the repository or CI:
 *
 * ```
 * gradlew :sailens-guidance:testDebugUnitTest --tests "*GroundContactCaptureEvaluationTest*" -PreplayCapture=<dir> [-Pm3aPhoneHeight=1.15]
 * ```
 *
 * Each capture is replayed through the real decision path:
 * - **as recorded** (M3a off). It must stay exact over every frame the record allows; the shadow
 *   estimate it computes gives the ground-contact distance of the obstacle behind every prompt.
 * - **counterfactually**, once per [CounterfactualDelivery] assumption, with M3a off (the baseline)
 *   and on. M3a's effect is on against off *under the same assumption*: comparing a counterfactual
 *   run with the device would mix the delivery assumption into the effect. Under
 *   `UNCHANGED_AS_RECORDED` the baseline is the device itself (checked here). Whether the device
 *   would have delivered a prompt it never made is unknown, so every changed or new prompt's
 *   delivery is reported as unknown.
 *
 * Geometry: a capture's recorded geometry (schema 1.4) with only the phone height replaced; for an
 * older capture, resolved from its sensor log -- possible only on the camera's realtime clock, since
 * the regression record keeps no receipt time. A capture with neither is reported as not evaluable.
 *
 * Reports go to `sailens-guidance/build/replay/<sessionId>.m3a.txt` and a summary to
 * `m3a-summary.txt`; they hold numbers only.
 */
class GroundContactCaptureEvaluationTest {

    @Test
    fun `ground contact evaluation on recorded captures`() {
        val path = System.getProperty("sailens.replay.capture").orEmpty()
        assumeTrue("set -PreplayCapture=<capture dir> to evaluate on captures", path.isNotBlank())
        val out = File(System.getProperty("sailens.replay.out").orEmpty().ifBlank { "build/replay" }).apply { mkdirs() }
        val height = System.getProperty("sailens.m3a.phoneHeight").orEmpty().ifBlank { "1.3" }.toFloat()
        val phone = PhoneGeometrySettings(height, HeightSource.PRESET)
        val captures = File(path).walkTopDown()
            .filter { it.isFile && it.name == CaptureSchema.MANIFEST_FILE }
            .mapNotNull { it.parentFile }
            .sortedBy { it.name }
            .toList()
        assertTrue("no capture under $path", captures.isNotEmpty())

        val summary = StringBuilder("M3a evaluation, phone height preset ${fmt(height)} m\n")
        val problems = mutableListOf<String>()
        var complete = 0
        for (directory in captures) {
            val session = CaptureSessionReader.read(directory).orThrow()
            val manifest = session.manifest
            if (manifest.captureMode != CaptureModes.MODEL_REGRESSION) continue
            val id = manifest.sessionId
            val geometry = geometrySource(session, phone)
            if (geometry is GeometrySource.NotEvaluable) {
                summary.appendLine().appendLine("capture $id (${manifest.sceneLabel ?: "?"}): NOT EVALUABLE -- ${geometry.why}")
                continue
            }
            geometry as GeometrySource.Available
            val block = checkNotNull(manifest.regression)
            val semantics = DefaultNavigationSemanticsProvider().semanticSegmentationSemantics()
            val recordedConfig = CaptureSchema.json.decodeFromJsonElement(PerceptionConfig.serializer(), block.perceptionConfig!!)
            val analysis = CaptureSchema.json.decodeFromJsonElement(AnalysisConfig.serializer(), block.analysisConfig!!)
            fun classMap(record: RegressionFrameRecord) = CaptureSessionReader.classMap(session, record)
            val lost = block.stats.firstLostSeq
            val expected = session.regressionFrames.count { lost == null || it.seq < lost }

            val off = Run()
            val offReport = RegressionReplay(recordedConfig, analysis, semantics).replay(
                session.regressionFrames, session.revokes, ::classMap, lost, geometry.of, off::record,
            )
            val onConfig = recordedConfig.copy(groundContact = GroundContactDistanceConfig(enabled = true))
            fun counterfactual(config: PerceptionConfig, delivery: CounterfactualDelivery): Pair<Run, RegressionReplay.Report> {
                val run = Run()
                return run to RegressionReplay(config, analysis, semantics).replay(
                    session.regressionFrames, session.revokes, ::classMap, lost, geometry.of, run::record, counterfactual = delivery,
                )
            }
            val base = CounterfactualDelivery.entries.associateWith { counterfactual(recordedConfig, it) }
            val on = CounterfactualDelivery.entries.associateWith { counterfactual(onConfig, it) }

            // Coverage: every run must have gone as far as the record allows, and that must be something.
            fun check(label: String, report: RegressionReplay.Report) {
                if (report.firstMismatch != null) problems += "$id $label: mismatch ${report.firstMismatch}"
                if (report.framesReplayed != expected) problems += "$id $label: replayed ${report.framesReplayed} of $expected frames"
            }
            check("off", offReport)
            if (!offReport.exact) problems += "$id off: not exact (approximate frames ${offReport.approximateFrames.size})"
            base.forEach { (delivery, result) -> check("base/${delivery.name.lowercase()}", result.second) }
            on.forEach { (delivery, result) -> check("on/${delivery.name.lowercase()}", result.second) }
            // Taking each revoke where the event is unchanged, the M3a-off baseline is the device.
            val asRecorded = base.getValue(CounterfactualDelivery.UNCHANGED_AS_RECORDED).first
            if (asRecorded.frames.map { it.second.events.map { e -> e.toRecord().withoutId() } } != off.frames.map { it.second.events.map { e -> e.toRecord().withoutId() } }) {
                problems += "$id: the unchanged_as_recorded baseline differs from the device"
            }
            if (expected == 0) problems += "$id: no frame to evaluate"
            if (lost == null && expected > 0) complete++

            val text = report(session, geometry, expected, lost, offReport, off, base.mapValues { it.value.first }, on.mapValues { it.value.first })
            File(out, "$id.m3a.txt").writeText(text)
            summary.appendLine().append(text)
        }
        summary.appendLine().appendLine("complete captures evaluated: $complete; problems: ${problems.size}")
        problems.forEach { summary.appendLine("  $it") }
        File(out, "m3a-summary.txt").writeText(summary.toString())
        println(summary)
        assertTrue("evaluation problems: $problems", problems.isEmpty())
        assertTrue("no complete model_regression capture was evaluated under $path", complete > 0)
    }

    private sealed interface GeometrySource {
        class Available(val description: String, val of: (RegressionFrameRecord) -> CameraGeometry?) : GeometrySource
        class NotEvaluable(val why: String) : GeometrySource
    }

    /** The geometry each frame is replayed with, carrying the evaluation's phone settings. */
    private fun geometrySource(session: CaptureSession, phone: PhoneGeometrySettings): GeometrySource {
        val frames = session.regressionFrames
        if (frames.isNotEmpty() && frames.all { it.geometry != null }) {
            return GeometrySource.Available("recorded (schema 1.4), phone height replaced") { record ->
                record.geometry?.toGeometry()?.copy(phone = phone)
            }
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
        return GeometrySource.Available("resolved from the sensor log (camera realtime clock)") { record ->
            val facts = FrameFacts(record.width, record.height, record.rotationDegrees, record.sensorTimestampNanos, 0, transform)
            resolver.resolve(camera, facts, ::nearest, phone)
        }
    }

    /** What one replay saw: per frame, the tracked obstacles and the decided events. */
    private class Run {
        val frames = mutableListOf<Pair<RegressionFrameRecord, RegressionReplay.FrameOutcome>>()
        fun record(record: RegressionFrameRecord, outcome: RegressionReplay.FrameOutcome) {
            frames += record to outcome
        }

        /** Obstacle prompts: the offered (first) event of each frame, when it is about an obstacle. */
        fun obstaclePrompts(): List<Prompt> = frames.mapNotNull { (record, outcome) ->
            outcome.events.firstOrNull()?.takeIf { it.messageKey.startsWith(OBSTACLE_PREFIX) }?.let {
                Prompt(record, it.toRecord().withoutId(), outcome.obstacles)
            }
        }
    }

    /** An offered obstacle prompt, [event] without its id, and the device's own offer on that frame. */
    private class Prompt(val record: RegressionFrameRecord, val event: EventRecord, val obstacles: List<DetectedObstacle>) {
        val seq: Long get() = record.seq
        val deviceOffer: EventRecord? = record.events.firstOrNull()

        /** The obstacle behind the prompt: the nearest tracked one in the zones it speaks of. */
        val obstacle: DetectedObstacle? = obstacles
            .filter { it.zone.name in event.relatedZones }
            .minWithOrNull(compareBy<DetectedObstacle> { it.distance.value }.thenByDescending { it.boundingBox.maxY })
    }

    private fun report(
        session: CaptureSession,
        geometry: GeometrySource.Available,
        expected: Int,
        lost: Long?,
        offReport: RegressionReplay.Report,
        off: Run,
        base: Map<CounterfactualDelivery, Run>,
        on: Map<CounterfactualDelivery, Run>,
    ): String = buildString {
        val m = session.manifest
        appendLine("capture ${m.sessionId} (${m.group ?: "?"}: ${m.sceneLabel ?: "?"})")
        appendLine(
            "  frames recorded ${session.regressionFrames.size}, evaluated $expected" +
                (if (lost == null) " (complete)" else " (PREFIX ONLY: stops before lost frame $lost)") +
                "; replayed off ${offReport.framesReplayed}, exact ${offReport.exact}",
        )
        appendLine("  geometry: ${geometry.description}")

        val geometries = off.frames.mapNotNull { geometry.of(it.first) }
        val quality = geometries.groupingBy { it.quality.javaClass.simpleName.lowercase() }.eachCount()
        val pitches = geometries.mapNotNull { it.pitchDownDegrees }.sorted()
        appendLine(
            "  geometry quality ${quality.entries.joinToString { "${it.key} ${it.value}" }}; pitch down p10/median/p90 " +
                (if (pitches.isEmpty()) "-" else listOf(0.1, 0.5, 0.9).joinToString("/") { fmt(pitches[(it * (pitches.size - 1)).toInt()]) }),
        )
        val tracked = off.frames.flatMap { it.second.obstacles }
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

        // A prompt the device offered but revoked (speech busy) was never heard.
        val revoked = session.revokes.map { it.eventId }.toSet()
        fun deliveredOnDevice(offer: EventRecord?) = offer != null && offer.id !in revoked
        val offPrompts = off.obstaclePrompts()
        val delivered = offPrompts.filter { deliveredOnDevice(it.deviceOffer) }
        appendLine("  obstacle prompts, M3a off (as on the device): offered ${offPrompts.size}, delivered ${delivered.size}")
        for (p in offPrompts) {
            val e = p.obstacle?.distanceEstimate
            appendLine(
                "    seq ${p.seq} ${p.event.messageKey} ${p.event.priority} distance=${p.event.distance ?: "-"}" +
                    (if (deliveredOnDevice(p.deviceOffer)) "" else " [revoked]") +
                    " obstacle=${p.obstacle?.category ?: "?"} y=${p.obstacle?.boundingBox?.maxY?.let(::fmt) ?: "-"}" +
                    " contact=${e?.contact?.validity?.name?.lowercase() ?: "none"} point=${fmt(e?.contact?.pointM)} lower=${fmt(e?.contact?.lowerM)}",
            )
        }
        val points = delivered.mapNotNull { p -> p.obstacle?.distanceEstimate?.contact?.takeIf { it.validity == ContactValidity.VALID }?.pointM }.sorted()
        if (points.isNotEmpty()) {
            appendLine("  delivered prompt distance (valid contacts, point estimate): n ${points.size}, min ${fmt(points.first())}, median ${fmt(points[points.size / 2])}, max ${fmt(points.last())}")
        }

        for ((delivery, run) in on) {
            val baseline = base.getValue(delivery)
            val basePrompts = baseline.obstaclePrompts()
            val prompts = run.obstaclePrompts()
            val bySeq = prompts.associateBy { it.seq }
            // Which baseline prompts count as heard: under unchanged_as_recorded the baseline is the
            // device, so the device's own revokes say; assuming all delivered, all of them.
            val heard = when (delivery) {
                CounterfactualDelivery.UNCHANGED_AS_RECORDED -> basePrompts.filter { deliveredOnDevice(it.deviceOffer) }
                CounterfactualDelivery.ALL_DELIVERED -> basePrompts
            }
            val unchanged = prompts.count { it.event == it.deviceOffer?.withoutId() }
            appendLine(
                "  assuming ${delivery.name.lowercase()}: obstacle prompts offered, M3a off ${basePrompts.size} -> on ${prompts.size}; " +
                    "on-run prompts the device never offered: ${prompts.size - unchanged} (delivery unknown)",
            )
            fun fate(p: Prompt): String {
                val now = bySeq[p.seq] ?: return "gone"
                return when {
                    now.event == p.event -> "kept"
                    now.event.messageKey == p.event.messageKey -> "reworded"
                    else -> "replaced"
                }
            }
            val fates = heard.groupBy(::fate)
            val baseSeqs = basePrompts.map { it.seq }.toSet()
            appendLine(
                "    of ${heard.size} heard with M3a off: kept ${fates["kept"].orEmpty().size}, reworded ${fates["reworded"].orEmpty().size}, " +
                    "replaced ${fates["replaced"].orEmpty().size}, gone ${fates["gone"].orEmpty().size}; " +
                    "new where M3a off offered none: ${prompts.count { it.seq !in baseSeqs }}",
            )
            for (fate in listOf("reworded", "replaced", "gone")) {
                fates[fate].orEmpty().forEach { p ->
                    val now = bySeq[p.seq]?.event
                    appendLine(
                        "      $fate seq ${p.seq} ${p.event.messageKey} ${p.event.priority}/${p.event.distance}" +
                            (if (now == null) "" else " -> ${now.messageKey} ${now.priority}/${now.distance}"),
                    )
                }
            }
            appendLine("    all decided events: M3a off ${baseline.frames.sumOf { it.second.events.size }}, on ${run.frames.sumOf { it.second.events.size }}")
        }
    }

    private fun pct(n: Int, of: Int) = if (of == 0) "-" else String.format(Locale.ROOT, "%.1f%%", 100.0 * n / of)
    private fun fmt(v: Float?) = v?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "-"

    private companion object {
        const val OBSTACLE_PREFIX = "event_obstacle_"
    }
}
