package com.friady.sailens.domain.model.trace

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionTraceAccumulatorTest {
    @Test
    fun `build aggregates frame metrics and dropped frames`() {
        val accumulator = SessionTraceAccumulator(
            sessionId = "session-1",
            startedAt = 1_000L,
        )

        accumulator.record(
            frameTrace(
                sequenceNumber = 1L,
                droppedFramesSinceLast = 0,
                processFrameMs = 20L,
                inferenceMs = 15L,
                totalPipelineMs = 35L,
                eventCount = 1,
                isBlocked = false,
                isRoadDangerous = true,
            )
        )
        accumulator.record(
            frameTrace(
                sequenceNumber = 4L,
                droppedFramesSinceLast = 2,
                processFrameMs = 30L,
                inferenceMs = 18L,
                totalPipelineMs = 60L,
                eventCount = 2,
                isBlocked = true,
                isRoadDangerous = false,
            )
        )

        val summary = accumulator.build(completedAt = 2_000L)

        assertEquals("session-1", summary.sessionId)
        assertEquals(2, summary.totalFrames)
        assertEquals(2, summary.droppedFrames)
        assertEquals(3, summary.totalEvents)
        assertEquals(1, summary.blockedFrames)
        assertEquals(1, summary.dangerousFrames)
        assertEquals(25.0, summary.avgProcessFrameMs, 0.001)
        assertEquals(16.5, summary.avgInferenceMs, 0.001)
        assertEquals(47.5, summary.avgTotalPipelineMs, 0.001)
        assertEquals(60L, summary.p95TotalPipelineMs)
        assertEquals(60L, summary.maxTotalPipelineMs)
        assertEquals(2_000L, summary.completedAt)
    }

    private fun frameTrace(
        sequenceNumber: Long,
        droppedFramesSinceLast: Int,
        processFrameMs: Long,
        inferenceMs: Long,
        totalPipelineMs: Long,
        eventCount: Int,
        isBlocked: Boolean,
        isRoadDangerous: Boolean,
    ) = FrameTrace(
        sessionId = "session-1",
        sequenceNumber = sequenceNumber,
        frameTimestamp = 10_000L + sequenceNumber,
        frameWidth = 640,
        frameHeight = 360,
        droppedFramesSinceLast = droppedFramesSinceLast,
        processFrameMs = processFrameMs,
        inferenceMs = inferenceMs,
        analyzeSceneMs = 5L,
        decideEventsMs = 4L,
        totalPipelineMs = totalPipelineMs,
        obstacleCount = 1,
        eventCount = eventCount,
        isBlocked = isBlocked,
        isNarrowing = false,
        isRoadDangerous = isRoadDangerous,
        messageKeys = listOf("event_blocked"),
    )
}


