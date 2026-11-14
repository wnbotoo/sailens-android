package com.sailens.guidance.sim

import com.sailens.core.frame.ImageFrame
import com.sailens.guidance.depth.geometry.DepthBaseline
import com.sailens.guidance.depth.geometry.DepthEvidenceRecorder
import com.sailens.guidance.depth.geometry.DepthEvidenceRun
import com.sailens.guidance.depth.geometry.DepthEvidenceSlot
import com.sailens.guidance.depth.geometry.DepthSession
import com.sailens.guidance.depth.geometry.DepthStartContext
import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.CameraGeometryResolver
import com.sailens.guidance.geometry.FrameFacts
import com.sailens.guidance.geometry.GroundFitter
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.SensorSample
import com.sailens.guidance.geometry.Vec3
import com.sailens.guidance.geometry.toCameraFacts
import com.sailens.guidance.safety.BaselineQualification
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.abs

/**
 * The depth lane's fits on every simulator scenario, frozen field by field ([DepthBaseline]): what a change to the
 * depth contract must leave exactly as it is. Each case is one depth session through the real lane, the simulator's
 * depth model and the real ground fit, fed every frame the camera delivers, one at a time and in order, so the runs
 * and the fitter's history are the same on every machine and every run. Each run's line holds the observation, the
 * guard after it, the geometry it was given and the snapshot the lane published with it. Regenerate with
 *
 * ```
 * gradlew :sailens-guidance:testDebugUnitTest --tests "*DepthBaselineScenarioTest*" -PupdateGoldens
 * ```
 */
class DepthBaselineScenarioTest {

    private val resolver = CameraGeometryResolver()

    private class Case(val name: String, val scenario: Scenario, val model: SimulatedDepthModel)

    /**
     * Every scenario with the default model (a 9:16 tensor, a small letterbox); the scenarios again with a square
     * tensor, whose letterbox leaves wide bands unseen; and a walk toward a drop with a model that outputs depth.
     */
    private val cases: List<Case> =
        (Scenarios.all + EdgeScenarios.all).map { Case("${it.name}/default", it, SimulatedDepthModel()) } +
            Scenarios.all.map { Case("${it.name}/square", it, SimulatedDepthModel(width = 224, height = 224)) } +
            Case("${Scenarios.curbAndDrop.name}/inverted", Scenarios.curbAndDrop, SimulatedDepthModel(inverted = true)) +
            Case("${WALK_TO_DROP.name}/default", WALK_TO_DROP, SimulatedDepthModel())

    @Test
    fun `every simulator scenario fits as the baseline froze it, field by field`() {
        val lines = listOf(HEADER) + cases.flatMap(::run)
        val fits = lines.count { !it.startsWith("#") }
        assertTrue("fits recorded: $fits", fits > 1_000)
        assertTrue("the guard held on the walk to the drop", lines.any { it.startsWith("case=${WALK_TO_DROP.name}/") && " holding=true " in it })
        val text = lines.joinToString("\n", postfix = "\n")
        // The lines as text beside the other replay outputs, to read when they differ.
        System.getProperty("sailens.replay.out")?.takeIf { it.isNotBlank() }?.let { File(it).apply { mkdirs() }.resolve("depth-baseline-simulator.txt").writeText(text) }
        if (System.getProperty("sailens.updateGoldens") == "true") {
            val dir = File(checkNotNull(System.getProperty("sailens.goldens.dir")) { "goldens dir not set" }).resolveSibling(DIR)
            dir.mkdirs()
            GZIPOutputStream(File(dir, FILE).outputStream()).use { it.write(text.toByteArray()) }
            return
        }
        val resource = javaClass.getResourceAsStream("/$DIR/$FILE") ?: return fail("no baseline $DIR/$FILE; generate it with -PupdateGoldens")
        val expected = GZIPInputStream(resource).use { it.readBytes().decodeToString() }.replace("\r", "").trimEnd('\n').split('\n')
        val comparison = DepthBaseline.compare(expected, lines)
        assertTrue("$DIR/$FILE: $comparison", comparison.same)
    }

    /** One depth session over [case]'s frames; a line per run, in run order. */
    private fun run(case: Case): List<String> = runBlocking {
        val fitters = Collections.synchronizedList(mutableListOf<GroundFitter>())
        val lines = Collections.synchronizedList(mutableListOf<String>())
        lateinit var session: DepthSession
        val recorder = object : DepthEvidenceRecorder {
            override fun copy(traceSessionId: String, generation: Long, runId: Long, frame: ImageFrame): DepthEvidenceSlot {
                val sequence = frame.sequenceNumber
                return object : DepthEvidenceSlot {
                    override fun submit(run: DepthEvidenceRun) {
                        lines += line(case.name, runId, sequence, run, fitters.single(), session)
                    }

                    override fun discard() {
                        lines += "case=${case.name} run=$runId frame=$sequence discarded=true"
                    }
                }
            }
        }
        val pipeline = SimulatedPipeline(
            case.scenario,
            depth = case.model,
            depthFitter = { GroundFitter().also { fitters += it } },
            depthEvidence = recorder,
        )
        val lane = checkNotNull(pipeline.depthLane)
        // A rendezvous: the next frame waits for the lane to take this one, and the lane handles one at a time.
        val frames = Channel<ImageFrame>()
        session = lane.start(
            DepthStartContext(
                frames = frames.receiveAsFlow(),
                releaseFrame = {},
                baseline = { BaselineQualification.Qualified },
                geometry = { frame -> geometry(case.scenario, frame) },
                traceSessionId = "baseline",
            ),
        )
        try {
            withTimeout(TIMEOUT_MS) {
                case.scenario.frames.forEachIndexed { index, frame ->
                    if (frame.delivery != FrameDelivery.Lost) frames.send(pipeline.cameraFrame(index))
                }
                frames.close()
                checkNotNull(session.job).join()
            }
        } finally {
            lane.stop(session)
        }
        synchronized(lines) { lines.toList() }
    }

    private fun line(case: String, runId: Long, sequence: Long, run: DepthEvidenceRun, fitter: GroundFitter, session: DepthSession): String {
        val identity = listOf("case" to case, "run" to "$runId", "frame" to "$sequence", "outcome" to run.outcome, "fit" to (run.fitOrdinal?.toString() ?: DepthBaseline.NONE))
        val snapshot = session.latest()
        val extras = DepthBaseline.geometry(run.geometry) + listOf(
            "grid" to "${run.gridWidth}x${run.gridHeight}",
            "map" to "${run.run.map.width}x${run.run.map.height}",
            "shape" to run.run.shapeLabel,
            "snapshotRun" to "${snapshot.runId}",
            "snapshotFrame" to "${snapshot.frameSequence}",
            "snapshotTime" to "${snapshot.frameTimeNanos}",
            "snapshotBasis" to "${snapshot.timeBasis?.name?.lowercase()}",
            "period" to "${snapshot.periodMs}",
        )
        val observation = run.observation
        return if (observation == null) {
            DepthBaseline.Line(identity + listOf("validity" to DepthBaseline.NONE) + DepthBaseline.guard(fitter) + extras).text
        } else {
            DepthBaseline.line(identity, observation, fitter, extras).text
        }
    }

    private fun geometry(scenario: Scenario, frame: ImageFrame): CameraGeometry {
        val facts = scenario.facts((frame.sequenceNumber - 1).toInt())
        return resolver.resolve(
            facts.camera.toCameraFacts(),
            FrameFacts.of(frame),
            { t -> facts.gravity.minByOrNull { abs(it.timestampNanos - t) }?.let { SensorSample(it.timestampNanos, Vec3(it.values[0], it.values[1], it.values[2])) } },
            PhoneGeometrySettings.DEFAULT,
        )
    }

    private companion object {
        const val DIR = "depth-baseline"
        /** Compressed: two thousand lines of digests and raw bits are read by the test, not by people. */
        const val FILE = "simulator.txt.gz"
        const val TIMEOUT_MS = 600_000L
        const val HEADER = "# Depth lane fits on the simulator scenarios (DepthBaselineScenarioTest): one line per run, floats as raw bits."

        /**
         * Toward a 1.5 m drop 4 m ahead at 8 cm a frame until its floor fills the near view, standing at the edge, a step
         * back where both levels show again, and on to the edge once more: the support-switch guard's hold and release.
         */
        val WALK_TO_DROP: Scenario = run {
            val drop = Scenarios.curbAndDrop.frames[40].world
            val walk = (0..45).map { it * 0.08f } + List(8) { 3.6f } + listOf(3.2f, 2.8f, 2.4f, 2.0f) + (25..45).map { it * 0.08f }
            Scenario(
                name = "walk_to_drop",
                description = "The depth baseline's walk to a drop and back.",
                frames = walk.map { walked -> ScenarioFrame(Pose(Scenarios.HEIGHT, Scenarios.PITCH, walkedM = walked), drop) },
            )
        }
    }
}
