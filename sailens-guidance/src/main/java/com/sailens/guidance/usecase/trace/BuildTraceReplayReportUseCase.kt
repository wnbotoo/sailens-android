package com.sailens.guidance.usecase.trace

import com.sailens.guidance.model.common.ObstacleRunKind
import com.sailens.guidance.model.trace.FrameTrace
import com.sailens.guidance.model.trace.SessionTraceAccumulator
import com.sailens.guidance.model.trace.SessionTraceSummary
import com.sailens.guidance.model.trace.TraceReplayParser
import com.sailens.guidance.model.trace.TraceReplayReport
import com.sailens.guidance.model.trace.TraceReplaySession

class BuildTraceReplayReportUseCase {
    operator fun invoke(lines: List<String>): TraceReplayReport = invoke(TraceReplayParser.parse(lines))

    operator fun invoke(session: TraceReplaySession): TraceReplayReport {
        val metadata = session.metadata
        val summary = session.summary ?: buildSummaryFromFrames(session)
        val totalFrames = summary.totalFrames
        val totalObservedFrames = totalFrames + summary.droppedFrames
        val droppedFrameRate = if (totalObservedFrames > 0) {
            summary.droppedFrames.toDouble() / totalObservedFrames
        } else {
            0.0
        }
        val frames = session.frames
        val avgNavigationPassableRatio = frames.averageOrZero { it.navigationPassableRatio }
        val avgNavigationPassableDelta = frames.averageOrZero { it.navigationPassableDelta }
        val maxNavigationPassableDelta = frames.maxOfOrNull { it.navigationPassableDelta } ?: 0.0
        val avgRoadRatio = frames.averageOrZero { it.roadRatio }
        val avgRoadRatioDelta = frames.averageOrZero { it.roadRatioDelta }
        val maxRoadRatioDelta = frames.maxOfOrNull { it.roadRatioDelta } ?: 0.0
        val avgBlockageConfidence = frames.averageOrZero { it.blockageConfidence }
        val avgBlockageConfidenceDelta = frames.averageOrZero { it.blockageConfidenceDelta }
        val maxBlockageConfidenceDelta = frames.maxOfOrNull { it.blockageConfidenceDelta } ?: 0.0
        val avgVerticalReachRatio = frames.averageOrZero { it.verticalReachRatio }
        val avgVerticalReachDelta = frames.averageOrZero { it.verticalReachDelta }
        val maxVerticalReachDelta = frames.maxOfOrNull { it.verticalReachDelta } ?: 0.0
        val avgFloodReachRatio = frames.averageOrZero { it.floodReachRatio }
        val avgFloodReachDelta = frames.averageOrZero { it.floodReachDelta }
        val maxFloodReachDelta = frames.maxOfOrNull { it.floodReachDelta } ?: 0.0
        val avgWidthRetentionP25 = frames.averageOrZero { it.widthRetentionP25 }
        val avgWidthRetentionP25Delta = frames.averageOrZero { it.widthRetentionP25Delta }
        val maxWidthRetentionP25Delta = frames.maxOfOrNull { it.widthRetentionP25Delta } ?: 0.0
        val avgOccludedPassableRatio = frames.averageOrZero { it.occludedPassableRatio }
        val maxOccludedPassableRatio = frames.maxOfOrNull { it.occludedPassableRatio } ?: 0.0
        val avgOccludedPassableDelta = frames.averageOrZero { it.occludedPassableDelta }
        val maxOccludedPassableDelta = frames.maxOfOrNull { it.occludedPassableDelta } ?: 0.0
        val obstacleRunFrames = frames.filter { it.ranObstacleModel() }
        val rawObstacleRunCountDeltas =
            obstacleRunFrames.adjacentIntDeltas { it.rawObstacleDetectionCount }
        val avgRawObstacleDetectionCount =
            obstacleRunFrames.averageOrZero { it.rawObstacleDetectionCount.toDouble() }
        val avgRawObstacleDetectionCountDelta =
            rawObstacleRunCountDeltas.averageOrZero { it.toDouble() }
        val maxRawObstacleDetectionCountDelta = rawObstacleRunCountDeltas.maxOrNull() ?: 0
        val avgObstacleCountDelta = frames.averageOrZero { it.obstacleCountDelta.toDouble() }
        val maxObstacleCountDelta = frames.maxOfOrNull { it.obstacleCountDelta } ?: 0
        val dominantClasses = frames
            .flatMap { it.dominantClasses }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(DOMINANT_CLASS_LIMIT)
            .map { it.key }
        val dominantClassPercentages = frames
            .firstOrNull { it.dominantClassPercentages.isNotEmpty() }
            ?.dominantClassPercentages
            .orEmpty()
        val rawObstacleClassPercentages = frames
            .flatMap { it.rawObstacleClassNames }
            .topPercentages(limit = OBSTACLE_CLASS_LIMIT)
        val trackedObstacleCategoryPercentages = frames
            .flatMap { it.trackedObstacleCategories }
            .topPercentages(limit = OBSTACLE_CLASS_LIMIT)
        val roadVehicleSourcePercentages = frames
            .map { it.roadVehicleSource }
            .filter { it.isNotBlank() && it != "none" }
            .topPercentages(limit = ROAD_VEHICLE_SOURCE_LIMIT)
        val avgRoadVehicleConfidence = frames
            .averagePositiveOrZero { it.roadVehicleConfidence }
        val roadVehicleReasonPercentages = frames
            .map { it.roadVehicleReason }
            .filter { it.isNotBlank() && it != "none" }
            .topPercentages(limit = ROAD_VEHICLE_REASON_LIMIT)
        val avgRoadVehicleBottomY = frames
            .averagePositiveOrZero { it.roadVehicleBottomY }
        val avgRoadVehicleCenterBandOverlap = frames
            .averagePositiveOrZero { it.roadVehicleCenterBandOverlap }
        val avgRoadVehicleAreaRatio = frames
            .averagePositiveOrZero { it.roadVehicleAreaRatio }
        val blockageReasonPercentages = frames
            .filter { it.isBlocked }
            .map { it.blockageReason }
            .filter { it.isNotBlank() && it != "none" }
            .topPercentages(limit = BLOCKAGE_REASON_LIMIT)
        val durationMs = frames.pipelineDurationMs(summary)
        val cameraInputFps = frames.cameraInputFps()
        val pipelineOutputFps = frames.pipelineOutputFps()
        val pipelineThroughputFps = summary.avgInferenceMs.toFps()
        // sem 运行帧按各分段耗时之和判定：缓存复用帧四段全为 0；单看 inference 会把
        // 快到取整为 0ms 的真实运行帧漏计
        val semanticRunCount = frames.count { it.semanticTotalMs() > 0 }
        val semanticRunFps = semanticRunCount.toFps(durationMs)
        val obstacleRunCount = frames.count { it.ranObstacleModel() }
        val obstacleRunFps = obstacleRunCount.toFps(durationMs)
        val avgAnalyzeSceneMs = frames.averageOrZero { it.analyzeSceneMs.toDouble() }
        val avgDecideEventsMs = frames.averageOrZero { it.decideEventsMs.toDouble() }
        val maskRenders = session.overlayRenders.filter { it.bitmapRendered }
        val maskSourceAges = maskRenders.map { it.sourceAgeMs }.filter { it > 0L }

        return TraceReplayReport(
            sessionId = metadata?.sessionId ?: summary.sessionId,
            pipelineMode = metadata?.pipelineMode,
            targetHardwareProfile = metadata?.targetHardwareProfile,
            totalFrames = totalFrames,
            droppedFrames = summary.droppedFrames,
            totalObservedFrames = totalObservedFrames,
            droppedFrameRate = droppedFrameRate,
            totalEvents = summary.totalEvents,
            durationMs = durationMs,
            cameraInputFps = cameraInputFps,
            pipelineOutputFps = pipelineOutputFps,
            pipelineThroughputFps = pipelineThroughputFps,
            semanticRunCount = semanticRunCount,
            semanticRunFps = semanticRunFps,
            obstacleRunCount = obstacleRunCount,
            obstacleRunFps = obstacleRunFps,
            overlayRenderCount = session.overlayRenders.size,
            maskRenderCount = maskRenders.size,
            avgMaskRenderMs = maskRenders.averageOrZero { it.renderMs.toDouble() },
            maskRenderFps = maskRenders.size.toFps(durationMs),
            avgMaskSourceAgeMs = maskSourceAges.averageOrZero { it.toDouble() },
            maxMaskSourceAgeMs = maskSourceAges.maxOrNull() ?: 0,
            blockedFrames = summary.blockedFrames,
            dangerousFrames = summary.dangerousFrames,
            blockedFrameRate = if (totalFrames > 0) summary.blockedFrames.toDouble() / totalFrames else 0.0,
            dangerousFrameRate = if (totalFrames > 0) summary.dangerousFrames.toDouble() / totalFrames else 0.0,
            avgProcessFrameMs = summary.avgProcessFrameMs,
            avgInferenceMs = summary.avgInferenceMs,
            avgAnalyzeSceneMs = avgAnalyzeSceneMs,
            avgDecideEventsMs = avgDecideEventsMs,
            avgLogicMs = avgAnalyzeSceneMs + avgDecideEventsMs,
            avgSemanticTotalMs = frames.averagePositiveOrZero { it.semanticTotalMs().toDouble() },
            avgSemanticPreprocessMs = frames.averagePositiveOrZero { it.semanticPreprocessMs.toDouble() },
            avgSemanticInferenceMs = frames.averagePositiveOrZero { it.semanticInferenceMs.toDouble() },
            avgSemanticOutputReadMs = frames.averagePositiveOrZero { it.semanticOutputReadMs.toDouble() },
            avgSemanticPostprocessMs = frames.averagePositiveOrZero { it.semanticPostprocessMs.toDouble() },
            avgObstacleTotalMs = frames.averagePositiveOrZero { it.obstacleTotalMs().toDouble() },
            avgObstaclePreprocessMs = frames.averagePositiveOrZero { it.obstaclePreprocessMs.toDouble() },
            avgObstacleInferenceMs = frames.averagePositiveOrZero { it.obstacleInferenceMs.toDouble() },
            avgObstacleOutputReadMs = frames.averagePositiveOrZero { it.obstacleOutputReadMs.toDouble() },
            avgObstaclePostprocessMs = frames.averagePositiveOrZero { it.obstaclePostprocessMs.toDouble() },
            semanticAccelerators = frames.distinctAcceleratorValues { it.semanticAccelerator },
            semanticAcceleratorSelections = frames.distinctKnownValues { it.semanticAcceleratorSelection },
            semanticPreprocessBackends = frames.distinctKnownValues { it.semanticPreprocessBackend },
            semanticPostprocessBackends = frames.distinctKnownValues { it.semanticPostprocessBackend },
            obstacleAccelerators = frames.distinctAcceleratorValues { it.obstacleAccelerator },
            obstacleAcceleratorSelections = frames.distinctKnownValues { it.obstacleAcceleratorSelection },
            obstaclePreprocessBackends = frames.distinctKnownValues { it.obstaclePreprocessBackend },
            obstaclePostprocessBackends = frames.distinctKnownValues { it.obstaclePostprocessBackend },
            avgTotalPipelineMs = summary.avgTotalPipelineMs,
            p95TotalPipelineMs = summary.p95TotalPipelineMs,
            maxTotalPipelineMs = summary.maxTotalPipelineMs,
            avgNavigationPassableRatio = avgNavigationPassableRatio,
            avgNavigationPassableDelta = avgNavigationPassableDelta,
            maxNavigationPassableDelta = maxNavigationPassableDelta,
            avgRoadRatio = avgRoadRatio,
            avgRoadRatioDelta = avgRoadRatioDelta,
            maxRoadRatioDelta = maxRoadRatioDelta,
            avgBlockageConfidence = avgBlockageConfidence,
            avgBlockageConfidenceDelta = avgBlockageConfidenceDelta,
            maxBlockageConfidenceDelta = maxBlockageConfidenceDelta,
            avgVerticalReachRatio = avgVerticalReachRatio,
            avgVerticalReachDelta = avgVerticalReachDelta,
            maxVerticalReachDelta = maxVerticalReachDelta,
            avgFloodReachRatio = avgFloodReachRatio,
            avgFloodReachDelta = avgFloodReachDelta,
            maxFloodReachDelta = maxFloodReachDelta,
            avgWidthRetentionP25 = avgWidthRetentionP25,
            avgWidthRetentionP25Delta = avgWidthRetentionP25Delta,
            maxWidthRetentionP25Delta = maxWidthRetentionP25Delta,
            avgOccludedPassableRatio = avgOccludedPassableRatio,
            maxOccludedPassableRatio = maxOccludedPassableRatio,
            avgOccludedPassableDelta = avgOccludedPassableDelta,
            maxOccludedPassableDelta = maxOccludedPassableDelta,
            avgRawObstacleDetectionCount = avgRawObstacleDetectionCount,
            avgRawObstacleDetectionCountDelta = avgRawObstacleDetectionCountDelta,
            maxRawObstacleDetectionCountDelta = maxRawObstacleDetectionCountDelta,
            avgObstacleCountDelta = avgObstacleCountDelta,
            maxObstacleCountDelta = maxObstacleCountDelta,
            maxDroppedFramesSinceLast = session.frames.maxOfOrNull { it.droppedFramesSinceLast } ?: 0,
            errorCount = session.errors.size,
            dominantClasses = dominantClasses,
            dominantClassPercentages = dominantClassPercentages,
            rawObstacleClassPercentages = rawObstacleClassPercentages,
            trackedObstacleCategoryPercentages = trackedObstacleCategoryPercentages,
            roadVehicleSourcePercentages = roadVehicleSourcePercentages,
            avgRoadVehicleConfidence = avgRoadVehicleConfidence,
            roadVehicleReasonPercentages = roadVehicleReasonPercentages,
            avgRoadVehicleBottomY = avgRoadVehicleBottomY,
            avgRoadVehicleCenterBandOverlap = avgRoadVehicleCenterBandOverlap,
            avgRoadVehicleAreaRatio = avgRoadVehicleAreaRatio,
            blockageReasonPercentages = blockageReasonPercentages,
            uniqueMessageKeys = session.frames
                .flatMap { it.messageKeys }
                .distinct()
                .sorted(),
        )
    }

    private fun buildSummaryFromFrames(session: TraceReplaySession): SessionTraceSummary {
        val startedAt = session.metadata?.startedAt ?: session.frames.firstOrNull()?.frameTimestamp ?: 0L
        val sessionId = session.metadata?.sessionId ?: session.frames.firstOrNull()?.sessionId ?: "unknown-session"
        val completedAt = session.frames.lastOrNull()?.frameTimestamp ?: startedAt

        return SessionTraceAccumulator(
            sessionId = sessionId,
            startedAt = startedAt,
        ).also { accumulator ->
            session.frames.forEach(accumulator::record)
        }.build(completedAt = completedAt)
    }

    private inline fun <T> List<T>.averageOrZero(selector: (T) -> Double): Double {
        if (isEmpty()) return 0.0
        return sumOf(selector) / size
    }

    private inline fun <T> List<T>.averagePositiveOrZero(selector: (T) -> Double): Double {
        var count = 0
        var total = 0.0
        forEach { item ->
            val value = selector(item)
            if (value > 0.0) {
                total += value
                count++
            }
        }
        return if (count > 0) total / count else 0.0
    }

    private inline fun <T> List<T>.adjacentIntDeltas(selector: (T) -> Int): List<Int> {
        if (size < 2) return emptyList()
        val deltas = ArrayList<Int>(size - 1)
        var previous = selector(first())
        for (index in 1 until size) {
            val current = selector(this[index])
            deltas += kotlin.math.abs(current - previous)
            previous = current
        }
        return deltas
    }

    private fun List<String>.topPercentages(limit: Int): List<String> {
        if (isEmpty()) return emptyList()
        val total = size
        return groupingBy { it }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { (name, count) ->
                val percent = (count * 100.0 / total).toInt()
                "$name:$percent%"
            }
    }

    private inline fun <T> List<T>.distinctKnownValues(selector: (T) -> String): List<String> {
        return map(selector)
            .filter { it.isNotBlank() && it != "unknown" }
            .distinct()
            .sorted()
    }

    private inline fun <T> List<T>.distinctAcceleratorValues(selector: (T) -> String): List<String> {
        return map(selector)
            .filter { it in ACTIVE_ACCELERATORS }
            .distinct()
            .sorted()
    }

    private fun List<FrameTrace>.pipelineDurationMs(summary: SessionTraceSummary): Long {
        val first = firstOrNull()?.pipelineStartedAt?.takeIf { it > 0 }
        val last = lastOrNull()?.pipelineCompletedAt?.takeIf { it > 0 }
        if (first != null && last != null && last > first) {
            return last - first
        }
        return (summary.completedAt - summary.startedAt).coerceAtLeast(0)
    }

    private fun List<FrameTrace>.pipelineOutputFps(): Double {
        if (size < 2) return 0.0
        val firstCompletedAt = first().pipelineCompletedAt
        val lastCompletedAt = last().pipelineCompletedAt
        val durationMs = lastCompletedAt - firstCompletedAt
        if (firstCompletedAt <= 0 || durationMs <= 0) return 0.0
        return (size - 1).toFps(durationMs)
    }

    private fun List<FrameTrace>.cameraInputFps(): Double {
        if (size < 2) return 0.0
        val durationMs = frameTimestampDeltaMs(first().frameTimestamp, last().frameTimestamp)
        if (durationMs <= 0) return 0.0
        val observedIntervals = (size - 1) + sumOf { it.droppedFramesSinceLast }
        return observedIntervals.toFps(durationMs)
    }

    private fun Int.toFps(durationMs: Long): Double {
        if (this <= 0 || durationMs <= 0) return 0.0
        return this * 1000.0 / durationMs
    }

    private fun Double.toFps(): Double {
        if (this <= 0.0) return 0.0
        return 1000.0 / this
    }

    private fun FrameTrace.semanticTotalMs(): Long {
        return semanticPreprocessMs + semanticInferenceMs + semanticOutputReadMs + semanticPostprocessMs
    }

    private fun FrameTrace.obstacleTotalMs(): Long {
        return obstaclePreprocessMs + obstacleInferenceMs + obstacleOutputReadMs + obstaclePostprocessMs
    }

    /**
     * 本帧是否真正运行了 obstacle 模型。以录制侧写入的 [FrameTrace.obstacleRunKind] 为准
     * （任意非 "none" 值都算一次运行，含历史 trace 里已退役的 "seg"）；旧 trace 无该字段
     * （解析为 "none"）时回退到分段耗时启发式。
     */
    private fun FrameTrace.ranObstacleModel(): Boolean {
        return obstacleRunKind != ObstacleRunKind.NONE.traceName || obstacleTotalMs() > 0
    }

    private fun frameTimestampDeltaMs(firstTimestamp: Long, lastTimestamp: Long): Long {
        val delta = lastTimestamp - firstTimestamp
        if (delta <= 0) return 0
        return if (delta > 1_000_000L) delta / 1_000_000L else delta
    }

    private companion object {
        val ACTIVE_ACCELERATORS = setOf("CPU", "GPU", "NPU")
        const val DOMINANT_CLASS_LIMIT = 5
        const val OBSTACLE_CLASS_LIMIT = 6
        const val ROAD_VEHICLE_SOURCE_LIMIT = 4
        const val ROAD_VEHICLE_REASON_LIMIT = 4
        const val BLOCKAGE_REASON_LIMIT = 6
    }
}
