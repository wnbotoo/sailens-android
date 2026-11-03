package com.sailens.guidance.evaluation

import com.sailens.guidance.evaluation.PromptCounts.Repeat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptCountsTest {
    private val aFirst = StageKey("a", Stage.FIRST)
    private val aNear = StageKey("a", Stage.NEAR)
    private val bFirst = StageKey("b", Stage.FIRST)

    private fun PromptCounts.RepeatHistory.metric(vararg stages: StageKey, own: Int = MEDIUM) =
        onCompleted(stages.toSet(), stages.associate { it.hazard to own }, metricOnly = true)

    private fun PromptCounts.RepeatHistory.today(vararg stages: StageKey, own: Int = MEDIUM) =
        onCompleted(stages.toSet(), stages.associate { it.hazard to own }, metricOnly = false)

    @Test
    fun `a today's prompt with nothing metric-only behind it is none of the three`() {
        val history = PromptCounts.RepeatHistory()
        assertNull(history.today(aFirst))
        assertNull(history.today(aFirst))
    }

    @Test
    fun `a metric-only prompt is not classified`() {
        val history = PromptCounts.RepeatHistory()
        assertNull(history.metric(aFirst))
        assertNull(history.metric(aFirst))
    }

    @Test
    fun `saying again only what metric-only said is the repeat C6 forbids`() {
        val history = PromptCounts.RepeatHistory()
        history.metric(aFirst)
        assertEquals(Repeat.REPEAT, history.today(aFirst))
    }

    @Test
    fun `entering NEAR brings something new, and then the same sentence is periodic`() {
        val history = PromptCounts.RepeatHistory()
        history.metric(aFirst)
        assertEquals(Repeat.OVERLAP, history.today(aFirst, aNear))
        assertEquals(Repeat.PERIODIC, history.today(aFirst, aNear))
    }

    @Test
    fun `a new hazard beside it brings something new, and then the same sentence is periodic`() {
        val history = PromptCounts.RepeatHistory()
        history.metric(aFirst)
        assertEquals(Repeat.OVERLAP, history.today(aFirst, bFirst))
        assertEquals(Repeat.PERIODIC, history.today(aFirst, bFirst))
    }

    @Test
    fun `after a sentence with another hazard, the first alone is a repeat`() {
        val history = PromptCounts.RepeatHistory()
        history.metric(aFirst)
        history.today(aFirst, bFirst)
        assertEquals(Repeat.REPEAT, history.today(aFirst))
    }

    @Test
    fun `a higher own priority is new, and then periodic since metric-only never said it that high`() {
        val history = PromptCounts.RepeatHistory()
        history.metric(aFirst, own = MEDIUM)
        assertEquals(Repeat.OVERLAP, history.today(aFirst, own = HIGH))
        assertEquals(Repeat.PERIODIC, history.today(aFirst, own = HIGH))
    }

    @Test
    fun `the highest metric-only level is what a today's prompt is compared with`() {
        val history = PromptCounts.RepeatHistory()
        history.metric(aFirst, own = HIGH)
        history.metric(aFirst, own = MEDIUM)
        assertEquals(Repeat.REPEAT, history.today(aFirst, own = HIGH))
    }

    @Test
    fun `the frozen count takes each stage credited again, not each sentence`() {
        val history = PromptCounts.RepeatHistory()
        history.metric(aFirst, bFirst)
        history.today(aFirst, bFirst, aNear)
        history.today(aFirst)
        assertEquals(3, history.stagesCreditedAgain)
    }

    @Test
    fun `a stage is covered from the first thing not modeled, from a window or from a slow frame`() {
        val none = UnmodeledFrom(emptyList(), emptyList())
        assertFalse(none.covers(Long.MAX_VALUE))
        val both = UnmodeledFrom(listOf(5_000L..6_000L), listOf(4_000L, 9_000L))
        assertFalse(both.covers(3_999))
        assertTrue(both.covers(4_000))
        assertTrue(both.covers(20_000))
    }

    private val nothing = UnmodeledFrom(emptyList(), emptyList())

    @Test
    fun `a stage comes to a kind of loss by when it ends`() {
        assertNull(Loss.of(notKept = false, known = true, completionMs = 1_400, deadlineMs = 1_500, unmodeled = nothing))
        assertEquals(Loss.BOUNDED_DELAY, Loss.of(true, true, 2_500, 1_500, nothing))
        assertEquals(Loss.SEVERE_DELAY, Loss.of(true, true, 2_501, 1_500, nothing))
        assertEquals(Loss.NEVER, Loss.of(true, true, null, 1_500, nothing))
        assertEquals(Loss.UNKNOWN, Loss.of(true, known = false, completionMs = 1_600, deadlineMs = 1_500, unmodeled = nothing))
    }

    @Test
    fun `something not modeled within the allowed window is not made up for by a later completion`() {
        val from1800 = UnmodeledFrom(listOf(1_800L..1_900L), emptyList())
        assertEquals(Loss.UNKNOWN, Loss.of(notKept = true, known = true, completionMs = 2_000, deadlineMs = 1_500, unmodeled = from1800))
    }

    @Test
    fun `a stage kept before the first thing not modeled stays kept`() {
        val from1800 = UnmodeledFrom(listOf(1_800L..1_900L), emptyList())
        assertNull(Loss.of(notKept = false, known = true, completionMs = 1_400, deadlineMs = 1_500, unmodeled = from1800))
        assertEquals(Loss.UNKNOWN, Loss.of(notKept = false, known = true, completionMs = 1_400, deadlineMs = 1_800, unmodeled = from1800))
    }

    private companion object {
        const val MEDIUM = 2
        const val HIGH = 3
    }
}
