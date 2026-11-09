package com.sailens.guidance.model.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The prompt hold in the trace: `safety_state.hold` and `holdReleased`, `frame.receivedElapsedMs` and
 * `session_start.holdTrace`. The JSON lines are the format TraceJsonEncoder writes (org.json is a stub on the JVM, so
 * the encoder itself is exercised on device); `scripts/capture/hold_releases.py` reads the same names.
 */
class HoldTraceTest {

    private fun safetyState(seq: Long, fields: String) =
        """{"type":"safety_state","sessionId":"s","seq":$seq,"at":1700000000000,"elapsedMs":11530,"stateSinceElapsedMs":11530,""" +
            """"state":"guiding","reasons":[],"phase":"running","interrupted":false,"stall":"none","judgement":"qualified",""" +
            """"judgementReasons":[],"outputFaults":[],"expectedNavigationChannel":"speech","cause":"result"$fields}"""

    @Test
    fun `a hold and a release parse, and a record written before them has neither`() {
        val recovering = safetyState(
            8,
            ""","hold":{"cause":"camera_obstructed","sinceElapsedMs":10000,"stopping":false,"freshResults":2,"freshSpanMs":100,""" +
                """"firstFreshReceivedElapsedMs":11000,"lastFreshReceivedElapsedMs":11100,"receiptFallbacks":1,"lastFreshSequenceNumber":42}""",
        ).replace("\"state\":\"guiding\",\"reasons\":[]", "\"state\":\"stop_required\",\"reasons\":[\"camera_obstructed\"]")
        val released = safetyState(
            9,
            ""","holdReleased":{"cause":"camera_obstructed","sinceElapsedMs":10000,"freshResults":5,"firstFreshReceivedElapsedMs":11000,""" +
                """"lastFreshReceivedElapsedMs":11500,"freshSpanMs":500,"receiptFallbacks":1,"sourceSequenceNumber":45}""",
        )
        val older = safetyState(10, "")

        val (hold, release, none) = TraceReplayParser.parse(listOf(recovering, released, older)).safetyStates
        assertEquals(HoldTrace("camera_obstructed", 10_000, stopping = false, freshResults = 2, freshSpanMs = 100, firstFreshReceivedElapsedMs = 11_000, lastFreshReceivedElapsedMs = 11_100, receiptFallbacks = 1, lastFreshSequenceNumber = 42), hold.hold)
        assertNull(hold.holdReleased)
        assertEquals(HoldReleaseTrace("camera_obstructed", 10_000, 5, 11_000, 11_500, 500, 1, 45), release.holdReleased)
        assertNull(release.hold)
        assertNull(none.hold)
        assertNull(none.holdReleased)
    }

    @Test
    fun `a stopping hold has no progress fields`() {
        val stopping = safetyState(3, ""","hold":{"cause":"lifecycle","sinceElapsedMs":8000,"stopping":true,"freshResults":0,"freshSpanMs":0,"receiptFallbacks":0}""")
            .replace("\"state\":\"guiding\",\"reasons\":[]", "\"state\":\"interrupted\",\"reasons\":[\"lifecycle\"]")
        val hold = TraceReplayParser.parse(listOf(stopping)).safetyStates.single().hold!!
        assertTrue(hold.stopping)
        assertNull(hold.firstFreshReceivedElapsedMs)
        assertNull(hold.lastFreshSequenceNumber)
    }

    @Test
    fun `an illegal hold is rejected with its line number`() {
        val illegal = listOf(
            "unknown cause" to ""","hold":{"cause":"rain","sinceElapsedMs":1,"stopping":true,"freshResults":0,"freshSpanMs":0}""",
            "a release with a hold" to ""","hold":{"cause":"lifecycle","sinceElapsedMs":1,"stopping":true,"freshResults":0,"freshSpanMs":0},""" +
                """"holdReleased":{"cause":"lifecycle","sinceElapsedMs":1,"freshResults":3,"firstFreshReceivedElapsedMs":2,"lastFreshReceivedElapsedMs":600,"freshSpanMs":598,"receiptFallbacks":0,"sourceSequenceNumber":4}""",
        )
        for ((case, fields) in illegal) {
            val error = assertThrows(case, IllegalArgumentException::class.java) { TraceReplayParser.parse(listOf(safetyState(1, fields))) }
            assertTrue("$case: ${error.message}", error.message!!.contains("line 1"))
        }
        val notByAResult = safetyState(1, ""","holdReleased":{"cause":"lifecycle","sinceElapsedMs":1,"freshResults":3,"firstFreshReceivedElapsedMs":2,"lastFreshReceivedElapsedMs":600,"freshSpanMs":598,"receiptFallbacks":0,"sourceSequenceNumber":4}""")
            .replace("\"cause\":\"result\"", "\"cause\":\"poll\"")
        assertThrows(IllegalArgumentException::class.java) { TraceReplayParser.parse(listOf(notByAResult)) }
    }

    private val frameBase =
        """"type":"frame","sessionId":"s","sequenceNumber":3,"frameTimestamp":1,"frameWidth":4,"frameHeight":4,""" +
            """"droppedFramesSinceLast":0,"processFrameMs":1,"inferenceMs":1,"analyzeSceneMs":1,"decideEventsMs":1,""" +
            """"totalPipelineMs":3,"obstacleCount":0,"eventCount":0,"isBlocked":false,"isNarrowing":false,"isRoadDangerous":false,"messageKeys":[]"""

    @Test
    fun `a frame's receipt parses, and a frame without one has none`() {
        val (received, unknown) = TraceReplayParser.parse(listOf("""{$frameBase,"receivedElapsedMs":11100}""", """{$frameBase}""")).frames
        assertEquals(11_100L, received.receivedElapsedMs)
        assertNull(unknown.receivedElapsedMs)
    }

    @Test
    fun `a session that records holds says so, and an older one does not`() {
        val start = """{"type":"session_start","sessionId":"s","startedAt":1,"pipelineMode":"default","targetHardwareProfile":"x","safetyTrace":true"""
        assertTrue(TraceReplayParser.parse(listOf("""$start,"holdTrace":true}""")).metadata!!.holdTrace)
        assertFalse(TraceReplayParser.parse(listOf("""$start}""")).metadata!!.holdTrace)
    }
}
