package com.sailens.guidance.evaluation

import com.sailens.guidance.evaluation.SpeakerModel.Offer
import com.sailens.guidance.evaluation.SpeakerModel.Queued
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class NominalSpeakerTest {

    @Test
    fun `step by step gives what one run over all the inputs gives, for every sentence that has ended`() {
        val random = Random(7)
        repeat(300) {
            var t = 0L
            val offers = List(5 + random.nextInt(40)) { i ->
                t += random.nextInt(2_500).toLong()
                Offer(t, "o$i", 1 + random.nextInt(3), t + 500 + random.nextInt(3_000), 1_000L + random.nextInt(1_500))
            }
            val queued = if (random.nextBoolean()) listOf(Queued(offers[offers.size / 2].atMs, "q", null, 1_500)) else emptyList()
            val speaker = NominalSpeaker(queued)
            val settled = LinkedHashMap<String, SpeakerModel.Prediction>()
            val accepted = HashMap<String, Boolean>()
            for (o in offers) {
                speaker.settled(o.atMs).forEach { settled[it.id] = it }
                accepted[o.id] = speaker.offer(o).accepted
            }
            val whole = SpeakerModel().run(offers + queued).associateBy { it.id }
            for ((id, p) in settled) assertEquals(whole[id], p)
            for (o in offers) assertEquals(whole.getValue(o.id).accepted, accepted[o.id])
            assertEquals(whole.values.toSet(), speaker.finish().toSet())
        }
    }

    @Test
    fun `an offer says what it cut off, and a sentence past its end is no longer speaking`() {
        val speaker = NominalSpeaker()
        assertTrue(speaker.offer(Offer(0, "a", 1, 5_000, 1_000)).accepted)
        val higher = speaker.offer(Offer(500, "b", 2, 5_000, 1_000))
        assertEquals(listOf("a"), higher.cut)
        val refused = speaker.offer(Offer(600, "c", 2, 5_000, 1_000))
        assertFalse(refused.accepted)
        assertEquals("outranked", refused.reason)
        assertEquals(listOf("a"), speaker.settled(600).map { it.id })
        assertEquals(emptyList<String>(), speaker.settled(600).map { it.id })
        assertEquals(listOf("b"), speaker.settled(1_500).map { it.id })
    }
}
