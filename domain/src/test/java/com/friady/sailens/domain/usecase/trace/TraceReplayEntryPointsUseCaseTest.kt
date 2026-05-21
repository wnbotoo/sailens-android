package com.friady.sailens.domain.usecase.trace

import com.friady.sailens.domain.model.trace.TraceSessionDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceReplayEntryPointsUseCaseTest {
    @Test
    fun `list trace sessions returns newest-first descriptors from replay service`() {
        val sessions = listOf(
            TraceSessionDescriptor(
                sessionId = "session-b",
                fileName = "trace_session-b.jsonl",
                lastModifiedAt = 20L,
            ),
            TraceSessionDescriptor(
                sessionId = "session-a",
                fileName = "trace_session-a.jsonl",
                lastModifiedAt = 10L,
            ),
        )

        val useCase = ListTraceSessionsUseCase(FakeTraceReplayService(sessions = sessions))

        assertEquals(sessions, useCase())
    }

    @Test
    fun `load trace replay report returns null when session file is missing`() {
        val useCase = LoadTraceReplayReportUseCase(
            traceReplayService = FakeTraceReplayService(),
            buildTraceReplayReportUseCase = BuildTraceReplayReportUseCase(),
        )

        assertNull(useCase("missing-session"))
    }

    @Test
    fun `load latest trace replay report uses newest stored session`() {
        val newestSession = TraceSessionDescriptor(
            sessionId = "session-newest",
            fileName = "trace_session-newest.jsonl",
            lastModifiedAt = 30L,
        )
        val olderSession = TraceSessionDescriptor(
            sessionId = "session-older",
            fileName = "trace_session-older.jsonl",
            lastModifiedAt = 10L,
        )
        val sessionLines = mapOf(
            newestSession.sessionId to listOf(
                """
                {"type":"session_start","sessionId":"session-newest","startedAt":1000,"pipelineMode":"semantic_only","targetHardwareProfile":"snapdragon_8_gen_3_plus"}
                """.trimIndent(),
                """
                {"type":"frame","sessionId":"session-newest","sequenceNumber":1,"frameTimestamp":1100,"frameWidth":640,"frameHeight":360,"droppedFramesSinceLast":0,"processFrameMs":20,"inferenceMs":12,"analyzeSceneMs":4,"decideEventsMs":3,"totalPipelineMs":29,"obstacleCount":1,"eventCount":1,"isBlocked":false,"isNarrowing":false,"isRoadDangerous":false,"messageKeys":["event_obstacle_center"]}
                """.trimIndent(),
            )
        )
        val replayService = FakeTraceReplayService(
            sessions = listOf(newestSession, olderSession),
            linesBySessionId = sessionLines,
        )

        val latestUseCase = LoadLatestTraceReplayReportUseCase(
            listTraceSessionsUseCase = ListTraceSessionsUseCase(replayService),
            loadTraceReplayReportUseCase = LoadTraceReplayReportUseCase(
                traceReplayService = replayService,
                buildTraceReplayReportUseCase = BuildTraceReplayReportUseCase(),
            ),
        )

        val report = latestUseCase()

        requireNotNull(report)
        assertEquals("session-newest", report.sessionId)
        assertEquals(1, report.totalFrames)
        assertTrue(report.uniqueMessageKeys.contains("event_obstacle_center"))
    }

    @Test
    fun `evaluate trace replay budget warns when latency and dropped frames exceed budget`() {
        val report = BuildTraceReplayReportUseCase()(
            listOf(
                """
                {"type":"session_start","sessionId":"session-budget","startedAt":1000,"pipelineMode":"combined","targetHardwareProfile":"snapdragon_8_gen_3_plus"}
                """.trimIndent(),
                """
                {"type":"frame","sessionId":"session-budget","sequenceNumber":1,"frameTimestamp":1100,"frameWidth":640,"frameHeight":360,"droppedFramesSinceLast":2,"processFrameMs":30,"inferenceMs":20,"analyzeSceneMs":10,"decideEventsMs":8,"totalPipelineMs":95,"obstacleCount":2,"eventCount":1,"isBlocked":true,"isNarrowing":false,"isRoadDangerous":true,"messageKeys":["event_blocked"]}
                """.trimIndent(),
            )
        )

        val evaluation = EvaluateTraceReplayBudgetUseCase()(report)

        assertTrue(evaluation.warnings.any { it.contains("p95 total pipeline") })
        assertTrue(evaluation.warnings.any { it.contains("dropped frame rate") })
    }
}

