package com.sailens.guidance.evaluation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * What the reflection reads is what the frozen class holds; building it here also stops the test
 * compiling if its constructor changes, and a renamed field throws from [FrozenSpeakerInputs].
 */
class FrozenSpeakerInputsTest {

    @Test
    fun `reads back each field of the frozen inputs, by reference`() {
        val offers = listOf(PromptPolicyCaptureEvaluation.OfferInput(1, "a", 2, 3, "event_blocked" to false))
        val queued = listOf(SpeakerModel.Queued(4, "n", null, 5))
        val actual = mapOf("a" to SpeakerModel.Actual(accepted = true))
        val excluded = mapOf("a" to "x2 evidence conflict")
        val windows = listOf(1L..2L to "x5 notice or description speaking")
        val busy = listOf(1L..2L)

        val read = FrozenSpeakerInputs(
            PromptPolicyCaptureEvaluation.SpeakerInputs(offers, queued, actual, excluded, windows, busy, emptyList(), "label"),
        )

        assertSame(offers, read.offers)
        assertSame(queued, read.queued)
        assertSame(actual, read.actual)
        assertSame(excluded, read.excluded)
        assertSame(windows, read.windows)
        assertSame(busy, read.deviceBusy)
        assertEquals("a", read.offers.single().id)
    }
}
