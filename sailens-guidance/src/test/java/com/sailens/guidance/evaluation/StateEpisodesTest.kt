package com.sailens.guidance.evaluation

import com.sailens.guidance.model.analysis.FrameQuality
import com.sailens.guidance.processor.decision.EventGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** det-first plan D1.1-5: a hazard without a track is a state episode; a gap over 1 s ends it. */
class StateEpisodesTest {

    @Test
    fun `frames within a second of each other are one episode, a longer gap starts the next`() {
        val episodes = StateEpisodes()
        val first = episodes.observe("blocked", at = 0, seq = 1)
        assertSame(first, episodes.observe("blocked", at = 600, seq = 2))
        assertSame("exactly 1 s is still the same episode", first, episodes.observe("blocked", at = 1_600, seq = 3))
        val second = episodes.observe("blocked", at = 2_601, seq = 4)

        assertEquals(listOf("blocked#1", "blocked#2"), episodes.all.map { it.id })
        assertEquals(1_600L, first.endAt)
        assertEquals(3, first.frames)
        assertEquals(4L, second.startSeq)
    }

    @Test
    fun `kinds are independent, and a lapsed episode is not current`() {
        val episodes = StateEpisodes()
        episodes.observe("blocked", at = 0, seq = 1)
        episodes.observe("path_complex", at = 500, seq = 2)
        episodes.observe("blocked", at = 900, seq = 3)

        assertEquals(listOf("blocked#1", "path_complex#1"), episodes.all.map { it.id })
        assertEquals("blocked#1", episodes.current("blocked", at = 1_500)?.id)
        assertNull(episodes.current("blocked", at = 1_901))
    }

    @Test
    fun `the two input-quality states are different hazards, and only trackless kinds count`() {
        val generator = EventGenerator()
        val dark = generator.generate(EvaluationFixtures.snapshot(frameQuality = FrameQuality.TOO_DARK), 0).single()
        val covered = generator.generate(EvaluationFixtures.snapshot(frameQuality = FrameQuality.OBSTRUCTED), 0).single()
        val blocked = generator.generate(EvaluationFixtures.snapshot(blocked = true), 0).single()

        assertEquals("sensor:low_light", StateEpisodes.keyOf(dark))
        assertEquals("sensor:camera_blocked", StateEpisodes.keyOf(covered))
        assertEquals("blocked", StateEpisodes.keyOf(blocked))
        listOf(dark, covered, blocked).forEach { assertEquals(true, it.category in StateEpisodes.TRACKLESS) }
    }
}
