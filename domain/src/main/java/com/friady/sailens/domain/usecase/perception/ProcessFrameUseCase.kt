package com.friady.sailens.domain.usecase.perception

import com.friady.sailens.domain.config.PerceptionConfig
import com.friady.sailens.domain.model.common.DistanceLevel
import com.friady.sailens.domain.model.common.NormalizedRect
import com.friady.sailens.domain.model.common.PerceptionMode
import com.friady.sailens.domain.model.perception.DetectedObstacle
import com.friady.sailens.domain.model.perception.ImageFrame
import com.friady.sailens.domain.model.perception.PerceptionResult
import com.friady.sailens.domain.model.perception.SegmentationAnalysis
import com.friady.sailens.domain.processor.perception.ObstacleExtractor
import com.friady.sailens.domain.processor.perception.ObstacleTracker
import com.friady.sailens.domain.processor.perception.SegmentationAnalyzer
import com.friady.sailens.domain.repository.DepthRepository
import com.friady.sailens.domain.repository.InstanceSegmentationProvider
import com.friady.sailens.domain.repository.PerceptionRepository
import com.friady.sailens.domain.util.Timestamp

/**
 * 处理帧用例
 */
class ProcessFrameUseCase(
    private val perceptionConfig: PerceptionConfig,
    private val perceptionRepository: PerceptionRepository,
    private val instanceProvider: InstanceSegmentationProvider?,
    private val depthRepository: DepthRepository,
    private val segmentationAnalyzer: SegmentationAnalyzer,
    private val obstacleExtractor: ObstacleExtractor,
    private val obstacleTracker: ObstacleTracker,
) {
    private var frameCount = 0

    suspend operator fun invoke(frame: ImageFrame): Result<PerceptionResult> {
        val startTime = Timestamp.now()
        frameCount++

        // 1. 语义分割推理（每帧都做）
        val segmentationOutput = perceptionRepository.segment(frame).getOrElse {
            return Result.failure(it)
        }

        // 2.  分析
        val analysis = segmentationAnalyzer.analyze(segmentationOutput.mask)

        // 3. 障碍物检测与跟踪
        val trackedObstacles = detectAndTrackObstacles(frame, analysis)

        val inferenceTime = Timestamp.now() - startTime

        val perception = PerceptionResult(
            timestamp = frame.timestamp,
            passableMask = analysis.passableMask,
            obstacleMask = analysis.obstacleMask,
            obstacles = trackedObstacles,
            bottomStats = analysis.bottomStats,
            analysis = analysis,
            inferenceTimeMs = inferenceTime
        )

        return Result.success(perception)
    }

    private suspend fun detectAndTrackObstacles(
        frame: ImageFrame,
        analysis: SegmentationAnalysis,
    ): List<DetectedObstacle> {
        val depthEstimator: (NormalizedRect) -> DistanceLevel = { boundingBox ->
            depthRepository.estimateDistance(boundingBox)
        }

        return when (perceptionConfig.mode) {
            // 模式 1: 只用语义分割
            PerceptionMode.SEMANTIC_ONLY -> {
                val rawObstacles = obstacleExtractor.extractFromSemantic(analysis, depthEstimator)
                obstacleTracker.update(rawObstacles, frame.timestamp)
            }

            // 模式 2: 语义 + 实例分割（交替推理）
            PerceptionMode.COMBINED -> {
                handleCombinedMode(frame, analysis, depthEstimator)
            }
        }
    }

    private suspend fun handleCombinedMode(
        frame: ImageFrame,
        analysis: SegmentationAnalysis,
        depthEstimator: (NormalizedRect) -> DistanceLevel,
    ): List<DetectedObstacle> {
        // 如果没有实例分割提供者，回退到语义分割
        if (instanceProvider == null || !instanceProvider.isInitialized) {
            val rawObstacles = obstacleExtractor.extractFromSemantic(analysis, depthEstimator)
            return obstacleTracker.update(rawObstacles, frame.timestamp)
        }

        return if (frameCount % 2 == 1) {
            // 奇数帧：运行实例分割，更新跟踪器
            val instances = instanceProvider.detect(frame)
            val rawObstacles = obstacleExtractor.extractFromInstances(instances, depthEstimator)
            obstacleTracker.update(rawObstacles, frame.timestamp)
        } else {
            // 偶数帧：只用跟踪器预测，不运行实例分割
            obstacleTracker.predict(frame.timestamp)
        }
    }
}