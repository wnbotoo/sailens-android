package com.sailens.guidance.sim

import com.sailens.core.runtime.MlRuntimeInfo
import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.model.common.DirectionZone
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.scene.SceneEventMessageKeys
import com.sailens.guidance.model.scene.SceneResult
import com.sailens.guidance.safety.BaselineQualification
import com.sailens.guidance.usecase.scene.GuidancePipelineFailedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Every edge scenario, run through the real pipeline, must produce its golden output line for line,
 * as [ScenarioGoldenTest] does for [Scenarios], and must reach what it is there to exercise: each test
 * asserts that on top of the golden, so a scenario that drifts away from its path fails even when its
 * golden is regenerated. A frame that is lost or that a model fails on has no line. Regenerate with
 *
 * ```
 * gradlew :sailens-guidance:testDebugUnitTest --tests "*EdgeScenarioGoldenTest*" -PupdateGoldens
 * ```
 *
 * Each scenario's test is named after the scenario.
 */
class EdgeScenarioGoldenTest {

    @Test
    fun obstacles_center_left_right() {
        val results = check(EdgeScenarios.obstaclesCenterLeftRight)
        for (zone in listOf(DirectionZone.CENTER, DirectionZone.LEFT, DirectionZone.RIGHT)) {
            assertTrue("an obstacle prompt at $zone", results.any { r -> r.events.any { it.category == EventCategory.OBSTACLE && zone in it.relatedZones } })
        }
    }

    @Test
    fun corridor_narrowing() {
        val results = check(EdgeScenarios.corridorNarrowing)
        assertTrue("no narrowing prompt", results.none { r -> r.events.any { it.messageKey == SceneEventMessageKeys.NARROWING } })
        val third = results.size / 3
        val width = results.map { it.debugInfo!!.widthRetentionP25 }
        val first = width.take(third).average()
        val last = width.takeLast(third).average()
        assertTrue("the width retained narrows: first third $first, last third $last", last < first)
        // What the golden cannot show: the fallback never judges this corridor narrowing, so the switch is not
        // the only thing keeping it silent.
        val switchedOn = runToCompletion(EdgeScenarios.corridorNarrowing, GuidanceSimulator(AnalysisConfig(enableNarrowingEvents = true)))
        assertTrue(
            "with narrowing prompts on, the walk is still not said to narrow",
            switchedOn.none { r -> r.events.any { it.messageKey == SceneEventMessageKeys.NARROWING } },
        )
        assertTrue("its narrowest retained width stays at or above 0.55", width.min() >= 0.55f)
    }

    @Test
    fun full_blockage() {
        val results = check(EdgeScenarios.fullBlockage)
        val strip = EdgeScenarios.STRIP_WALK.last + 1L
        assertTrue(
            "over the strip, a complex-path prompt at a confidence between severe and hard-blocked",
            results.filter { it.sequenceNumber <= strip }.any { r ->
                r.events.any { it.messageKey == SceneEventMessageKeys.PATH_COMPLEX } && r.debugInfo!!.blockageConfidence in 0.70f..<0.75f
            },
        )
        assertTrue(
            "the strip never makes the floor unrecognised",
            results.filter { it.sequenceNumber <= strip }.none { it.debugInfo!!.groundRecognition == "unrecognized" },
        )
        assertTrue(
            "the wall makes a critical blocked prompt",
            results.filter { it.sequenceNumber > strip }.any { r -> r.events.any { it.messageKey == SceneEventMessageKeys.BLOCKED && it.priority == EventPriority.CRITICAL } },
        )
    }

    @Test
    fun segmentation_flicker() {
        val results = check(EdgeScenarios.segmentationFlicker)
        val ground = results.map { it.debugInfo!!.groundRecognition }
        assertTrue("the misread frames make the ground uncertain", "uncertain" in ground)
        assertFalse("never unrecognised", "unrecognized" in ground)
    }

    @Test
    fun single_frame_false_detection() {
        val results = check(EdgeScenarios.singleFrameFalseDetection)
        val single = results.bySeq(EdgeScenarios.FALSE_DETECTION_INDEX + 1L)
        assertTrue("the detector reports a person", single.obstacleDetections.any { it.category == ObstacleCategory.PERSON })
        assertTrue("the tracker does not output it", single.obstacles.none { it.category == ObstacleCategory.PERSON })
        val repeat = EdgeScenarios.REPEATED_DETECTION_INDEX + 1L
        assertTrue(
            "no person prompt before the repeat",
            results.filter { it.sequenceNumber < repeat }.none { r -> r.events.any { it.category == EventCategory.OBSTACLE } },
        )
        // Had the single detection's track survived, the repeat (the same box) would match it and be output a frame earlier.
        assertTrue(results.bySeq(repeat).obstacles.none { it.category == ObstacleCategory.PERSON })
        assertTrue(results.bySeq(repeat + 1).obstacles.none { it.category == ObstacleCategory.PERSON })
        val third = results.bySeq(repeat + 2).obstacles.single { it.category == ObstacleCategory.PERSON }
        assertEquals("output from its third detection, counted from the repeat alone", 3, third.stableFrames)
    }

    @Test
    fun dropped_frames() {
        val results = check(EdgeScenarios.droppedFrames)
        val seqs = results.map { it.sequenceNumber }
        assertTrue("sequence numbers skip", seqs.zipWithNext().any { (a, b) -> b - a > 1 })
        assertEquals("the car's track carries on across the gap", null, carTrackAcrossGap(results))
        val iou = gapIou(results)
        assertTrue("the boxes either side of the gap overlap past the tracker's 0.3: $iou", iou > 0.3f)

        val reference = GuidanceSimulator().run(Scenarios.approachParkedCar).firstPromptSeq()
        val lostFrames = EdgeScenarios.droppedFrames
        val firstPrompt = results.firstPromptSeq()
        val lateByMs = (firstPrompt - reference) * lostFrames.stepMs
        assertTrue(
            "the first prompt comes no later than the gap's length after the undisturbed walk's ($lateByMs ms)",
            lateByMs in 0..EdgeScenarios.GAP_FRAMES * lostFrames.stepMs,
        )

        val coveredArrivals = EdgeScenarios.DROPPED_COVERED.filter { lostFrames.frames[it].delivery == FrameDelivery.Delivered }
        val confirmedAt = coveredArrivals[5] + 1L
        assertEquals(
            "the covered lens is confirmed on the sixth covered frame that arrives",
            confirmedAt,
            results.first { it.qualification.baseline is BaselineQualification.Unqualified }.sequenceNumber,
        )
        assertTrue(results.bySeq(confirmedAt).events.any { it.messageKey == SceneEventMessageKeys.CAMERA_BLOCKED })
    }

    @Test
    fun `with the car's box moved past the tracker's overlap after the gap, the track does not carry on`() {
        val results = GuidanceSimulator().run(EdgeScenarios.droppedFramesMovedBox)
        val iou = gapIou(results)
        assertTrue("the moved box overlaps its track under 0.3: $iou", iou < 0.3f)
        assertNotNull("the assertion that holds for the scenario fails here", carTrackAcrossGap(results))
    }

    @Test
    fun rate_limited_det_ttl() {
        val results = check(EdgeScenarios.rateLimitedDetTtl, GuidanceSimulator(perceptionConfig = EdgeScenarios.rateLimitedDetection))
        val last = EdgeScenarios.TTL_LAST_HIT_INDEX + 1L
        // From the last detection of the person, T: what each frame is and whether the person is still output.
        val expected = listOf(
            0 to (false to true),
            100 to (true to true),
            200 to (true to true),
            300 to (true to true),
            400 to (false to true),
            500 to (true to true),
            600 to (true to false),
        )
        for ((afterMs, frame) in expected) {
            val (predicted, person) = frame
            val result = results.bySeq(last + afterMs / 100)
            assertEquals("T+$afterMs predicted", predicted, result.debugInfo!!.obstacleRuntimeInfo == MlRuntimeInfo.skipped("tracker_predict"))
            assertEquals("T+$afterMs person output", person, result.obstacles.any { it.category == ObstacleCategory.PERSON })
        }
        assertTrue("T is the person's last detection", results.bySeq(last).obstacleDetections.any { it.category == ObstacleCategory.PERSON })
    }

    @Test
    fun stale_frames() {
        val onTime = check(EdgeScenarios.staleFrames)
        val late = EdgeScenarios.staleFramesLate
        for (index in late.frames.indices) {
            val frame = late.facts(index).frame
            val lagNanos = if (index in EdgeScenarios.STALE_LATE) 2_000_000_000L else 0L
            assertEquals("frame $index received 2 s before its decision, or on time", lagNanos, frame.sensorTimestampNanos - frame.receivedElapsedRealtimeNanos)
            assertEquals("aligned: the clock reads the frame's timestamp", late.clockAt(index) * 1_000_000, frame.sensorTimestampNanos)
        }
        val lateResults = runToCompletion(late)
        assertEquals("every frame that arrives and does not fail produces a result", expectedSeqs(late), lateResults.map { it.sequenceNumber })
        val lateSeqs = EdgeScenarios.STALE_LATE.map { it + 1L }
        assertTrue(
            "an obstacle prompt among the late frames",
            lateResults.filter { it.sequenceNumber in lateSeqs }.any { r -> r.events.any { it.category == EventCategory.OBSTACLE } },
        )
        assertEquals(comparable(onTime), comparable(lateResults))
    }

    @Test
    fun frozen_camera() {
        val results = check(EdgeScenarios.frozenCamera)
        val frozenSeqs = EdgeScenarios.FROZEN.map { it + 1L }
        assertEquals("every frozen frame has a result", frozenSeqs, results.map { it.sequenceNumber }.filter { it in frozenSeqs })
        assertTrue("nothing about the camera is said", results.none { r -> r.events.any { it.category == EventCategory.SENSOR_QUALITY } })
        assertTrue("every frame is qualified", results.all { it.qualification.baseline == BaselineQualification.Qualified })
    }

    @Test
    fun cover_near_wall() {
        val results = check(EdgeScenarios.coverNearWall)
        val covered = EdgeScenarios.COVER_NEAR_WALL
        assertTrue("a critical covered-lens prompt", results.any { r -> r.events.any { it.messageKey == SceneEventMessageKeys.CAMERA_BLOCKED && it.priority == EventPriority.CRITICAL } })
        val unqualified = results.filter { it.qualification.baseline is BaselineQualification.Unqualified }.map { it.sequenceNumber }
        // Confirmed on the sixth covered frame, believed again on the sixth uncovered one.
        assertEquals((covered.first + 6L..covered.last + 6L).toList(), unqualified)
        val judgedAgain = results.filter { it.sequenceNumber > covered.last + 6L }
        assertTrue("the path is judged again after the lens is uncovered", judgedAgain.isNotEmpty() && judgedAgain.all { it.qualification.baseline == BaselineQualification.Qualified })
    }

    @Test
    fun low_light_and_cover() {
        val results = check(EdgeScenarios.lowLightAndCover)
        val events = results.flatMap { it.events }
        assertTrue("a high low-light prompt", events.any { it.messageKey == SceneEventMessageKeys.LOW_LIGHT && it.priority == EventPriority.HIGH })
        assertTrue("a critical covered-lens prompt", events.any { it.messageKey == SceneEventMessageKeys.CAMERA_BLOCKED && it.priority == EventPriority.CRITICAL })
    }

    @Test
    fun obstacle_vanishes() {
        val results = check(EdgeScenarios.obstacleVanishes)
        val lastTracked = results.last { r -> r.obstacles.any { it.category == ObstacleCategory.PERSON } }
        val lastDetection = checkNotNull(lastTracked.obstacles.single { it.category == ObstacleCategory.PERSON }.measurement).frameSequence
        for (missed in 1..4) {
            val person = results.bySeq(lastDetection + missed).obstacles.singleOrNull { it.category == ObstacleCategory.PERSON }
            assertEquals("still output after $missed missed detections", lastDetection, person?.measurement?.frameSequence)
        }
        assertTrue("dropped at the fifth", results.bySeq(lastDetection + 5).obstacles.none { it.category == ObstacleCategory.PERSON })
        assertTrue("the person was detected first", lastDetection > EdgeScenarios.VANISH_START_INDEX)
    }

    @Test
    fun perception_faults() {
        val scenario = EdgeScenarios.perceptionFaults
        val run = GuidanceSimulator().runToEnd(scenario)
        val end = run.end
        val error = (end as? SimulationEnd.Failed)?.error ?: return fail("the run must end on the thirtieth failure in a row, ended $end")
        assertTrue("ended by the pipeline failing: $error", error is GuidancePipelineFailedException)
        assertEquals(30, (error as GuidancePipelineFailedException).consecutiveFailures)
        val endIndex = thirtiethFailureInARow(scenario)
        assertEquals(
            "every frame before the end that arrives and does not fail produces a result",
            expectedSeqs(scenario).filter { it <= endIndex },
            run.results.map { it.sequenceNumber },
        )
        assertTrue("the last result comes before the run of thirty", run.results.last().sequenceNumber < endIndex - 29)
        Goldens.check(scenario, "${scenario.name}.golden.txt", run.results.map(GuidanceSimulator::goldenLine))
    }

    @Test
    fun `every scenario has a golden test named after it`() {
        val tests = javaClass.declaredMethods.filter { it.isAnnotationPresent(Test::class.java) }.map { it.name }.toSet()
        val untested = EdgeScenarios.all.map { it.name }.filter { it !in tests }
        assertEquals("add a test named after each scenario", emptyList<String>(), untested)
        assertEquals("scenario names are unique", EdgeScenarios.all.size, EdgeScenarios.all.map { it.name }.toSet().size)
    }

    @Test
    fun `a run is deterministic`() {
        for (scenario in listOf(EdgeScenarios.droppedFrames, EdgeScenarios.perceptionFaults)) {
            val first = GuidanceSimulator().runToEnd(scenario)
            val second = GuidanceSimulator().runToEnd(scenario)
            assertEquals(scenario.name, comparable(first.results), comparable(second.results))
            assertEquals(scenario.name, first.end.describe(), second.end.describe())
        }
    }

    private fun check(scenario: Scenario, simulator: GuidanceSimulator = GuidanceSimulator()): List<SceneResult> {
        val results = runToCompletion(scenario, simulator)
        assertEquals("every frame that arrives and does not fail produces a result", expectedSeqs(scenario), results.map { it.sequenceNumber })
        Goldens.check(scenario, "${scenario.name}.golden.txt", results.map(GuidanceSimulator::goldenLine))
        return results
    }

    private fun runToCompletion(scenario: Scenario, simulator: GuidanceSimulator = GuidanceSimulator()): List<SceneResult> {
        val run = simulator.runToEnd(scenario)
        val end = run.end
        if (end is SimulationEnd.Failed) throw AssertionError("${scenario.name} ended before its last frame", end.error)
        return run.results
    }

    private companion object {
        /** The sequence numbers that must have a result: every frame that arrives and no model fails on (detection runs on every frame). */
        fun expectedSeqs(scenario: Scenario): List<Long> = scenario.frames.withIndex()
            .filter { (_, f) -> f.delivery != FrameDelivery.Lost && !f.semFails && !f.detFails }
            .map { it.index + 1L }

        /** The sequence number of the frame on which the thirtieth failure in a row falls. */
        fun thirtiethFailureInARow(scenario: Scenario): Long {
            var inARow = 0
            scenario.frames.forEachIndexed { index, f ->
                if (f.delivery == FrameDelivery.Lost) return@forEachIndexed
                inARow = if (f.semFails || f.detFails) inARow + 1 else 0
                if (inARow == 30) return index + 1L
            }
            error("${scenario.name} has no thirty failures in a row")
        }

        fun List<SceneResult>.bySeq(seq: Long): SceneResult = singleOrNull { it.sequenceNumber == seq } ?: error("no result for frame $seq")

        fun List<SceneResult>.firstPromptSeq(): Long = first { r -> r.events.any { it.category == EventCategory.OBSTACLE } }.sequenceNumber

        /** The last result before the gap of [EdgeScenarios.droppedFrames] and the first after it. */
        fun List<SceneResult>.aroundGap(): Pair<SceneResult, SceneResult> =
            bySeq(EdgeScenarios.GAP_START_INDEX.toLong()) to bySeq(EdgeScenarios.GAP_START_INDEX + EdgeScenarios.GAP_FRAMES + 1L)

        /** How far the car's track box before the gap overlaps the detected box after it: what the tracker matches on. */
        fun gapIou(results: List<SceneResult>): Float {
            val (before, after) = results.aroundGap()
            val track = before.obstacles.single { it.category == ObstacleCategory.VEHICLE }
            val detected = after.obstacleDetections.single { it.category == ObstacleCategory.VEHICLE }
            return track.boundingBox.iou(detected.boundingBox)
        }

        /**
         * Null when the first frame after the gap updates the car's track from before it: the same track,
         * one more detection, measured on that frame. Otherwise why not. Only the car being output is not
         * enough: an unmatched track counts a miss and is still output.
         */
        fun carTrackAcrossGap(results: List<SceneResult>): String? {
            val (before, after) = results.aroundGap()
            val track = before.obstacles.single { it.category == ObstacleCategory.VEHICLE }
            val carried = after.obstacles.singleOrNull { it.id == track.id } ?: return "the track before the gap is gone"
            if (carried.stableFrames != track.stableFrames + 1) return "detections ${track.stableFrames} -> ${carried.stableFrames}"
            if (carried.measurement?.frameSequence != after.sequenceNumber) return "last measured on frame ${carried.measurement?.frameSequence}"
            return null
        }

        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /**
         * What a run decided, frame by frame, with every random id (an event's own, a track's, and the track
         * ids an event refers to) numbered by first appearance: every field of every event, the baseline
         * qualification, the vision faults, the tracked obstacles and the golden line.
         */
        fun comparable(results: List<SceneResult>): List<String> {
            val ids = LinkedHashMap<String, String>()
            return results.map { r ->
                val text = "seq=${r.sequenceNumber} events=${r.events} baseline=${r.qualification.baseline} " +
                    "faults=${r.visionFaults} obstacles=${r.obstacles} line=${GuidanceSimulator.goldenLine(r)}"
                UUID_PATTERN.replace(text) { match -> ids.getOrPut(match.value) { "id${ids.size + 1}" } }
            }
        }

        fun SimulationEnd.describe(): String = when (this) {
            SimulationEnd.Completed -> "completed"
            is SimulationEnd.Failed -> "failed: ${error.javaClass.name}: ${error.message}"
        }
    }
}
