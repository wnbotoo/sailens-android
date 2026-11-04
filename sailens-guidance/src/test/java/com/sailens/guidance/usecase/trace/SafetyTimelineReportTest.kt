package com.sailens.guidance.usecase.trace

import com.sailens.guidance.model.trace.AlertRecordCompleteness
import com.sailens.guidance.model.trace.SafetyRecordCompleteness
import com.sailens.guidance.model.trace.SafetyStateTimeline
import com.sailens.guidance.model.trace.TraceReplayParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The safety-state timeline of a trace report, read from the trace's own lines: how long each state held within the
 * window, by elapsed realtime alone, and what the records cannot tell, which is unknown and never given to a
 * neighbouring state. Gaps are found on the records as read, before same-state neighbours are joined.
 */
class SafetyTimelineReportTest {

    private val build = BuildTraceReplayReportUseCase()

    private fun startLine(openedElapsedMs: Long? = 0L, safetyTrace: Boolean = true) = buildString {
        append("""{"type":"session_start","sessionId":"s","startedAt":1,"pipelineMode":"default","targetHardwareProfile":"test"""")
        openedElapsedMs?.let { append(""","openedElapsedMs":$it""") }
        if (safetyTrace) append(""","safetyTrace":true""")
        append("}")
    }

    /** A state record observed at [elapsedMs] (default: when the state began), wall clock [wall]. */
    private fun stateLine(
        seq: Long,
        state: String,
        since: Long,
        elapsedMs: Long = since,
        reasons: List<String> = emptyList(),
        cause: String = "result",
        wall: Long = 1_700_000_000_000L + elapsedMs,
    ) = """{"type":"safety_state","sessionId":"s","seq":$seq,"at":$wall,"elapsedMs":$elapsedMs,"stateSinceElapsedMs":$since,""" +
        """"state":"$state","reasons":[${reasons.joinToString { "\"$it\"" }}],"phase":"running","interrupted":false,"stall":"none",""" +
        """"outputFaults":[],"expectedNavigationChannel":"speech","cause":"$cause"}"""

    private fun summaryLine(closedElapsedMs: Long, states: Long, alerts: Long = 0) =
        """{"type":"session_summary","sessionId":"s","startedAt":1,"completedAt":2,"totalFrames":0,"droppedFrames":0,"totalEvents":0,""" +
            """"blockedFrames":0,"dangerousFrames":0,"avgProcessFrameMs":0.0,"avgTotalPipelineMs":0.0,"avgInferenceMs":0.0,""" +
            """"p95TotalPipelineMs":0,"maxTotalPipelineMs":0,"closedElapsedMs":$closedElapsedMs,"guidanceAlertCount":$alerts,"safetyStateCount":$states}"""

    private fun alertLine(seq: Long, source: String = "vision", kind: String = "fault_notice") =
        """{"type":"guidance_alert","sessionId":"s","seq":$seq,"at":1,"elapsedMs":$seq,"source":"$source","kind":"$kind",""" +
            """"messageKey":"event_camera_blocked","priority":"critical","channel":"speech","result":"accepted"}"""

    private fun timeline(vararg lines: String): SafetyStateTimeline = build(lines.toList()).safetyTimeline

    private fun assertTimeline(
        timeline: SafetyStateTimeline,
        durations: Map<String, Long>,
        unknownMs: Long,
        completeness: SafetyRecordCompleteness,
        missing: Long = 0,
        startKnown: Boolean = true,
        endKnown: Boolean = true,
    ) {
        assertEquals("durations", durations, timeline.durationsMs)
        assertEquals("unknown", unknownMs, timeline.unknownMs)
        assertEquals("completeness", completeness, timeline.completeness)
        assertEquals("missing", missing, timeline.missingRecords)
        assertEquals("start known", startKnown, timeline.windowStartKnown)
        assertEquals("end known", endKnown, timeline.windowEndKnown)
    }

    @Test
    fun `a complete trace - each state from its start to the next, the one from before the window from its opening`() {
        val timeline = timeline(
            startLine(openedElapsedMs = 100),
            stateLine(1, "initializing", since = 40, elapsedMs = 100, cause = "session_start"),
            stateLine(2, "guiding", since = 2_000),
            stateLine(3, "stop_required", since = 5_000, reasons = listOf("camera_obstructed")),
            stateLine(4, "guiding", since = 8_000),
            summaryLine(closedElapsedMs = 10_000, states = 4),
        )

        assertTimeline(
            timeline,
            durations = mapOf("guiding" to 5_000L, "initializing" to 1_900L, "stop_required(camera_obstructed)" to 3_000L),
            unknownMs = 0,
            completeness = SafetyRecordCompleteness.COMPLETE,
        )
        assertFalse(timeline.endedOnError)
    }

    /** True states 0-10 s guiding, 10-20 s stop required, 20-30 s guiding; only the stop-required record is lost. */
    @Test
    fun `a lost transition between two records of the same state is unknown, not joined into one stretch`() {
        val timeline = timeline(
            startLine(openedElapsedMs = 0),
            stateLine(1, "guiding", since = 0, cause = "session_start"),
            // seq 2: stop_required since 10 000, lost
            stateLine(3, "guiding", since = 20_000),
            summaryLine(closedElapsedMs = 30_000, states = 3),
        )

        assertTimeline(
            timeline,
            durations = mapOf("guiding" to 10_000L),
            unknownMs = 20_000,
            completeness = SafetyRecordCompleteness.INCOMPLETE,
            missing = 1,
        )
    }

    @Test
    fun `a lost record before a different state leaves unknown from the record before it to that state's start`() {
        val timeline = timeline(
            startLine(openedElapsedMs = 0),
            stateLine(1, "guiding", since = 0, cause = "session_start"),
            stateLine(3, "degraded", since = 15_000, reasons = listOf("ground_unrecognized")),
            summaryLine(closedElapsedMs = 30_000, states = 3),
        )

        assertTimeline(
            timeline,
            durations = mapOf("degraded(ground_unrecognized)" to 15_000L),
            unknownMs = 15_000,
            completeness = SafetyRecordCompleteness.INCOMPLETE,
            missing = 1,
        )
    }

    @Test
    fun `a lost record that only changed a fact leaves nothing unknown`() {
        val timeline = timeline(
            startLine(openedElapsedMs = 0),
            stateLine(1, "guiding", since = 0, cause = "session_start"),
            // seq 2: guiding since 0, an output fact changed at 4 000, lost
            stateLine(3, "guiding", since = 0, elapsedMs = 7_000, cause = "output"),
            summaryLine(closedElapsedMs = 10_000, states = 3),
        )

        assertTimeline(
            timeline,
            durations = mapOf("guiding" to 10_000L),
            unknownMs = 0,
            completeness = SafetyRecordCompleteness.INCOMPLETE,
            missing = 1,
        )
    }

    @Test
    fun `records lost at the tail leave unknown from the last one kept to the closing`() {
        val timeline = timeline(
            startLine(openedElapsedMs = 0),
            stateLine(1, "initializing", since = 0, cause = "session_start"),
            stateLine(2, "guiding", since = 3_000),
            summaryLine(closedElapsedMs = 10_000, states = 4),
        )

        assertTimeline(
            timeline,
            durations = mapOf("initializing" to 3_000L),
            unknownMs = 7_000,
            completeness = SafetyRecordCompleteness.INCOMPLETE,
            missing = 2,
        )
    }

    @Test
    fun `without the session's first state, the time from the opening to the first state kept is unknown`() {
        val timeline = timeline(
            startLine(openedElapsedMs = 1_000),
            // seq 1: the state kept from before the opening, lost
            stateLine(2, "guiding", since = 3_000),
            summaryLine(closedElapsedMs = 10_000, states = 2),
        )

        assertTimeline(
            timeline,
            durations = mapOf("guiding" to 7_000L),
            unknownMs = 2_000,
            completeness = SafetyRecordCompleteness.INCOMPLETE,
            missing = 1,
        )
    }

    @Test
    fun `without the session start, the window opens where the session's first state was written`() {
        val timeline = timeline(
            stateLine(1, "initializing", since = 40, elapsedMs = 1_000, cause = "session_start"),
            stateLine(2, "guiding", since = 3_000),
            summaryLine(closedElapsedMs = 10_000, states = 2),
        )

        assertTimeline(
            timeline,
            durations = mapOf("guiding" to 7_000L, "initializing" to 2_000L),
            unknownMs = 0,
            completeness = SafetyRecordCompleteness.COMPLETE,
        )
    }

    @Test
    fun `without the session start and its first state, counting starts at the first record kept`() {
        val timeline = timeline(
            stateLine(2, "guiding", since = 3_000, elapsedMs = 3_500),
            stateLine(3, "degraded", since = 6_000, reasons = listOf("speech_not_ready")),
            summaryLine(closedElapsedMs = 10_000, states = 3),
        )

        assertTimeline(
            timeline,
            durations = mapOf("degraded(speech_not_ready)" to 4_000L, "guiding" to 2_500L),
            unknownMs = 0,
            completeness = SafetyRecordCompleteness.INCOMPLETE,
            missing = 1,
            startKnown = false,
        )
    }

    @Test
    fun `without the summary, counting stops at the last record kept, and the last ones may be missing`() {
        val timeline = timeline(
            startLine(openedElapsedMs = 0),
            stateLine(1, "initializing", since = 0, cause = "session_start"),
            stateLine(2, "guiding", since = 3_000),
            stateLine(3, "guiding", since = 3_000, elapsedMs = 8_000, cause = "output"),
        )

        assertTimeline(
            timeline,
            durations = mapOf("guiding" to 5_000L, "initializing" to 3_000L),
            unknownMs = 0,
            completeness = SafetyRecordCompleteness.POSSIBLY_INCOMPLETE,
            endKnown = false,
        )
    }

    @Test
    fun `with every safety record lost, the whole window is unknown`() {
        val timeline = timeline(startLine(openedElapsedMs = 0), summaryLine(closedElapsedMs = 10_000, states = 3))

        assertTimeline(timeline, durations = emptyMap(), unknownMs = 10_000, completeness = SafetyRecordCompleteness.INCOMPLETE, missing = 3)
    }

    @Test
    fun `a lost alert marks the alerts incomplete and leaves the state durations alone`() {
        val report = build(
            listOf(
                startLine(openedElapsedMs = 0),
                stateLine(1, "guiding", since = 0, cause = "session_start"),
                alertLine(2),
                alertLine(3),
                summaryLine(closedElapsedMs = 10_000, states = 1, alerts = 3),
            ),
        )

        assertEquals(AlertRecordCompleteness.INCOMPLETE, report.guidanceAlertCompleteness)
        assertEquals(1L, report.guidanceAlertsMissing)
        assertTimeline(report.safetyTimeline, durations = mapOf("guiding" to 10_000L), unknownMs = 0, completeness = SafetyRecordCompleteness.COMPLETE)
    }

    @Test
    fun `an alert from before the session opened is in no trace and not counted, so the window's alerts are complete`() {
        // The ledger drops an alert while no session is attached and does not count it: the session's alerts start at 1.
        val report = build(
            listOf(
                startLine(openedElapsedMs = 0),
                stateLine(1, "initializing", since = 0, cause = "session_start"),
                alertLine(1, source = "system", kind = "interrupted"),
                summaryLine(closedElapsedMs = 10_000, states = 1, alerts = 1),
            ),
        )

        assertEquals(AlertRecordCompleteness.COMPLETE, report.guidanceAlertCompleteness)
        assertEquals(mapOf("interrupted" to 1), report.guidanceAlertCounts)
    }

    @Test
    fun `durations are elapsed realtime - a wall clock that jumps back changes nothing`() {
        val steady = timeline(
            startLine(openedElapsedMs = 0),
            stateLine(1, "guiding", since = 0, cause = "session_start"),
            stateLine(2, "stop_required", since = 4_000, reasons = listOf("too_dark")),
            stateLine(3, "guiding", since = 6_000),
            summaryLine(closedElapsedMs = 10_000, states = 3),
        )
        val jumped = timeline(
            startLine(openedElapsedMs = 0),
            stateLine(1, "guiding", since = 0, cause = "session_start", wall = 1_700_000_100_000L),
            stateLine(2, "stop_required", since = 4_000, reasons = listOf("too_dark"), wall = 1_699_999_000_000L),
            stateLine(3, "guiding", since = 6_000, wall = 1_600_000_000_000L),
            summaryLine(closedElapsedMs = 10_000, states = 3),
        )

        assertEquals(mapOf("guiding" to 8_000L, "stop_required(too_dark)" to 2_000L), steady.durationsMs)
        assertEquals(steady, jumped)
    }

    @Test
    fun `a stall during initialisation is its own stretch between initialising and guiding`() {
        val timeline = timeline(
            startLine(openedElapsedMs = 500),
            stateLine(1, "initializing", since = 0, elapsedMs = 500, cause = "session_start"),
            stateLine(2, "stop_required", since = 20_000, reasons = listOf("startup_stalled"), cause = "poll"),
            stateLine(3, "guiding", since = 23_000),
            summaryLine(closedElapsedMs = 30_000, states = 3),
        )

        assertTimeline(
            timeline,
            durations = mapOf("guiding" to 7_000L, "initializing" to 19_500L, "stop_required(startup_stalled)" to 3_000L),
            unknownMs = 0,
            completeness = SafetyRecordCompleteness.COMPLETE,
        )
    }

    @Test
    fun `records that only change a fact neither restart the state nor count it twice`() {
        val timeline = timeline(
            startLine(openedElapsedMs = 0),
            stateLine(1, "guiding", since = 0, cause = "session_start"),
            stateLine(2, "guiding", since = 0, elapsedMs = 3_000, cause = "output"),
            stateLine(3, "guiding", since = 0, elapsedMs = 5_000, cause = "result"),
            summaryLine(closedElapsedMs = 10_000, states = 3),
        )

        assertTimeline(timeline, durations = mapOf("guiding" to 10_000L), unknownMs = 0, completeness = SafetyRecordCompleteness.COMPLETE)
    }

    @Test
    fun `a state whose reasons change is a new state`() {
        val timeline = timeline(
            startLine(openedElapsedMs = 0),
            stateLine(1, "degraded", since = 0, reasons = listOf("ground_unrecognized"), cause = "session_start"),
            stateLine(2, "degraded", since = 4_000, reasons = listOf("ground_unrecognized", "speech_unavailable"), cause = "output"),
            summaryLine(closedElapsedMs = 10_000, states = 2),
        )

        assertEquals(
            mapOf("degraded(ground_unrecognized)" to 4_000L, "degraded(ground_unrecognized,speech_unavailable)" to 6_000L),
            timeline.durationsMs,
        )
    }

    @Test
    fun `a trace from before safety states were written has no timeline`() {
        val report = build(listOf(startLine(openedElapsedMs = 0, safetyTrace = false), alertLine(1)))

        assertEquals(SafetyStateTimeline.NOT_RECORDED, report.safetyTimeline)
    }

    @Test
    fun `a trace whose session start was lost still has a timeline when its system alerts or states are there`() {
        assertEquals(
            SafetyRecordCompleteness.POSSIBLY_INCOMPLETE,
            timeline(alertLine(1, source = "system", kind = "stalled")).completeness,
        )
    }

    @Test
    fun `an analysis flow that ended on an error is said so - its failure comes after the trace closed`() {
        val timeline = timeline(
            startLine(openedElapsedMs = 0),
            stateLine(1, "guiding", since = 0, cause = "session_start"),
            """{"type":"error","sessionId":"s","stage":"navigation_flow","exception":"GuidancePipelineFailedException","message":"30 frames"}""",
            summaryLine(closedElapsedMs = 10_000, states = 1),
        )

        assertTrue(timeline.endedOnError)
        assertEquals(mapOf("guiding" to 10_000L), timeline.durationsMs)
    }

    @Test
    fun `an illegal safety state is rejected with its line number`() {
        val illegal = listOf(
            stateLine(1, "paused", since = 0),
            stateLine(1, "guiding", since = 0).replace("\"phase\":\"running\"", "\"phase\":\"walking\""),
            stateLine(1, "guiding", since = 0).replace("\"stall\":\"none\"", "\"stall\":\"maybe\""),
            stateLine(1, "guiding", since = 0).replace("\"expectedNavigationChannel\":\"speech\"", "\"expectedNavigationChannel\":\"telepathy\""),
            stateLine(1, "guiding", since = 0, cause = "whim"),
            stateLine(1, "guiding", since = 0).replace("\"outputFaults\"", "\"judgementReasons\":[\"too_dark\"],\"outputFaults\""),
            stateLine(1, "guiding", since = 0).replace("\"outputFaults\"", "\"judgement\":\"half\",\"outputFaults\""),
            stateLine(1, "guiding", since = 0).replace(",\"stateSinceElapsedMs\":0", ""),
        )
        for (line in illegal) {
            val error = assertThrows(line, IllegalArgumentException::class.java) { TraceReplayParser.parse(listOf(startLine(), line)) }
            assertTrue("${error.message}", error.message.orEmpty().contains("line 2"))
        }
    }

    @Test
    fun `every field a safety state record carries is read back`() {
        val line = """{"type":"safety_state","sessionId":"s","seq":7,"at":1700000000123,"elapsedMs":5000,"stateSinceElapsedMs":4000,""" +
            """"state":"stop_required","reasons":["camera_obstructed"],"phase":"running","interrupted":false,"stall":"none",""" +
            """"judgement":"unqualified","judgementReasons":["camera_obstructed","ground_unrecognized"],"outputFaults":["speech_unavailable"],""" +
            """"expectedNavigationChannel":"haptics_only","cause":"result"}"""
        val state = TraceReplayParser.parse(listOf(line)).safetyStates.single()

        assertEquals(7L, state.seq)
        assertEquals(1_700_000_000_123L, state.at)
        assertEquals(5_000L, state.elapsedMs)
        assertEquals(4_000L, state.stateSinceElapsedMs)
        assertEquals("stop_required(camera_obstructed)", state.stateLabel)
        assertEquals("unqualified", state.judgement)
        assertEquals(listOf("camera_obstructed", "ground_unrecognized"), state.judgementReasons)
        assertEquals(listOf("speech_unavailable"), state.outputFaults)
        assertEquals("haptics_only", state.expectedNavigationChannel)
        assertEquals("result", state.cause)
        assertTrue(TraceReplayParser.parse(listOf(startLine())).metadata!!.safetyTrace)
        assertFalse(TraceReplayParser.parse(listOf(startLine(safetyTrace = false))).metadata!!.safetyTrace)
        assertEquals(4L, TraceReplayParser.parse(listOf(summaryLine(closedElapsedMs = 1, states = 4))).summary!!.safetyStateCount)
    }
}
