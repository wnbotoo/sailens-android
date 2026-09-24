package com.sailens.guidance.processor.decision

import com.sailens.core.mask.BinaryMask
import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.model.analysis.RoadSafetyState
import com.sailens.guidance.model.analysis.SceneElements
import com.sailens.guidance.model.analysis.SceneSnapshot
import com.sailens.guidance.model.perception.SegmentationMask
import com.sailens.guidance.processor.analysis.ConnectivityChecker
import com.sailens.guidance.processor.analysis.GroundRecognitionAnalyzer
import com.sailens.guidance.semantics.CityscapesNavigationSemantics
import com.sailens.vision.taxonomy.CityscapesTaxonomy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ground-recognition gate must never make a real wall quieter than it was before the gate.
 *
 * Runs the real connectivity checker, gate and generator over whole-frame masks, a frame every
 * [FRAME_MS], the way the pipeline feeds them.
 */
class GroundGateSafetyTest {

    private val config = AnalysisConfig()
    private var now = 0L
    private val connectivity = ConnectivityChecker(config)
    private val gate = GroundRecognitionAnalyzer(config, CityscapesNavigationSemantics, clock = { now })
    private val generator = EventGenerator(config)

    @Test
    fun `facing a building-labelled wall from the first frame is announced as blocked at once`() {
        // Starting up in front of a facade, or turning to face one: no earlier frame warned.
        val keys = run(CityscapesTaxonomy.BUILDING, durationMs = 1_000L)

        assertTrue("blocked must be announced before the gate confirms: $keys", "event_blocked" in keys)
        assertTrue("nothing is paused yet: $keys", "event_ground_unrecognized" !in keys)
    }

    @Test
    fun `an unknown floor held past the confirmation window switches to the status notice`() {
        val keys = run(CityscapesTaxonomy.BUILDING, durationMs = config.groundUnrecognizedEnterMs + 500L)

        val confirmedFrom = keys.indexOfFirst { it == "event_ground_unrecognized" }
        assertTrue("the notice must come once the state is confirmed: $keys", confirmedFrom >= 0)
        assertTrue(
            "blocked is paused only after confirmation: $keys",
            keys.drop(confirmedFrom).none { it == "event_blocked" },
        )
    }

    @Test
    fun `a wall or fence the model recognises is never paused`() {
        for (barrier in listOf(CityscapesTaxonomy.WALL, CityscapesTaxonomy.FENCE)) {
            val keys = run(barrier, durationMs = config.groundUnrecognizedEnterMs * 3)

            assertTrue("blocked must keep being announced for class $barrier", "event_blocked" in keys.takeLast(1))
            assertTrue("class $barrier: $keys", "event_ground_unrecognized" !in keys)
        }
    }

    /** Feeds whole-frame [classId] masks for [durationMs]; returns the message keys of every frame. */
    private fun run(classId: Int, durationMs: Long): List<String> {
        connectivity.reset()
        gate.reset()
        val keys = mutableListOf<String>()
        val end = now + durationMs
        while (now <= end) {
            val mask = SegmentationMask(WIDTH, HEIGHT, IntArray(WIDTH * HEIGHT) { classId })
            val passable = BinaryMask(WIDTH, HEIGHT) // nothing in these frames is passable
            val snapshot = SceneSnapshot(
                timestamp = now,
                obstacles = emptyList(),
                bottomCoverage = 0f,
                connectivity = connectivity.analyze(passable),
                sceneElements = SceneElements(),
                roadSafety = RoadSafetyState(false, false, 0f, false, false, 0f),
                groundTypeChange = null,
                groundRecognition = gate.analyze(mask).recognition,
            )
            keys += generator.generate(snapshot, now).map { it.messageKey }
            now += FRAME_MS
        }
        assertEquals(false, keys.isEmpty())
        return keys
    }

    private companion object {
        const val WIDTH = 90
        const val HEIGHT = 160
        const val FRAME_MS = 50L
    }
}
