package com.sailens.guidance.delivery.fingerprint

import com.sailens.core.geometry.NormalizedRect
import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.config.DetFirstConfig
import com.sailens.guidance.evaluation.EvaluationFixtures
import com.sailens.guidance.model.analysis.FrameQuality
import com.sailens.guidance.model.analysis.GroundRecognition
import com.sailens.guidance.model.analysis.SceneSnapshot
import com.sailens.guidance.model.common.DirectionZone
import com.sailens.guidance.model.common.EventPriority
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
        // Frame i is seq i + 1: the first description holds over seq 1..46, the second over 56..76.
        val first = 1L..46L
        val second = 56L..76L
        assertTrue(
            "the side person waited for the first description",
            lines.any { seqOf(it) in first && it.contains(" outcome ") && it.contains("messageKey=event_obstacle_right_person") && it.contains("revokeReason=waiting_for_description") },
        )
        assertTrue(
            "the person ahead cut the first description off",
            lines.any { seqOf(it) in first && it.contains(" preempt_description event_obstacle_center") },
        )
        assertTrue("the floor notice waited for the second description", lines.any { seqOf(it) in second && it.contains(" description_waits LOW -> true") })
        val floorNoticeSaid = lines.first { it.contains(" speak_notice ") && it.contains("key=event_ground_unrecognized") && it.endsWith("-> taken") }
        assertTrue("the floor notice was said only after the second description", seqOf(floorNoticeSaid) > second.last)
        lines
    }

    /** A sentence a higher one flushes is told it was cut inside the higher one's offer, so the next decision already knows. */
    @Test
    fun `a flushed sentence is cut before the offer that flushed it returns`() {
        val analysis = AnalysisConfig(detFirst = DetFirstConfig.LAUNCH)
        val pipeline = FingerprintPipeline(analysis, OutputCondition.V1)
        frames(analysis, 8) { i -> if (i < 3) scene(sidePerson(1)) else scene(sidePerson(1), centerObstacle(2, ObstacleCategory.VEHICLE)) }
            .forEach(pipeline::onFrame)
        val lines = pipeline.lines
        val cut = lines.indexOfFirst { it.contains(" cut speak e1") }
        val receipt = lines.indexOfFirst { it.contains(" receipt cut e1") }
        val flushing = lines.indexOfFirst { it.contains(" speak e2 ") && it.endsWith("-> taken") }
        assertTrue("the side person was cut, its receipt given, then the offer that cut it returned", cut in 0 until receipt && receipt < flushing)
        assertEquals("all in the frame of the flush", setOf(seqOf(lines[flushing])), setOf(seqOf(lines[cut]), seqOf(lines[receipt])))
        val redecided = lines.indexOfFirst { it.startsWith("${seqOf(lines[flushing]) + 1} decided [") && it.contains("event_obstacle_right_person") }
        assertTrue("the cut side person is decided again on the very next frame", redecided > flushing)
    }

    /** What the engine ends by itself is reported at the next settlement, never inside the frame it ended in. */
    @Test
    fun `a sentence heard to the end is reported at the next settlement`() {
        val analysis = AnalysisConfig(detFirst = DetFirstConfig.LAUNCH)
        val pipeline = FingerprintPipeline(analysis, OutputCondition.V1)
        val count = 40
        frames(analysis, count) { i -> if (i == 0) scene(centerPerson(1)) else scene() }.forEach(pipeline::onFrame)
        val lines = pipeline.lines
        val endsAt = START_MS + FingerprintPipeline.durationMs("event_obstacle_center_person")
        val spoken = lines.single { it.contains(" spoken speak e1") }
        assertEquals("reported by the first frame at or after its end", (endsAt - START_MS + STEP_MS - 1) / STEP_MS + 1, seqOf(spoken))
        assertTrue("its confirmation followed", lines.indexOf(spoken) < lines.indexOfFirst { it.contains(" receipt confirm e1") })
    }

    /** A port handed something else than the event decided under the same id says so: what an output reads is in the line. */
    @Test
    fun `a port argument that differs from the decided event is written whole`() {
        val analysis = AnalysisConfig()
        val pipeline = FingerprintPipeline(analysis, OutputCondition.V3)
        frames(analysis, 1) { scene(centerPerson(1)) }.forEach(pipeline::onFrame)
        val event = pipeline.lastDecided.first()
        assertEquals("the event as decided goes by its number", "e1", pipeline.argument(event))
        val otherPriority = pipeline.argument(event.copy(priority = EventPriority.LOW))
        val otherWording = pipeline.argument(event.copy(messageKey = "event_obstacle_left_person"))
        assertTrue("another priority under the same id is written whole", otherPriority.startsWith("e1 as=") && otherPriority.contains("\"priority\":\"LOW\""))
        assertTrue("another wording under the same id is written whole", otherWording.startsWith("e1 as=") && otherWording.contains("event_obstacle_left_person"))
        assertTrue("the two differ", otherPriority != otherWording)
        val otherComponents = pipeline.argument(event.copy(components = emptyList()))
        assertTrue("components the regression record leaves out are compared too", otherComponents.startsWith("e1 as=") && otherComponents.endsWith("components=[]"))
        assertTrue("the prompt that reached the user goes by its number", pipeline.lines.any { it == "1 reached e1" })
        pipeline.onReachedUser(event.copy(messageKey = "event_obstacle_left_person"))
        assertTrue("a prompt that reached the user under the same id but otherwise is written whole", pipeline.lines.last().startsWith("1 reached e1 as="))
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

    private fun seqOf(line: String): Long = line.substringBefore(' ').toLong()

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
