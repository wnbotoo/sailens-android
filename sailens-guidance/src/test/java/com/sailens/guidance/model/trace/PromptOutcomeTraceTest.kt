package com.sailens.guidance.model.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `prompt_outcome` trace record: what became of the one prompt a frame offered. The JSON lines
 * here are the format TraceJsonEncoder.encodePromptOutcome writes (org.json is a stub on the JVM, so
 * the encoder itself is exercised on device). The invariants live on [PromptOutcomeTrace], so the
 * writer cannot produce what the parser would reject.
 */
class PromptOutcomeTraceTest {

    @Test
    fun `a delivered and a revoked prompt parse`() {
        val session = TraceReplayParser.parse(
            listOf(
                line(""""deliveredAt":1700000000100,"deliveredVia":["speech","haptics"]"""),
                line(""""revokedAt":1700000000150,"revokeReason":"waiting_for_description"""", eventId = "e2"),
            ),
        )

        val (delivered, revoked) = session.promptOutcomes
        assertEquals("e1", delivered.eventId)
        assertEquals(12L, delivered.sourceSequenceNumber)
        assertEquals(1_700_000_000_100L, delivered.deliveredAt)
        assertEquals(listOf("speech", "haptics"), delivered.deliveredVia)
        assertNull(delivered.revokedAt)
        assertNull(delivered.revokeReason)

        assertEquals(PromptRevokeReasons.WAITING_FOR_DESCRIPTION, revoked.revokeReason)
        assertEquals(1_700_000_000_150L, revoked.revokedAt)
        assertEquals(emptyList<String>(), revoked.deliveredVia)
    }

    @Test
    fun `every illegal combination is rejected with its line number`() {
        val illegal = listOf(
            "neither timestamp" to "",
            "both timestamps" to """"deliveredAt":1,"revokedAt":2,"deliveredVia":["speech"]""",
            "revoked without a reason" to """"revokedAt":1""",
            "revoked with an unknown reason" to """"revokedAt":1,"revokeReason":"bored"""",
            "revoked yet reached a channel" to """"revokedAt":1,"revokeReason":"output_refused","deliveredVia":["speech"]""",
            "delivered with a revoke reason" to """"deliveredAt":1,"revokeReason":"output_refused","deliveredVia":["speech"]""",
            "delivered through no channel" to """"deliveredAt":1""",
            "delivered through an unknown channel" to """"deliveredAt":1,"deliveredVia":["telepathy"]""",
        )

        for ((case, fields) in illegal) {
            val error = assertThrows(case, IllegalArgumentException::class.java) {
                TraceReplayParser.parse(listOf(line(fields)))
            }
            assertTrue("$case: ${error.message}", error.message!!.contains("line 1"))
        }
    }

    @Test
    fun `the writer cannot build what the parser rejects`() {
        assertThrows(IllegalArgumentException::class.java) {
            outcome(deliveredAt = 1, deliveredVia = emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            outcome(revokedAt = 1, revokeReason = null)
        }
    }

    @Test
    fun `traces recorded before outcomes existed parse with none`() {
        val session = TraceReplayParser.parse(
            listOf(
                """{"type":"session_start","sessionId":"s1","startedAt":1000,"pipelineMode":"default","targetHardwareProfile":"x"}""",
            ),
        )

        assertTrue(session.promptOutcomes.isEmpty())
    }

    private fun line(outcomeFields: String, eventId: String = "e1"): String {
        val fields = if (outcomeFields.isEmpty()) "" else "$outcomeFields,"
        return """{"type":"prompt_outcome","sessionId":"s1","eventId":"$eventId","sourceSequenceNumber":12,""" +
            """"messageKey":"event_blocked","category":"blocked","priority":"critical",$fields""" +
            """"speechEnabled":true,"screenReaderActive":false,"hapticsEnabled":true}"""
    }

    private fun outcome(
        deliveredAt: Long? = null,
        revokedAt: Long? = null,
        revokeReason: String? = null,
        deliveredVia: List<String> = emptyList(),
    ) = PromptOutcomeTrace(
        eventId = "e1",
        sourceSequenceNumber = 1,
        messageKey = "event_blocked",
        category = "blocked",
        priority = "high",
        deliveredAt = deliveredAt,
        revokedAt = revokedAt,
        revokeReason = revokeReason,
        deliveredVia = deliveredVia,
        speechEnabled = true,
        screenReaderActive = false,
        hapticsEnabled = true,
    )
}
