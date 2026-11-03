package com.sailens.guidance.usecase.trace

import com.sailens.guidance.model.trace.AlertRecordCompleteness
import com.sailens.guidance.model.trace.FrameTrace
import com.sailens.guidance.model.trace.GuidanceAlertChannels
import com.sailens.guidance.model.trace.GuidanceAlertKinds
import com.sailens.guidance.model.trace.GuidanceAlertResults
import com.sailens.guidance.model.trace.GuidanceAlertSources
import com.sailens.guidance.model.trace.GuidanceAlertTrace
import com.sailens.guidance.model.trace.SessionTraceMetadata
import com.sailens.guidance.model.trace.SessionTraceSummary
import com.sailens.guidance.model.trace.TraceReplaySession
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the trace report says about alerts and qualification. Alert counts are counts within the trace's window, and
 * whether they are complete is read from `seq` and the session's count: a dropped record is reported, never hidden.
 */
class TraceReplayAlertReportTest {

    private val build = BuildTraceReplayReportUseCase()

    private fun alert(seq: Long, kind: String = GuidanceAlertKinds.FAULT_NOTICE) = GuidanceAlertTrace(
        sessionId = "s",
        seq = seq,
        at = seq,
        elapsedMs = seq,
        source = GuidanceAlertSources.VISION,
        kind = kind,
        messageKey = if (kind == GuidanceAlertKinds.FAULT_RHYTHM) null else "event_camera_blocked",
        haptic = if (kind == GuidanceAlertKinds.FAULT_RHYTHM) "vision_unreliable" else null,
        priority = "critical",
        channel = if (kind == GuidanceAlertKinds.FAULT_RHYTHM) GuidanceAlertChannels.HAPTICS else GuidanceAlertChannels.SPEECH,
        result = GuidanceAlertResults.ACCEPTED,
    )

    private val start = SessionTraceMetadata("s", 0L, "default", "test", openedElapsedMs = 100L)

    private fun summary(count: Long?) = SessionTraceSummary(
        sessionId = "s", startedAt = 0L, completedAt = 10L, totalFrames = 0, droppedFrames = 0, totalEvents = 0,
        blockedFrames = 0, dangerousFrames = 0, avgProcessFrameMs = 0.0, avgTotalPipelineMs = 0.0, avgInferenceMs = 0.0,
        p95TotalPipelineMs = 0L, maxTotalPipelineMs = 0L, closedElapsedMs = count?.let { 900L }, guidanceAlertCount = count,
    )

    private fun report(
        alerts: List<GuidanceAlertTrace>,
        count: Long?,
        withSummary: Boolean = true,
        metadata: SessionTraceMetadata? = start,
        frames: List<FrameTrace> = emptyList(),
    ) = build(
        TraceReplaySession(
            metadata = metadata,
            frames = frames,
            overlayRenders = emptyList(),
            errors = emptyList(),
            summary = if (withSummary) summary(count) else null,
            guidanceAlerts = alerts,
        ),
    )

    private fun completeness(alerts: List<Long>, count: Long?, withSummary: Boolean = true) =
        report(alerts.map { alert(it) }, count, withSummary).let { it.guidanceAlertCompleteness to it.guidanceAlertsMissing }

    @Test
    fun `every seq up to the session's count is complete, and the counts are per kind`() {
        val report = report(
            listOf(alert(1), alert(2, GuidanceAlertKinds.FAULT_RHYTHM), alert(3, GuidanceAlertKinds.FAULT_RHYTHM), alert(4, GuidanceAlertKinds.FAULT_RECOVERY)),
            count = 4,
        )
        assertEquals(AlertRecordCompleteness.COMPLETE, report.guidanceAlertCompleteness)
        assertEquals(0L, report.guidanceAlertsMissing)
        assertEquals(mapOf("fault_notice" to 1, "fault_recovery" to 1, "fault_rhythm" to 2), report.guidanceAlertCounts)
    }

    @Test
    fun `a record dropped in the middle, at the start or at the end makes the counts incomplete, by how many`() {
        assertEquals(AlertRecordCompleteness.INCOMPLETE to 1L, completeness(listOf(1, 3), count = 3))
        assertEquals(AlertRecordCompleteness.INCOMPLETE to 1L, completeness(listOf(2, 3), count = 3))
        assertEquals(AlertRecordCompleteness.INCOMPLETE to 2L, completeness(listOf(1), count = 3))
        assertEquals(AlertRecordCompleteness.INCOMPLETE to 3L, completeness(emptyList(), count = 3))
    }

    @Test
    fun `without the session's count the last ones could be missing, and a gap is at least that many`() {
        assertEquals(AlertRecordCompleteness.POSSIBLY_INCOMPLETE to 0L, completeness(listOf(1, 2), count = null, withSummary = false))
        assertEquals(AlertRecordCompleteness.INCOMPLETE to 1L, completeness(listOf(2), count = null, withSummary = false))
        // The summary written by an older build carries no count.
        assertEquals(AlertRecordCompleteness.POSSIBLY_INCOMPLETE to 0L, completeness(listOf(1), count = null))
    }

    @Test
    fun `a session with no alert is complete, and a trace from before alerts were recorded says so`() {
        assertEquals(AlertRecordCompleteness.COMPLETE to 0L, completeness(emptyList(), count = 0))
        val older = report(emptyList(), count = null, metadata = SessionTraceMetadata("s", 0L, "default", "test"))
        assertEquals(AlertRecordCompleteness.NOT_RECORDED, older.guidanceAlertCompleteness)
        assertEquals(emptyMap<String, Int>(), older.guidanceAlertCounts)
    }

    @Test
    fun `alerts outside the window are in no trace and not counted, so the window's own are complete`() {
        // The session counted the two it queued; an alert before it opened never reached it.
        assertEquals(AlertRecordCompleteness.COMPLETE to 0L, completeness(listOf(1, 2), count = 2))
    }

    @Test
    fun `frames are counted per qualification, and frames from before it existed as none`() {
        fun frame(seq: Long, qualification: String?) = FrameTrace(
            sessionId = "s", sequenceNumber = seq, frameTimestamp = seq, frameWidth = 4, frameHeight = 4, droppedFramesSinceLast = 0,
            processFrameMs = 1, inferenceMs = 1, analyzeSceneMs = 1, decideEventsMs = 1, totalPipelineMs = 3, obstacleCount = 0,
            eventCount = 0, isBlocked = false, isNarrowing = false, isRoadDangerous = false, navigationPassableRatio = 0.0,
            blockageConfidence = 0.0, verticalReachRatio = 0.0, floodReachRatio = 0.0, widthRetentionP25 = 0.0,
            messageKeys = emptyList(), qualification = qualification,
        )
        val report = report(
            emptyList(), count = 0,
            frames = listOf(frame(1, "qualified"), frame(2, "unqualified"), frame(3, "qualified"), frame(4, null)),
        )
        assertEquals(mapOf("none" to 1, "qualified" to 2, "unqualified" to 1), report.qualificationFrameCounts)
    }
}
