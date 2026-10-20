package com.sailens.guidance.trace.regression

import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.config.PerceptionConfig
import com.sailens.guidance.depth.ContactValidity
import com.sailens.guidance.depth.GroundContactDistanceConfig
import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.CameraGeometryResolver
import com.sailens.guidance.geometry.FrameFacts
import com.sailens.guidance.geometry.HeightSource
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.SensorSample
import com.sailens.guidance.geometry.Vec3
import com.sailens.guidance.geometry.toCameraFacts
import com.sailens.guidance.model.perception.DetectedObstacle
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.semantics.DefaultNavigationSemanticsProvider
import com.sailens.guidance.trace.capture.CaptureModes
import com.sailens.guidance.trace.capture.CaptureSchema
import com.sailens.guidance.trace.capture.CaptureSensor
import com.sailens.guidance.trace.capture.CaptureSession
import com.sailens.guidance.trace.capture.CaptureSessionReader
import com.sailens.guidance.trace.capture.RegressionFrameRecord
import com.sailens.guidance.trace.capture.orThrow
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * M3a's offline evaluation on real model-regression captures. Skipped unless a path is given --
 * captures contain faces and places and never enter the repository or CI:
 *
 * ```
 * gradlew :sailens-guidance:testDebugUnitTest --tests "*GroundContactCaptureEvaluationTest*" -PreplayCapture=<dir> [-Pm3aPhoneHeight=1.2]
 * ```
 *
 * Each capture is replayed twice through the real decision path, with the camera geometry resolved
 * from its own sensor log and camera record (captures before schema 1.4 recorded none) and the
 * phone height given as a preset:
 * - as recorded (M3a off), which must still replay exactly -- the shadow estimate is computed but
 *   changes nothing -- and gives the ground-contact distance of the obstacle behind every prompt;
 * - counterfactually with M3a on, which says which prompts would change.
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
        val notExact = mutableListOf<String>()
        for (directory in captures) {
            val session = CaptureSessionReader.read(directory).orThrow()
            val manifest = session.manifest
            if (manifest.captureMode != CaptureModes.MODEL_REGRESSION) continue
            val block = checkNotNull(manifest.regression)
            val semantics = DefaultNavigationSemanticsProvider().semanticSegmentationSemantics()
            val recordedConfig = CaptureSchema.json.decodeFromJsonElement(PerceptionConfig.serializer(), block.perceptionConfig!!)
            val analysis = CaptureSchema.json.decodeFromJsonElement(AnalysisConfig.serializer(), block.analysisConfig!!)
            val geometryOf = geometryFromSensors(session, phone)
            fun classMap(record: RegressionFrameRecord) = CaptureSessionReader.classMap(session, record)

            val off = Run()
            val offReport = RegressionReplay(recordedConfig, analysis, semantics).replay(
                session.regressionFrames, session.revokes, ::classMap, block.stats.firstLostSeq, geometryOf, off::record,
            )
            if (!offReport.exact) notExact += manifest.sessionId
            val onConfig = recordedConfig.copy(groundContact = GroundContactDistanceConfig(enabled = true))
            val on = Run()
            RegressionReplay(onConfig, analysis, semantics).replay(
                session.regressionFrames, session.revokes, ::classMap, block.stats.firstLostSeq, geometryOf, on::record,
                compareDecisions = false,
            )
            val text = report(session, offReport, off, on, geometryOf)
            File(out, "${manifest.sessionId}.m3a.txt").writeText(text)
            summary.appendLine().append(text)
        }
        File(out, "m3a-summary.txt").writeText(summary.toString())
        println(summary)
        assertTrue("with M3a off the replay must stay exact: $notExact", notExact.isEmpty())
    }

    /** Geometry for each regression frame, resolved as the device would from the capture's raw facts. */
    private fun geometryFromSensors(session: CaptureSession, phone: PhoneGeometrySettings): (RegressionFrameRecord) -> CameraGeometry? {
        val camera = session.manifest.camera?.toCameraFacts() ?: return { null }
        val transform = session.frames.firstOrNull { it.sensorToBufferTransform != null }?.sensorToBufferTransform
        val gravity = session.sensors.filter { it.sensor == CaptureSensor.GRAVITY }.sortedBy { it.timestampNanos }
        val times = LongArray(gravity.size) { gravity[it].timestampNanos }
        fun nearest(t: Long): SensorSample? {
            if (gravity.isEmpty()) return null
            val i = times.binarySearch(t).let { if (it >= 0) it else -it - 1 }
            val best = listOfNotNull(gravity.getOrNull(i - 1), gravity.getOrNull(i)).minBy { kotlin.math.abs(it.timestampNanos - t) }
            return SensorSample(best.timestampNanos, Vec3(best.values[0], best.values[1], best.values[2]))
        }
        val resolver = CameraGeometryResolver()
        return { record ->
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
        fun obstaclePrompts() = frames.mapNotNull { (record, outcome) ->
            outcome.events.firstOrNull()?.takeIf { it.messageKey.startsWith("event_obstacle_") }?.let {
                Prompt(record.seq, it, outcome.obstacles, record.events.firstOrNull()?.id)
            }
        }
    }

    /** [recordedId]: the id the device gave this frame's offered event, to look its revoke up by. */
    private class Prompt(val seq: Long, val event: SceneEvent, val obstacles: List<DetectedObstacle>, val recordedId: String?) {
        /** The obstacle behind the prompt: the nearest tracked one in the zones it speaks of. */
        val obstacle: DetectedObstacle? = obstacles
            .filter { it.zone in event.relatedZones }
            .minWithOrNull(compareBy<DetectedObstacle> { it.distance.value }.thenByDescending { it.boundingBox.maxY })
    }

    private fun report(
        session: CaptureSession,
        offReport: RegressionReplay.Report,
        off: Run,
        on: Run,
        geometryOf: (RegressionFrameRecord) -> CameraGeometry?,
    ): String = buildString {
        val m = session.manifest
        appendLine("capture ${m.sessionId} (${m.group ?: "?"}: ${m.sceneLabel ?: "?"})")
        appendLine("  frames replayed ${offReport.framesReplayed}, exact with M3a off: ${offReport.exact}")

        val geometries = off.frames.mapNotNull { geometryOf(it.first) }
        val quality = geometries.groupingBy { it.quality.javaClass.simpleName.lowercase() }.eachCount()
        val pitches = geometries.mapNotNull { it.pitchDownDegrees }.sorted()
        appendLine(
            "  geometry ${quality.entries.joinToString { "${it.key} ${it.value}" }}; pitch down p10/median/p90 " +
                (if (pitches.isEmpty()) "-" else listOf(0.1, 0.5, 0.9).joinToString("/") { fmt(pitches[(it * (pitches.size - 1)).toInt()]) }),
        )
        val tracked = off.frames.flatMap { it.second.obstacles }
        val validity = tracked.groupingBy { it.distanceEstimate?.contact?.validity ?: ContactValidity.NO_GEOMETRY }.eachCount()
        appendLine("  tracked obstacle-frames ${tracked.size}; contact validity: " +
            ContactValidity.entries.filter { validity[it] != null }.joinToString { "${it.name.lowercase()} ${pct(validity.getValue(it), tracked.size)}" })
        val levelPairs = tracked.mapNotNull { o -> o.distanceEstimate?.let { "${it.today.name.first()}>${it.contactLevel?.name?.first() ?: '-'}" } }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        appendLine("  today>contact level (lower bound), all validities: " + levelPairs.joinToString { "${it.key} ${it.value}" })
        val wouldRaise = tracked.count { o ->
            val e = o.distanceEstimate ?: return@count false
            e.contact.validity == ContactValidity.VALID && e.contactLevel != null && e.contactLevel.value < e.today.value
        }
        appendLine("  valid contacts nearer than today: $wouldRaise (${pct(wouldRaise, tracked.size)})")

        // A prompt the device offered but could not deliver (speech busy) was revoked: nobody heard it.
        val revoked = session.revokes.map { it.eventId }.toSet()
        val offPrompts = off.obstaclePrompts()
        fun heard(p: Prompt) = p.recordedId !in revoked
        appendLine("  obstacle prompts offered (M3a off): ${offPrompts.size}, delivered ${offPrompts.count(::heard)}")
        for (p in offPrompts) {
            val e = p.obstacle?.distanceEstimate
            appendLine(
                "    seq ${p.seq} ${p.event.messageKey} ${p.event.priority} distance=${p.event.distance ?: "-"}" +
                    (if (heard(p)) "" else " [revoked]") +
                    " obstacle=${p.obstacle?.category ?: "?"} y=${p.obstacle?.boundingBox?.maxY?.let(::fmt) ?: "-"}" +
                    " contact=${e?.contact?.validity?.name?.lowercase() ?: "none"} point=${fmt(e?.contact?.pointM)} lower=${fmt(e?.contact?.lowerM)}",
            )
        }
        val points = offPrompts.filter(::heard).mapNotNull { p -> p.obstacle?.distanceEstimate?.contact?.takeIf { it.validity == ContactValidity.VALID }?.pointM }.sorted()
        if (points.isNotEmpty()) {
            appendLine("  delivered prompt distance (valid contacts, point estimate): n ${points.size}, min ${fmt(points.first())}, median ${fmt(points[points.size / 2])}, max ${fmt(points.last())}")
        }

        val onPrompts = on.obstaclePrompts()
        val offKeys = offPrompts.map { it.seq to it.event.messageKey }.toSet()
        val onKeys = onPrompts.map { it.seq to it.event.messageKey }.toSet()
        val offBySeq = offPrompts.associateBy { it.seq to it.event.messageKey }
        val removed = (offKeys - onKeys).sortedBy { it.first }
        val removedHeard = removed.count { heard(offBySeq.getValue(it)) }
        // Counterfactual offers: whether the device would have delivered them is unknown, and a
        // revoke only reaches an event the device also decided, so offers re-made after a revoke
        // on the device do not recur. Compare delivered prompts, not offers.
        appendLine(
            "  with M3a on: obstacle prompts offered ${onPrompts.size}; added ${(onKeys - offKeys).size}, " +
                "removed ${removed.size} (of which delivered on the device: $removedHeard)",
        )
        (onKeys - offKeys).sortedBy { it.first }.forEach { appendLine("    + seq ${it.first} ${it.second}") }
        removed.forEach { appendLine("    - seq ${it.first} ${it.second}" + if (heard(offBySeq.getValue(it))) "" else " [was revoked]") }
        val reworded = onPrompts.mapNotNull { p ->
            val before = offBySeq[p.seq to p.event.messageKey] ?: return@mapNotNull null
            if (before.event.distance != p.event.distance || before.event.priority != p.event.priority) {
                "seq ${p.seq} ${p.event.messageKey}: ${before.event.priority}/${before.event.distance} -> ${p.event.priority}/${p.event.distance}"
            } else {
                null
            }
        }
        appendLine("  same prompt, different priority or distance: ${reworded.size}")
        reworded.forEach { appendLine("    $it") }
        val allOff = off.frames.sumOf { it.second.events.size }
        val allOn = on.frames.sumOf { it.second.events.size }
        appendLine("  all decided events: off $allOff, on $allOn")
    }

    private fun pct(n: Int, of: Int) = if (of == 0) "-" else String.format(Locale.ROOT, "%.1f%%", 100.0 * n / of)
    private fun fmt(v: Float?) = v?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "-"
}
