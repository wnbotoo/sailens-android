package com.sailens.guidance.trace

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sailens.guidance.model.trace.PromptOutcomeTrace
import com.sailens.guidance.model.trace.PromptRevokeReasons
import com.sailens.guidance.model.trace.TraceReplayParser
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The encoder writes with Android's org.json, which is a stub on the JVM, so the round trip through
 * the parser is proven here, on a device.
 */
@RunWith(AndroidJUnit4::class)
class PromptOutcomeEncodingTest {

    @Test
    fun deliveredAndRevokedOutcomesSurviveTheRoundTrip() {
        val delivered = PromptOutcomeTrace(
            sessionId = "s1",
            eventId = "e1",
            sourceSequenceNumber = 12,
            messageKey = "event_blocked",
            category = "blocked",
            priority = "critical",
            deliveredAt = 1_700_000_000_100,
            speechEnabled = true,
            screenReaderActive = false,
            hapticsEnabled = true,
        )
        val revoked = delivered.copy(
            eventId = "e2",
            deliveredAt = null,
            revokedAt = 1_700_000_000_150,
            revokeReason = PromptRevokeReasons.OUTPUT_REFUSED,
            hapticsEnabled = false,
        )

        val lines = listOf(delivered, revoked).map { TraceJsonEncoder.encodePromptOutcome(it).toString() }
        val parsed = TraceReplayParser.parse(lines).promptOutcomes

        assertEquals(listOf(delivered, revoked), parsed)
    }
}
