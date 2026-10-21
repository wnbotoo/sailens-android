package com.sailens.guidance.evaluation

import com.sailens.guidance.evaluation.LeadTime.Outcome
import com.sailens.guidance.evaluation.LeadTime.Sample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** det-first plan D1.5: the crossing interval, and the five outcomes kept apart. */
class LeadTimeTest {

    private val ms = 1_000_000L

    /** A fresh valid measurement at [atMs] ms, [m] metres away. */
    private fun s(atMs: Long, m: Float?, pitch: Float? = 15f, lower: Float? = m?.minus(0.4f)) = Sample(atMs * ms, m, lower, pitch)

    private val approach = listOf(s(0, 6f), s(100, 4.5f), s(200, 3.4f), s(300, 2.9f), s(400, 2.0f))

    @Test
    fun `completed before the crossing interval is a positive lead interval`() {
        val r = LeadTime.classify(approach, 3.0f, completedAtNanos = 50 * ms)

        assertEquals(Outcome.BEFORE, r.outcome)
        assertEquals(200 * ms, r.crossing!!.t1Nanos)
        assertEquals(300 * ms, r.crossing!!.t2Nanos)
        assertEquals(150.0, r.leadMinMs!!, 1e-9)
        assertEquals(250.0, r.leadMaxMs!!, 1e-9)
        assertEquals("the lower bound crosses earlier", 200 * ms, r.crossing!!.lowerBoundT2Nanos)
    }

    @Test
    fun `completed after it is a negative lead`() {
        val r = LeadTime.classify(approach, 3.0f, completedAtNanos = 380 * ms)
        assertEquals(Outcome.AFTER, r.outcome)
        assertEquals(-180.0, r.leadMinMs!!, 1e-9)
        assertEquals(-80.0, r.leadMaxMs!!, 1e-9)
    }

    @Test
    fun `completion inside the interval cannot be ordered`() {
        assertEquals(Outcome.ORDER_UNDETERMINED, LeadTime.classify(approach, 3.0f, completedAtNanos = 250 * ms).outcome)
    }

    @Test
    fun `a bracketed crossing never completed is clearly missed`() {
        val r = LeadTime.classify(approach, 3.0f, completedAtNanos = null)
        assertEquals(Outcome.CLEARLY_MISSED, r.outcome)
        assertNull(r.leadMinMs)
    }

    @Test
    fun `no bracketed crossing is unknowable, with the reason`() {
        fun why(samples: List<Sample>) = LeadTime.classify(samples, 3.0f, completedAtNanos = 0).let {
            assertEquals(Outcome.UNKNOWABLE, it.outcome)
            it.reason
        }
        assertEquals("never within 3.0m", why(listOf(s(0, 6f), s(100, 4f))))
        assertEquals("first valid contact already within 3.0m", why(listOf(s(0, 2.5f), s(100, 2f))))
        assertEquals("no valid contact", why(listOf(s(0, null), s(100, null))))
        assertEquals("bracket wider than 1 s", why(listOf(s(0, 4f), s(1_200, 2f))))
        assertEquals("pitch changed by more than 10°", why(listOf(s(0, 4f, pitch = 10f), s(100, 2f, pitch = 25f))))
        assertEquals("no pitch", why(listOf(s(0, 4f, pitch = null), s(100, 2f))))
    }

    @Test
    fun `invalid measurements between do not shorten the bracket`() {
        val samples = listOf(s(0, 4f), s(100, null), s(200, null), s(300, 2.5f))
        val r = LeadTime.classify(samples, 3.0f, completedAtNanos = null)
        assertEquals(0L, r.crossing!!.t1Nanos)
        assertEquals(300 * ms, r.crossing!!.t2Nanos)
    }

    @Test
    fun `an obstacle not measured on this frame gives no sample`() {
        val measured = EvaluationFixtures.obstacle(1, measuredOn = 7, cameraNanos = 123, contactPointM = 2.2f)
        val sample = LeadTime.freshSample(measured, frameSequence = 7, pitchDownDegrees = 14f)!!
        assertEquals(123L, sample.cameraNanos)
        assertEquals(2.2f, sample.validPointM!!, 0f)
        assertNull("repeated evidence is not a measurement", LeadTime.freshSample(measured, frameSequence = 8, pitchDownDegrees = 14f))
        assertNull("no measurement recorded", LeadTime.freshSample(EvaluationFixtures.obstacle(2), frameSequence = 7, pitchDownDegrees = 14f))
    }
}
