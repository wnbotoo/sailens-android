package com.sailens.guidance.model.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `guidance_alert` record and the qualification fields of `frame`, `session_start` and `session_summary`. The JSON
 * lines here are the format TraceJsonEncoder writes (org.json is a stub on the JVM, so the encoder itself is exercised
 * on device). The invariants live on [GuidanceAlertTrace], so the writer cannot produce what the parser would reject.
 */
class GuidanceAlertTraceTest {

    private fun alert(fields: String) =
        """{"type":"guidance_alert","sessionId":"s","seq":1,"at":1700000000000,"elapsedMs":5000,"source":"vision","priority":"critical",$fields}"""

    @Test
    fun `a sentence and a rhythm parse, in file order`() {
        val session = TraceReplayParser.parse(
            listOf(
                alert(""""kind":"fault_notice","messageKey":"event_camera_blocked","channel":"speech","result":"accepted""""),
                alert(""""kind":"fault_rhythm","haptic":"vision_unreliable","channel":"haptics","result":"played"""").replace("\"seq\":1", "\"seq\":2"),
            ),
        )

        val (notice, rhythm) = session.guidanceAlerts
        assertEquals(1L, notice.seq)
        assertEquals("event_camera_blocked", notice.messageKey)
        assertNull(notice.haptic)
        assertEquals(GuidanceAlertResults.ACCEPTED, notice.result)
        assertEquals(2L, rhythm.seq)
        assertEquals("vision_unreliable", rhythm.haptic)
        assertNull(rhythm.messageKey)
        assertEquals(5_000L, rhythm.elapsedMs)
    }

    @Test
    fun `every illegal record is rejected with its line number`() {
        val illegal = listOf(
            "neither sentence nor rhythm" to """"kind":"fault_notice","channel":"speech","result":"accepted"""",
            "both" to """"kind":"fault_notice","messageKey":"k","haptic":"h","channel":"speech","result":"accepted"""",
            "unknown kind" to """"kind":"chime","messageKey":"k","channel":"speech","result":"accepted"""",
            "unknown channel" to """"kind":"fault_notice","messageKey":"k","channel":"telepathy","result":"accepted"""",
            "unknown result" to """"kind":"fault_notice","messageKey":"k","channel":"speech","result":"maybe"""",
            "no result" to """"kind":"fault_notice","messageKey":"k","channel":"speech"""",
        )
        for ((case, fields) in illegal) {
            val error = assertThrows(case, IllegalArgumentException::class.java) { TraceReplayParser.parse(listOf(alert(fields))) }
            assertTrue("$case: ${error.message}", error.message!!.contains("line 1"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TraceReplayParser.parse(listOf(alert(""""kind":"fault_notice","messageKey":"k","channel":"speech","result":"accepted"""").replace("\"vision\"", "\"oracle\"")))
        }
    }

    private val frameBase =
        """"type":"frame","sessionId":"s","sequenceNumber":3,"frameTimestamp":1,"frameWidth":4,"frameHeight":4,""" +
            """"droppedFramesSinceLast":0,"processFrameMs":1,"inferenceMs":1,"analyzeSceneMs":1,"decideEventsMs":1,""" +
            """"totalPipelineMs":3,"obstacleCount":0,"eventCount":0,"isBlocked":false,"isNarrowing":false,"isRoadDangerous":false,"messageKeys":[]"""

    @Test
    fun `the frame's qualification fields parse, and a frame written before them has none`() {
        val newer = """{$frameBase,"frameQuality":"ok","frameQualityClassified":"obstructed","qualification":"unqualified",""" +
            """"qualificationReasons":["too_dark","ground_unrecognized"],"receiptToDecisionMs":42.5,""" +
            """"capabilities":{"orientation":{"status":"degraded","issues":["heading_carried_over"]},"camera_geometry":{"status":"available","issues":[]}}}"""
        val older = """{$frameBase}"""

        val (frame, old) = TraceReplayParser.parse(listOf(newer, older)).frames
        assertEquals("ok", frame.frameQuality)
        assertEquals("obstructed", frame.frameQualityClassified)
        assertEquals("unqualified", frame.qualification)
        assertEquals(listOf("too_dark", "ground_unrecognized"), frame.qualificationReasons)
        assertEquals(42.5, frame.receiptToDecisionMs!!, 0.0)
        assertEquals(
            mapOf(
                "orientation" to CapabilityTrace("degraded", listOf("heading_carried_over")),
                "camera_geometry" to CapabilityTrace("available"),
            ),
            frame.capabilities,
        )

        assertNull(old.frameQuality)
        assertNull(old.frameQualityClassified)
        assertNull(old.qualification)
        assertEquals(emptyList<String>(), old.qualificationReasons)
        assertEquals(emptyMap<String, CapabilityTrace>(), old.capabilities)
        assertNull(old.receiptToDecisionMs)
    }

    @Test
    fun `the session's window and alert count parse, and older sessions have none`() {
        val start = """{"type":"session_start","sessionId":"s","startedAt":1,"pipelineMode":"default","targetHardwareProfile":"t","openedElapsedMs":900}"""
        val summary = """{"type":"session_summary","sessionId":"s","startedAt":1,"completedAt":2,"totalFrames":0,"droppedFrames":0,""" +
            """"totalEvents":0,"blockedFrames":0,"dangerousFrames":0,"avgProcessFrameMs":0.0,"avgTotalPipelineMs":0.0,"avgInferenceMs":0.0,""" +
            """"p95TotalPipelineMs":0,"maxTotalPipelineMs":0,"closedElapsedMs":9900,"guidanceAlertCount":4}"""
        val session = TraceReplayParser.parse(listOf(start, summary))
        assertEquals(900L, session.metadata!!.openedElapsedMs)
        assertEquals(9_900L, session.summary!!.closedElapsedMs)
        assertEquals(4L, session.summary!!.guidanceAlertCount)

        val older = TraceReplayParser.parse(listOf(start.replace(",\"openedElapsedMs\":900", ""), summary.replace(",\"closedElapsedMs\":9900,\"guidanceAlertCount\":4", "")))
        assertNull(older.metadata!!.openedElapsedMs)
        assertNull(older.summary!!.closedElapsedMs)
        assertNull(older.summary!!.guidanceAlertCount)
    }
}
