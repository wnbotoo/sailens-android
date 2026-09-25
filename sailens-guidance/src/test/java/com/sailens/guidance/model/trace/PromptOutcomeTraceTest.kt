package com.sailens.guidance.model.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `prompt_outcome` trace record: what became of the one prompt a frame offered. The JSON lines
 * here are the format TraceJsonEncoder.encodePromptOutcome writes (org.json is a stub on the JVM, so
 * the encoder itself is exercised on device).
 */
class PromptOutcomeTraceTest {

    @Test
    fun `a delivered and a revoked prompt parse alongside their frames`() {
        val session = TraceReplayParser.parse(
            listOf(
                """{"type":"prompt_outcome","sessionId":"s1","eventId":"e1","sourceSequenceNumber":12,"messageKey":"event_blocked","category":"blocked","priority":"critical","deliveredAt":1700000000100,"speechEnabled":true,"screenReaderActive":false,"hapticsEnabled":true}""",
                """{"type":"prompt_outcome","sessionId":"s1","eventId":"e2","sourceSequenceNumber":13,"messageKey":"event_traffic_light","category":"traffic_light","priority":"low","revokedAt":1700000000150,"revokeReason":"waiting_for_description","speechEnabled":true,"screenReaderActive":false,"hapticsEnabled":false}""",
            ),
        )

        val (delivered, revoked) = session.promptOutcomes
        assertEquals("e1", delivered.eventId)
        assertEquals(12L, delivered.sourceSequenceNumber)
        assertEquals(1_700_000_000_100L, delivered.deliveredAt)
        assertNull(delivered.revokedAt)
        assertNull(delivered.revokeReason)
        assertTrue(delivered.hapticsEnabled)

        assertEquals(PromptRevokeReasons.WAITING_FOR_DESCRIPTION, revoked.revokeReason)
        assertEquals(1_700_000_000_150L, revoked.revokedAt)
        assertNull(revoked.deliveredAt)
    }

    @Test
    fun `an outcome must be exactly one of delivered or revoked`() {
        val neither = """{"type":"prompt_outcome","sessionId":"s1","eventId":"e1","sourceSequenceNumber":1,"messageKey":"event_blocked","category":"blocked","priority":"high","speechEnabled":true,"screenReaderActive":false,"hapticsEnabled":true}"""
        val both = """{"type":"prompt_outcome","sessionId":"s1","eventId":"e1","sourceSequenceNumber":1,"messageKey":"event_blocked","category":"blocked","priority":"high","deliveredAt":1,"revokedAt":2,"speechEnabled":true,"screenReaderActive":false,"hapticsEnabled":true}"""

        assertThrows(IllegalArgumentException::class.java) { TraceReplayParser.parse(listOf(neither)) }
        assertThrows(IllegalArgumentException::class.java) { TraceReplayParser.parse(listOf(both)) }
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
}
