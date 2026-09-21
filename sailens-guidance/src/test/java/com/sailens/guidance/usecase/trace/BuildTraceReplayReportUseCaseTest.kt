package com.sailens.guidance.usecase.trace

import com.sailens.guidance.model.trace.TraceReplayParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuildTraceReplayReportUseCaseTest {
    private val useCase = BuildTraceReplayReportUseCase()

    @Test
    fun `builds replay report from complete trace jsonl`() {
        val lines = listOf(
            """
            {"type":"session_start","sessionId":"session-1","startedAt":1000,"pipelineMode":"semantic_only","targetHardwareProfile":"snapdragon_8_gen_3_plus"}
            """.trimIndent(),
            """
            {"type":"frame","sessionId":"session-1","sequenceNumber":1,"frameTimestamp":1100,"frameWidth":640,"frameHeight":360,"droppedFramesSinceLast":0,"processFrameMs":18,"inferenceMs":10,"analyzeSceneMs":4,"decideEventsMs":3,"totalPipelineMs":25,"obstacleCount":1,"eventCount":1,"isBlocked":false,"isNarrowing":false,"isRoadDangerous":true,"navigationPassableRatio":0.52,"navigationPassableDelta":0.0,"roadRatio":0.30,"roadRatioDelta":0.0,"blockageConfidence":0.12,"blockageConfidenceDelta":0.0,"blockageReason":"none","verticalReachRatio":0.66,"verticalReachDelta":0.0,"floodReachRatio":0.44,"floodReachDelta":0.0,"widthRetentionP25":0.58,"widthRetentionP25Delta":0.0,"occludedPassableRatio":0.10,"occludedPassableDelta":0.0,"rawObstacleDetectionCount":3,"rawObstacleMaskCount":1,"obstacleCountDelta":0,"rawObstacleDetectionCountDelta":0,"rawObstacleClassNames":["car","person","car"],"trackedObstacleCategories":["VEHICLE"],"roadVehicleSource":"raw","roadVehicleConfidence":0.80,"roadVehicleReason":"center_band","roadVehicleBottomY":0.74,"roadVehicleCenterBandOverlap":0.90,"roadVehicleAreaRatio":0.08,"messageKeys":["event_road_danger"],"semanticPreprocessMs":10,"semanticInferenceMs":2,"semanticOutputReadMs":3,"semanticPostprocessMs":4,"obstaclePreprocessMs":20,"obstacleInferenceMs":5,"obstacleOutputReadMs":1,"obstaclePostprocessMs":2,"obstacleRunKind":"seg"}
            """.trimIndent(),
            """
            {"type":"frame","sessionId":"session-1","sequenceNumber":3,"frameTimestamp":1200,"frameWidth":640,"frameHeight":360,"droppedFramesSinceLast":1,"processFrameMs":30,"inferenceMs":15,"analyzeSceneMs":6,"decideEventsMs":4,"totalPipelineMs":40,"obstacleCount":2,"eventCount":2,"isBlocked":true,"isNarrowing":true,"isRoadDangerous":false,"navigationPassableRatio":0.40,"navigationPassableDelta":0.12,"roadRatio":0.20,"roadRatioDelta":0.10,"blockageConfidence":0.55,"blockageConfidenceDelta":0.43,"blockageReason":"flood+width","verticalReachRatio":0.33,"verticalReachDelta":0.33,"floodReachRatio":0.18,"floodReachDelta":0.26,"widthRetentionP25":0.28,"widthRetentionP25Delta":0.30,"occludedPassableRatio":0.40,"occludedPassableDelta":0.30,"rawObstacleDetectionCount":1,"rawObstacleMaskCount":0,"obstacleCountDelta":1,"rawObstacleDetectionCountDelta":2,"messageKeys":["event_blocked","event_obstacle_ahead"],"semanticPreprocessMs":20,"semanticInferenceMs":4,"semanticOutputReadMs":5,"semanticPostprocessMs":6}
            """.trimIndent(),
            """
            {"type":"overlay_render","sessionId":"session-1","renderedAt":1210,"renderMs":6,"overlayMode":"PASSABLE_AREA_MASK","bitmapRendered":true,"sourceSequenceNumber":1,"sourcePipelineCompletedAt":1180,"sourceAgeMs":30}
            """.trimIndent(),
            """
            {"type":"overlay_render","sessionId":"session-1","renderedAt":1260,"renderMs":8,"overlayMode":"PASSABLE_AREA_MASK","bitmapRendered":true,"sourceSequenceNumber":3,"sourcePipelineCompletedAt":1210,"sourceAgeMs":50}
            """.trimIndent(),
            """
            {"type":"error","sessionId":"session-1","stage":"navigation_flow","exception":"RuntimeException","message":"boom"}
            """.trimIndent(),
            """
            {"type":"session_summary","sessionId":"session-1","startedAt":1000,"completedAt":1300,"totalFrames":2,"droppedFrames":1,"totalEvents":3,"blockedFrames":1,"dangerousFrames":1,"avgProcessFrameMs":24.0,"avgTotalPipelineMs":32.5,"avgInferenceMs":12.5,"p95TotalPipelineMs":40,"maxTotalPipelineMs":40}
            """.trimIndent(),
        )

        val parsed = TraceReplayParser.parse(lines)
        val report = useCase(parsed)

        assertEquals("session-1", parsed.metadata?.sessionId)
        assertEquals(2, parsed.frames.size)
        assertEquals(2, parsed.overlayRenders.size)
        assertEquals(1, parsed.errors.size)
        assertEquals("session-1", report.sessionId)
        assertEquals("semantic_only", report.pipelineMode)
        assertEquals("snapdragon_8_gen_3_plus", report.targetHardwareProfile)
        assertEquals(2, report.totalFrames)
        assertEquals(1, report.droppedFrames)
        assertEquals(3, report.totalObservedFrames)
        assertEquals(1.0 / 3.0, report.droppedFrameRate, 0.0001)
        assertEquals(3, report.totalEvents)
        assertEquals(1, report.blockedFrames)
        assertEquals(1, report.dangerousFrames)
        assertEquals(0.5, report.blockedFrameRate, 0.0001)
        assertEquals(0.5, report.dangerousFrameRate, 0.0001)
        assertEquals(24.0, report.avgProcessFrameMs, 0.0001)
        assertEquals(12.5, report.avgInferenceMs, 0.0001)
        assertEquals(5.0, report.avgAnalyzeSceneMs, 0.0001)
        assertEquals(3.5, report.avgDecideEventsMs, 0.0001)
        assertEquals(8.5, report.avgLogicMs, 0.0001)
        assertEquals(27.0, report.avgSemanticTotalMs, 0.0001)
        assertEquals(28.0, report.avgObstacleTotalMs, 0.0001)
        assertEquals(2, report.overlayRenderCount)
        assertEquals(2, report.maskRenderCount)
        assertEquals(7.0, report.avgMaskRenderMs, 0.0001)
        assertEquals(40.0, report.avgMaskSourceAgeMs, 0.0001)
        assertEquals(50L, report.maxMaskSourceAgeMs)
        assertEquals(32.5, report.avgTotalPipelineMs, 0.0001)
        assertEquals(40L, report.p95TotalPipelineMs)
        assertEquals(40L, report.maxTotalPipelineMs)
        assertEquals(0.46, report.avgNavigationPassableRatio, 0.0001)
        assertEquals(0.06, report.avgNavigationPassableDelta, 0.0001)
        assertEquals(0.12, report.maxNavigationPassableDelta, 0.0001)
        assertEquals(0.25, report.avgRoadRatio, 0.0001)
        assertEquals(0.05, report.avgRoadRatioDelta, 0.0001)
        assertEquals(0.10, report.maxRoadRatioDelta, 0.0001)
        assertEquals(0.335, report.avgBlockageConfidence, 0.0001)
        assertEquals(0.215, report.avgBlockageConfidenceDelta, 0.0001)
        assertEquals(0.43, report.maxBlockageConfidenceDelta, 0.0001)
        assertEquals(0.495, report.avgVerticalReachRatio, 0.0001)
        assertEquals(0.165, report.avgVerticalReachDelta, 0.0001)
        assertEquals(0.33, report.maxVerticalReachDelta, 0.0001)
        assertEquals(0.31, report.avgFloodReachRatio, 0.0001)
        assertEquals(0.13, report.avgFloodReachDelta, 0.0001)
        assertEquals(0.26, report.maxFloodReachDelta, 0.0001)
        assertEquals(0.43, report.avgWidthRetentionP25, 0.0001)
        assertEquals(0.15, report.avgWidthRetentionP25Delta, 0.0001)
        assertEquals(0.30, report.maxWidthRetentionP25Delta, 0.0001)
        assertEquals(0.25, report.avgOccludedPassableRatio, 0.0001)
        assertEquals(0.40, report.maxOccludedPassableRatio, 0.0001)
        assertEquals(0.15, report.avgOccludedPassableDelta, 0.0001)
        assertEquals(0.30, report.maxOccludedPassableDelta, 0.0001)
        assertEquals(1, report.obstacleRunCount)
        assertEquals(3.0, report.avgRawObstacleDetectionCount, 0.0001)
        assertEquals(0.0, report.avgRawObstacleDetectionCountDelta, 0.0001)
        assertEquals(0, report.maxRawObstacleDetectionCountDelta)
        assertEquals(0.5, report.avgObstacleCountDelta, 0.0001)
        assertEquals(1, report.maxObstacleCountDelta)
        assertEquals(1, report.maxDroppedFramesSinceLast)
        assertEquals(1, report.errorCount)
        assertEquals(listOf("car:66%", "person:33%"), report.rawObstacleClassPercentages)
        assertEquals(listOf("VEHICLE:100%"), report.trackedObstacleCategoryPercentages)
        assertEquals(listOf("raw:100%"), report.roadVehicleSourcePercentages)
        assertEquals(0.80, report.avgRoadVehicleConfidence, 0.0001)
        assertEquals(listOf("center_band:100%"), report.roadVehicleReasonPercentages)
        assertEquals(0.74, report.avgRoadVehicleBottomY, 0.0001)
        assertEquals(0.90, report.avgRoadVehicleCenterBandOverlap, 0.0001)
        assertEquals(0.08, report.avgRoadVehicleAreaRatio, 0.0001)
        assertEquals(listOf("flood+width:100%"), report.blockageReasonPercentages)
        assertEquals(
            listOf("event_blocked", "event_obstacle_ahead", "event_road_danger"),
            report.uniqueMessageKeys,
        )
    }

    @Test
    fun `falls back to frame aggregation when session summary is missing`() {
        val lines = listOf(
            """
            {"type":"session_start","sessionId":"session-2","startedAt":2000,"pipelineMode":"combined","targetHardwareProfile":"snapdragon_8_gen_3_plus"}
            """.trimIndent(),
            """
            {"type":"frame","sessionId":"session-2","sequenceNumber":5,"frameTimestamp":2100,"frameWidth":640,"frameHeight":360,"droppedFramesSinceLast":0,"processFrameMs":20,"inferenceMs":12,"analyzeSceneMs":5,"decideEventsMs":3,"totalPipelineMs":28,"obstacleCount":1,"eventCount":0,"isBlocked":false,"isNarrowing":false,"isRoadDangerous":false,"navigationPassableRatio":0.60,"blockageConfidence":0.10,"verticalReachRatio":0.66,"floodReachRatio":0.50,"widthRetentionP25":0.62,"messageKeys":[]}
            """.trimIndent(),
            """
            {"type":"frame","sessionId":"session-2","sequenceNumber":8,"frameTimestamp":2200,"frameWidth":640,"frameHeight":360,"droppedFramesSinceLast":2,"processFrameMs":40,"inferenceMs":18,"analyzeSceneMs":7,"decideEventsMs":5,"totalPipelineMs":52,"obstacleCount":2,"eventCount":1,"isBlocked":true,"isNarrowing":false,"isRoadDangerous":true,"navigationPassableRatio":0.35,"blockageConfidence":0.60,"verticalReachRatio":0.30,"floodReachRatio":0.14,"widthRetentionP25":0.22,"messageKeys":["event_blocked"]}
            """.trimIndent(),
            "",
        )

        val parsed = TraceReplayParser.parse(lines)
        val report = useCase(lines)

        assertNull(parsed.summary)
        assertTrue(parsed.errors.isEmpty())
        assertEquals("session-2", report.sessionId)
        assertEquals("combined", report.pipelineMode)
        assertEquals(2, report.totalFrames)
        assertEquals(2, report.droppedFrames)
        assertEquals(4, report.totalObservedFrames)
        assertEquals(0.5, report.droppedFrameRate, 0.0001)
        assertEquals(1, report.totalEvents)
        assertEquals(1, report.blockedFrames)
        assertEquals(1, report.dangerousFrames)
        assertEquals(30.0, report.avgProcessFrameMs, 0.0001)
        assertEquals(15.0, report.avgInferenceMs, 0.0001)
        assertEquals(40.0, report.avgTotalPipelineMs, 0.0001)
        assertEquals(52L, report.p95TotalPipelineMs)
        assertEquals(52L, report.maxTotalPipelineMs)
        assertEquals(0.475, report.avgNavigationPassableRatio, 0.0001)
        assertEquals(0.35, report.avgBlockageConfidence, 0.0001)
        assertEquals(0.48, report.avgVerticalReachRatio, 0.0001)
        assertEquals(0.32, report.avgFloodReachRatio, 0.0001)
        assertEquals(0.42, report.avgWidthRetentionP25, 0.0001)
        assertEquals(2, report.maxDroppedFramesSinceLast)
        assertEquals(listOf("event_blocked"), report.uniqueMessageKeys)
    }
}
