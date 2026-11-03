package com.sailens.guidance.delivery.fingerprint

import com.sailens.core.geometry.NormalizedRect
import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.config.DetFirstConfig
import com.sailens.guidance.evaluation.EvaluationFixtures
import com.sailens.guidance.model.analysis.FrameQuality
import com.sailens.guidance.model.analysis.GroundRecognition
import com.sailens.guidance.model.analysis.SceneSnapshot
import com.sailens.guidance.model.common.DirectionZone
import com.sailens.guidance.model.common.DistanceLevel
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.common.UrgencyLevel
import com.sailens.guidance.model.perception.DetectedObstacle
import com.sailens.guidance.processor.decision.EventGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Delivery paths the calibration captures never reach under the five [OutputCondition]s -- a description holding the
 * floor, a motor that queues, a prompt heard after it was cut off, callbacks that arrive after the last frame -- as
 * short scripts of built scenes through [FingerprintPipeline]. Their fingerprints are checked in under
 * `src/test/resources/fingerprints/` and compared on every run, as the scenario goldens are:
 *
 * ```
 * gradlew :sailens-guidance:testDebugUnitTest --tests "*DeliveryBoundaryScriptsTest*" -PupdateGoldens
 * ```
 *
 * A golden pins what delivery does today, so that a change meant to keep it shows any difference; it does not say
 * that everything in it is right. Each script also checks that it reached the path it is for.
 */
class DeliveryBoundaryScriptsTest {

    /** A description holds the floor: a prompt below HIGH and the floor notice wait for it, a CRITICAL prompt cuts it off. */
    @Test
    fun `a description holding the floor`() = forBothConfigs("s1_description") { analysis ->
        val script = object : FingerprintScript {
            override val descriptions = listOf(at(0)..at(45), at(55)..at(75))
        }
        val pipeline = FingerprintPipeline(analysis, OutputCondition.V1, script = script)
        frames(analysis, 90) { i ->
            when (i) {
                in 0..29 -> scene(sidePerson(1, UrgencyLevel.MEDIUM))
                in 30..49 -> scene(sidePerson(1, UrgencyLevel.MEDIUM), centerPerson(2))
                in 58..89 -> scene(groundRecognition = GroundRecognition.UNRECOGNIZED)
                else -> scene()
            }
        }.forEach(pipeline::onFrame)
        pipeline.finish()
        val lines = pipeline.lines
        assertTrue("a prompt waited for the description", lines.any { it.contains("description_waits") && it.endsWith("-> true") })
        assertTrue("a prompt cut the description off", lines.any { it.contains("preempt_description") })
        lines
    }

    /** Vibration is the only channel and the motor queues it: once it plays, once it is dropped, once Guidance stops first. */
    @Test
    fun `a motor that queues`() = forBothConfigs("s2_motor") { analysis ->
        val answers = ArrayDeque(listOf<(Long) -> MotorAnswer>({ MotorAnswer.Queued(it + 300, plays = true) }, { MotorAnswer.Queued(it + 300, plays = false) }))
        val script = object : FingerprintScript {
            override fun motorAlone(atMs: Long): MotorAnswer = answers.removeFirstOrNull()?.invoke(atMs) ?: MotorAnswer.Queued(atMs + 60_000, plays = true)
        }
        val pipeline = FingerprintPipeline(analysis, OutputCondition.V3, script = script)
        frames(analysis, 120) { i ->
            when (i) {
                in 0..39 -> scene(centerPerson(1))
                in 40..79 -> scene(centerObstacle(2, ObstacleCategory.VEHICLE))
                else -> scene(centerObstacle(3, ObstacleCategory.STATIC_OBSTACLE))
            }
        }.forEach(pipeline::onFrame)
        val (afterReset, afterLate) = pipeline.stop()
        assertEquals("nothing that arrives after the stop changes anything", afterReset, afterLate)
        val lines = pipeline.lines
        assertTrue("a queued prompt played", lines.any { it.contains("motor_answered") && it.endsWith("played=true") })
        assertTrue("a queued prompt was dropped", lines.any { it.contains("motor_answered") && it.endsWith("played=false") })
        assertTrue("a prompt was still queued at the stop", lines.count { it.contains("vibrate_alone") } > lines.count { it.contains("motor_answered") })
        lines
    }

    /** A higher prompt cuts a lower one off, and the engine then says the lower one was heard to the end after all. */
    @Test
    fun `a prompt heard after it was cut off`() = forBothConfigs("s3_heard_after_cut") { analysis ->
        val script = object : FingerprintScript {
            override fun heardAfterCut(cutAtMs: Long): Long = cutAtMs + 200
        }
        val pipeline = FingerprintPipeline(analysis, OutputCondition.V1, script = script)
        frames(analysis, 60) { i ->
            when (i) {
                in 0..2 -> scene(sidePerson(1))
                in 3..39 -> scene(sidePerson(1), centerObstacle(2, ObstacleCategory.VEHICLE))
                else -> scene()
            }
        }.forEach(pipeline::onFrame)
        pipeline.finish()
        val lines = pipeline.lines
        assertTrue("a prompt was cut off", lines.any { it.contains(" cut speak") })
        assertTrue("the cut prompt was heard after all", lines.any { it.contains(" heard_after_cut speak") })
        if (analysis.detFirst.retryInterrupted) {
            assertTrue("the cooldown heard of the cut", lines.any { it.contains("receipt cut") })
            assertTrue("the cooldown heard it was heard after all", lines.any { it.contains("receipt heard_after_cut") })
        }
        lines
    }

    /**
     * What is still due after the last frame: drained in time order and written down before the stop; or, when
     * Guidance stops first, arriving after the reset and changing nothing.
     */
    @Test
    fun `callbacks after the last frame`() = forBothConfigs("s4_after_last_frame") { analysis ->
        val script = object : FingerprintScript {
            override fun motorAlone(atMs: Long): MotorAnswer = MotorAnswer.Queued(atMs + 500, plays = true)
        }
        fun run(condition: OutputCondition, drain: Boolean): List<String> {
            val pipeline = FingerprintPipeline(analysis, condition, script = script)
            frames(analysis, 30) { i -> if (i >= 28) scene(centerPerson(1)) else scene() }.forEach(pipeline::onFrame)
            val (afterReset, afterLate) = if (drain) pipeline.finish() else pipeline.stop()
            assertEquals("nothing that arrives after the stop changes anything", afterReset, afterLate)
            return listOf("# ${condition.name} ${if (drain) "drained" else "stopped"}") + pipeline.lines
        }
        val lines = run(OutputCondition.V1, drain = true) + run(OutputCondition.V1, drain = false) +
            run(OutputCondition.V3, drain = true) + run(OutputCondition.V3, drain = false)
        assertTrue("a sentence ended after the last frame", lines.any { it.contains(" spoken ") })
        assertTrue("a queued rhythm answered after the last frame", lines.any { it.contains("motor_answered") })
        assertTrue("the stop cut off what was being said", lines.any { it.contains(" cut speak") })
        lines
    }

    private fun forBothConfigs(name: String, run: (AnalysisConfig) -> List<String>) {
        for ((label, detFirst) in listOf("off" to DetFirstConfig(), "launch" to DetFirstConfig.LAUNCH)) {
            check("$name.$label.txt", run(AnalysisConfig(detFirst = detFirst)))
        }
    }

    private fun check(fileName: String, lines: List<String>) {
        if (System.getProperty("sailens.updateGoldens") == "true") {
            val dir = File(checkNotNull(System.getProperty("sailens.fingerprints.dir")) { "fingerprints dir not set" })
            dir.mkdirs()
            File(dir, fileName).writeText(lines.joinToString("\n", postfix = "\n"))
            return
        }
        val resource = javaClass.getResource("/fingerprints/$fileName") ?: return fail("no golden $fileName; generate it with -PupdateGoldens")
        val expected = resource.readText().replace("\r", "").trimEnd('\n').split('\n')
        FingerprintComparison.firstDifference(expected, lines)?.let { difference ->
            fail("$fileName differs from its golden: $difference. If the change is intended, regenerate with -PupdateGoldens and review the diff.")
        }
    }

    private fun frames(analysis: AnalysisConfig, count: Int, scene: (Int) -> SceneSnapshot): List<FingerprintFrame> {
        val generator = EventGenerator(analysis)
        return (0 until count).map { i ->
            val snapshot = scene(i).copy(timestamp = at(i))
            FingerprintFrame(seq = i + 1L, snapshot = snapshot, visionFaults = generator.visionFaults(snapshot), decidedAt = at(i), stationary = false)
        }
    }

    private fun at(frame: Int): Long = START_MS + STEP_MS * frame

    private fun scene(
        vararg obstacles: DetectedObstacle,
        frameQuality: FrameQuality = FrameQuality.OK,
        groundRecognition: GroundRecognition = GroundRecognition.RECOGNIZED,
    ): SceneSnapshot = EvaluationFixtures.snapshot(obstacles.toList(), frameQuality = frameQuality).copy(groundRecognition = groundRecognition)

    private fun centerPerson(n: Int) = EvaluationFixtures.obstacle(n, distance = DistanceLevel.NEAR)

    private fun centerObstacle(n: Int, category: ObstacleCategory) = EvaluationFixtures.obstacle(n, category = category, distance = DistanceLevel.NEAR)

    /** HIGH by default; at MEDIUM urgency a MEDIUM prompt, below what cuts a description off. */
    private fun sidePerson(n: Int, urgency: UrgencyLevel = UrgencyLevel.HIGH) = EvaluationFixtures.obstacle(
        n,
        zone = DirectionZone.RIGHT,
        distance = DistanceLevel.MEDIUM,
        urgency = urgency,
        box = NormalizedRect(x = 0.72f, y = 0.40f, width = 0.14f, height = 0.30f),
    )

    private companion object {
        const val START_MS = 100_000L
        const val STEP_MS = 100L
    }
}
