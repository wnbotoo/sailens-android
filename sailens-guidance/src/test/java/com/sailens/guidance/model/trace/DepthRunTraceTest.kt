package com.sailens.guidance.model.trace

import com.sailens.guidance.usecase.trace.DepthRunReportBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `depth_run` records, the frame's depth fields, and the replay report's depth section, which counts from the records
 * alone (docs/trace_metrics_guide.md). The JSON lines use the names TraceJsonEncoder writes; org.json is a stub on the
 * JVM, so the encoder's half is exercised on device.
 */
class DepthRunTraceTest {

    private val frameCommon = """"type":"frame","sessionId":"s","sequenceNumber":1,"frameTimestamp":0,"frameWidth":1280,""" +
        """"frameHeight":720,"droppedFramesSinceLast":0,"processFrameMs":0,"inferenceMs":0,"analyzeSceneMs":0,""" +
        """"decideEventsMs":0,"totalPipelineMs":0,"obstacleCount":0,"eventCount":0,"isBlocked":false,""" +
        """"isNarrowing":false,"isRoadDangerous":false,"messageKeys":[]"""

    @Test
    fun `a depth_run line parses with every field, and a closing record with its in-flight attempt`() {
        val published = """{"type":"depth_run","sessionId":"s","seq":1,"generation":2,"runId":1,"outcome":"published","atElapsedMs":5000,""" +
            """"frameSequence":41,"frameTimeNanos":123456789,"timeBasis":"camera_realtime","geometryQuality":"ok","validity":"valid",""" +
            """"inlierRatio":0.8,"confidence":0.7,"planeAgreesWithGravity":true,"groundRatio":0.4,"aboveGroundRatio":0.2,""" +
            """"belowGroundRatio":0.01,"disparityScale":7.5,"disparityShift":0.2,"detail":3.5,"prepareMs":6,"admitWaitMs":0,""" +
            """"inferMs":31,"outputReadMs":2,"fitMs":9,"accelerator":"gpu","acceleratorSelection":"explicit gpu precision=fp16 priority=low",""" +
            """"preprocessBackend":"native_yuv","postprocessBackend":"kotlin_ground_fit","modelShape":"1x3x392x224>1x392x224",""" +
            """"skipsSinceLast":{"no_baseline_yet":2}}"""
        val closed = """{"type":"depth_run","sessionId":"s","seq":2,"generation":2,"runId":2,"outcome":"closed","atElapsedMs":6000,""" +
            """"cancelledRunId":2,"cancelledStage":"infer","failuresTotal":0,"finalState":"running"}"""
        val (run, closing) = TraceReplayParser.parse(listOf(published, closed)).depthRuns

        assertEquals(
            DepthRunTrace(
                sessionId = "s", generation = 2, runId = 1, outcome = DepthRunTrace.PUBLISHED, seq = 1, atElapsedMs = 5000,
                frameSequence = 41, frameTimeNanos = 123456789, timeBasis = "camera_realtime", geometryQuality = "ok", validity = "valid",
                inlierRatio = 0.8f, confidence = 0.7f, planeAgreesWithGravity = true, groundRatio = 0.4f, aboveGroundRatio = 0.2f,
                belowGroundRatio = 0.01f, disparityScale = 7.5f, disparityShift = 0.2f, detail = 3.5f, prepareMs = 6, admitWaitMs = 0,
                inferMs = 31, outputReadMs = 2, fitMs = 9, accelerator = "gpu", acceleratorSelection = "explicit gpu precision=fp16 priority=low",
                preprocessBackend = "native_yuv", postprocessBackend = "kotlin_ground_fit", modelShape = "1x3x392x224>1x392x224",
                skipsSinceLast = mapOf("no_baseline_yet" to 2),
            ),
            run,
        )
        assertEquals(2L, closing.cancelledRunId)
        assertEquals("infer", closing.cancelledStage)
        assertEquals("running", closing.finalState)
        assertTrue(closing.skipsSinceLast.isEmpty())
    }

    @Test
    fun `an unknown outcome is rejected with its line number`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            TraceReplayParser.parse(listOf("""{"type":"depth_run","sessionId":"s","seq":1,"generation":1,"runId":1,"outcome":"maybe","atElapsedMs":1}"""))
        }
        assertTrue(error.message!!, error.message!!.contains("line 1"))
    }

    @Test
    fun `the frame's depth fields parse, and a frame from before them has none`() {
        val (recent, old) = TraceReplayParser.parse(listOf("""{$frameCommon,"depthRunId":3,"depthAgeMs":-120}""", "{$frameCommon}")).frames
        assertEquals(3L, recent.depthRunId)
        assertEquals(-120L, recent.depthAgeMs)
        assertNull(old.depthRunId)
        assertNull(old.depthAgeMs)
    }

    @Test
    fun `the session's flag and count parse, and a trace from before them has neither`() {
        val start = """{"type":"session_start","sessionId":"s","startedAt":0,"pipelineMode":"default","targetHardwareProfile":"t","depthTrace":true}"""
        val parsed = TraceReplayParser.parse(listOf(start))
        assertTrue(parsed.metadata!!.depthTrace)
        val oldStart = """{"type":"session_start","sessionId":"s","startedAt":0,"pipelineMode":"default","targetHardwareProfile":"t"}"""
        assertFalse(TraceReplayParser.parse(listOf(oldStart)).metadata!!.depthTrace)
        assertEquals(DepthRunReport.NOT_RECORDED, DepthRunReportBuilder.build(TraceReplayParser.parse(listOf(oldStart))))
    }

    // ---- the report

    private fun run(seq: Long, runId: Long, outcome: String = DepthRunTrace.PUBLISHED, generation: Long = 1, inferMs: Long? = 30, validity: String? = "valid", skips: Map<String, Int> = emptyMap()) =
        DepthRunTrace(sessionId = "s", generation = generation, runId = runId, outcome = outcome, seq = seq, atElapsedMs = seq, inferMs = inferMs, validity = validity, skipsSinceLast = skips)

    private fun closed(seq: Long, lastRunId: Long, generation: Long = 1, cancelled: Long? = null, skips: Map<String, Int> = emptyMap()) =
        DepthRunTrace(sessionId = "s", generation = generation, runId = lastRunId, outcome = DepthRunTrace.CLOSED, seq = seq, atElapsedMs = seq, cancelledRunId = cancelled, cancelledStage = cancelled?.let { "fit" }, skipsSinceLast = skips)

    private fun frame(sequence: Long, depthRunId: Long?, depthAgeMs: Long?) = FrameTrace(
        sessionId = "s", sequenceNumber = sequence, frameTimestamp = sequence, frameWidth = 1, frameHeight = 1,
        droppedFramesSinceLast = 0, processFrameMs = 0, inferenceMs = 0, analyzeSceneMs = 0, decideEventsMs = 0, totalPipelineMs = 0,
        obstacleCount = 0, eventCount = 0, isBlocked = false, isNarrowing = false, isRoadDangerous = false, navigationPassableRatio = 0.0,
        blockageConfidence = 0.0, verticalReachRatio = 0.0, floodReachRatio = 0.0, widthRetentionP25 = 0.0, messageKeys = emptyList(),
        depthRunId = depthRunId, depthAgeMs = depthAgeMs,
    )

    private fun session(runs: List<DepthRunTrace>, frames: List<FrameTrace> = emptyList(), counted: Long? = runs.size.toLong()) = TraceReplaySession(
        metadata = SessionTraceMetadata(sessionId = "s", startedAt = 0, pipelineMode = "default", targetHardwareProfile = "t", depthTrace = true),
        frames = frames,
        overlayRenders = emptyList(),
        promptOutcomes = emptyList(),
        errors = emptyList(),
        summary = counted?.let {
            SessionTraceSummary(
                sessionId = "s", startedAt = 0L, completedAt = 0L, totalFrames = 0, droppedFrames = 0, totalEvents = 0,
                blockedFrames = 0, dangerousFrames = 0, avgProcessFrameMs = 0.0, avgTotalPipelineMs = 0.0, avgInferenceMs = 0.0,
                p95TotalPipelineMs = 0L, maxTotalPipelineMs = 0L, depthRunCount = it,
            )
        },
        depthRuns = runs,
    )

    @Test
    fun `two runs between two frames are two runs, the frame carrying the later`() {
        val runs = listOf(run(1, 1), run(2, 2), closed(3, 2))
        val report = DepthRunReportBuilder.build(session(runs, listOf(frame(10, depthRunId = 2, depthAgeMs = 40))))
        assertEquals(DepthRecordCompleteness.COMPLETE, report.completeness)
        assertEquals(2, report.attempts)
        assertEquals(mapOf(DepthRunTrace.PUBLISHED to 2), report.outcomeCounts)
        assertEquals(1, report.ageMs!!.count)
    }

    @Test
    fun `one snapshot on three frames is still one run, and sampling the frames changes no run statistic`() {
        val runs = (1L..20L).map { run(it, it, inferMs = 20 + it) } + closed(21, 20)
        val every = (1L..60L).map { frame(it, depthRunId = (it + 2) / 3, depthAgeMs = (it % 3) * 100) }
        val full = DepthRunReportBuilder.build(session(runs, every))
        val sampled = DepthRunReportBuilder.build(session(runs, every.filter { it.sequenceNumber % 10 == 0L }))
        assertEquals(20, full.attempts)
        assertEquals(full.copy(ageMs = null), sampled.copy(ageMs = null))
        assertEquals(TimingStats(count = 20, p50 = 30, p95 = 39, max = 40), full.inferMs)
    }

    @Test
    fun `an in-flight attempt at the stop counts once, as cancelled`() {
        val report = DepthRunReportBuilder.build(session(listOf(run(1, 1), closed(2, 2, cancelled = 2))))
        assertEquals(DepthRecordCompleteness.COMPLETE, report.completeness)
        assertEquals(2, report.attempts)
        assertEquals(mapOf(DepthRunReport.CANCELLED to 1, DepthRunTrace.PUBLISHED to 1), report.outcomeCounts)
    }

    @Test
    fun `outcomes, validity, the inverse ratio and the skips add up over the trace`() {
        val runs = listOf(
            run(1, 1, validity = "valid", skips = mapOf("no_baseline_yet" to 3)),
            run(2, 2, validity = "inverse_output"),
            run(3, 3, DepthRunTrace.YIELD_DROPPED, inferMs = null, validity = null, skips = mapOf("yield" to 1)),
            run(4, 4, DepthRunTrace.NON_FINITE_OUTPUT, validity = null),
            closed(5, 4, skips = mapOf("yield" to 2, "thermal" to 1)),
        )
        val report = DepthRunReportBuilder.build(session(runs))
        assertEquals(mapOf("non_finite_output" to 1, "published" to 2, "yield_dropped" to 1), report.outcomeCounts)
        assertEquals(mapOf("inverse_output" to 1, "valid" to 1), report.validityCounts)
        assertEquals(0.5, report.inverseOutputRatio, 0.0)
        assertEquals(mapOf("no_baseline_yet" to 3, "thermal" to 1, "yield" to 3), report.skipCounts)
    }

    @Test
    fun `a gap in seq, a missing run id, a depth session never closed, or no summary is not complete`() {
        val seqGap = DepthRunReportBuilder.build(session(listOf(run(1, 1), closed(3, 2, cancelled = 2)), counted = 3))
        assertEquals(DepthRecordCompleteness.INCOMPLETE, seqGap.completeness)
        assertEquals(1L, seqGap.missingRecords)

        val shortOfCount = DepthRunReportBuilder.build(session(listOf(run(1, 1), closed(2, 1)), counted = 4))
        assertEquals(DepthRecordCompleteness.INCOMPLETE, shortOfCount.completeness)
        assertEquals(2L, shortOfCount.missingRecords)

        // Run 2 began and was settled by neither its own record nor the closing record.
        val runGap = DepthRunReportBuilder.build(session(listOf(run(1, 1), run(2, 3), closed(3, 3))))
        assertEquals(DepthRecordCompleteness.INCOMPLETE, runGap.completeness)

        val unclosed = DepthRunReportBuilder.build(session(listOf(run(1, 1), run(2, 1, generation = 2), closed(3, 1, generation = 2))))
        assertEquals(DepthRecordCompleteness.INCOMPLETE, unclosed.completeness)

        val noSummary = DepthRunReportBuilder.build(session(listOf(run(1, 1), closed(2, 1)), counted = null))
        assertEquals(DepthRecordCompleteness.POSSIBLY_INCOMPLETE, noSummary.completeness)
    }

    @Test
    fun `a lane that never ran is a complete trace with no runs`() {
        val report = DepthRunReportBuilder.build(session(emptyList(), counted = 0))
        assertEquals(DepthRecordCompleteness.COMPLETE, report.completeness)
        assertEquals(0, report.attempts)
        assertNull(report.inferMs)
    }
}
