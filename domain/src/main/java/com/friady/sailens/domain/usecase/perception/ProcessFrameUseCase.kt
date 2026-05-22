package com.friady.sailens.domain.usecase.perception

import com.friady.sailens.domain.config.PerceptionConfig
import com.friady.sailens.domain.model.common.DistanceLevel
import com.friady.sailens.domain.model.common.InferenceStrategy
import com.friady.sailens.domain.model.common.NormalizedRect
import com.friady.sailens.domain.model.common.PerceptionMode
import com.friady.sailens.domain.model.perception.DetectedInstance
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
    private val instanceProvider: InstanceSegmentationProvider,
    private val depthRepository: DepthRepository,
    private val segmentationAnalyzer: SegmentationAnalyzer,
    private val obstacleExtractor: ObstacleExtractor,
    private val obstacleTracker: ObstacleTracker,
) {
    private var frameCount = 0
    private var framesSinceSemanticUpdate = 0
    private var cachedSemanticAnalysis: CachedSemanticAnalysis? = null

    private data class ObstacleTrackingOutput(
        val trackedObstacles: List<DetectedObstacle>,
        val instanceDetections: List<DetectedInstance> = emptyList(),
        val instancePreprocessTimeMs: Long = 0,
        val instanceInferenceTimeMs: Long = 0,
        val instanceOutputReadTimeMs: Long = 0,
        val instancePostprocessTimeMs: Long = 0,
    )

    private data class SemanticAnalysisOutput(
        val analysis: SegmentationAnalysis,
        val preprocessTimeMs: Long = 0,
        val inferenceTimeMs: Long = 0,
        val outputReadTimeMs: Long = 0,
        val postprocessTimeMs: Long = 0,
    )

    private data class CachedSemanticAnalysis(
        val frameWidth: Int,
        val frameHeight: Int,
        val rotationDegrees: Int,
        val analysis: SegmentationAnalysis,
    )

    fun reset() {
        frameCount = 0
        framesSinceSemanticUpdate = 0
        cachedSemanticAnalysis = null
    }

    suspend operator fun invoke(frame: ImageFrame): Result<PerceptionResult> {
        val startTime = Timestamp.now()
        frameCount++

        // 1. 语义分割推理。高分辨率 semantic 输出较重，可配置为隔帧刷新并复用上一次 mask。
        val semanticOutput = getSemanticAnalysis(frame).getOrElse {
            return Result.failure(it)
        }
        val analysis = semanticOutput.analysis

        // 3. 障碍物检测与跟踪
        val trackingOutput = detectAndTrackObstacles(frame, analysis)

        val inferenceTime = Timestamp.now() - startTime

        val perception = PerceptionResult(
            timestamp = frame.timestamp,
            passableMask = analysis.passableMask,
            obstacleMask = analysis.obstacleMask,
            obstacles = trackingOutput.trackedObstacles,
            instanceDetections = trackingOutput.instanceDetections,
            bottomStats = analysis.bottomStats,
            analysis = analysis,
            inferenceTimeMs = inferenceTime,
            semanticPreprocessTimeMs = semanticOutput.preprocessTimeMs,
            semanticInferenceTimeMs = semanticOutput.inferenceTimeMs,
            semanticOutputReadTimeMs = semanticOutput.outputReadTimeMs,
            semanticPostprocessTimeMs = semanticOutput.postprocessTimeMs,
            instancePreprocessTimeMs = trackingOutput.instancePreprocessTimeMs,
            instanceInferenceTimeMs = trackingOutput.instanceInferenceTimeMs,
            instanceOutputReadTimeMs = trackingOutput.instanceOutputReadTimeMs,
            instancePostprocessTimeMs = trackingOutput.instancePostprocessTimeMs,
        )

        return Result.success(perception)
    }

    private suspend fun getSemanticAnalysis(frame: ImageFrame): Result<SemanticAnalysisOutput> {
        val reusableCached = cachedSemanticAnalysis?.takeIf { cached ->
            cached.frameWidth == frame.width &&
                cached.frameHeight == frame.height &&
                cached.rotationDegrees == frame.rotationDegrees
        }

        val shouldRunSemantic = !perceptionConfig.enableSemanticFrameSkipping ||
            perceptionConfig.semanticFrameInterval <= 1 ||
            reusableCached == null ||
            framesSinceSemanticUpdate >= perceptionConfig.semanticFrameInterval - 1

        if (!shouldRunSemantic) {
            framesSinceSemanticUpdate++
            return Result.success(
                SemanticAnalysisOutput(
                    analysis = requireNotNull(reusableCached).analysis,
                )
            )
        }

        val segmentationOutput = perceptionRepository.segment(frame).getOrElse {
            return Result.failure(it)
        }
        val analysis = segmentationAnalyzer.analyze(segmentationOutput.mask)
        cachedSemanticAnalysis = CachedSemanticAnalysis(
            frameWidth = frame.width,
            frameHeight = frame.height,
            rotationDegrees = frame.rotationDegrees,
            analysis = analysis,
        )
        framesSinceSemanticUpdate = 0
        return Result.success(
            SemanticAnalysisOutput(
                analysis = analysis,
                preprocessTimeMs = segmentationOutput.preprocessTimeMs,
                inferenceTimeMs = segmentationOutput.modelTimeMs,
                outputReadTimeMs = segmentationOutput.outputReadTimeMs,
                postprocessTimeMs = segmentationOutput.postprocessTimeMs,
            )
        )
    }

    private suspend fun detectAndTrackObstacles(
        frame: ImageFrame,
        analysis: SegmentationAnalysis,
    ): ObstacleTrackingOutput {
        val depthEstimator: (NormalizedRect) -> DistanceLevel = { boundingBox ->
            depthRepository.estimateDistance(boundingBox)
        }

        return when (perceptionConfig.mode) {
            // 模式 1: 只用语义分割
            PerceptionMode.SEMANTIC_ONLY -> {
                val rawObstacles = obstacleExtractor.extractFromSemantic(analysis, depthEstimator)
                ObstacleTrackingOutput(
                    trackedObstacles = obstacleTracker.update(rawObstacles, frame.timestamp),
                )
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
    ): ObstacleTrackingOutput {
        // 如果实例模型尚未初始化，回退到语义分割，避免启动阶段阻塞导航结果。
        if (!instanceProvider.isInitialized) {
            val rawObstacles = obstacleExtractor.extractFromSemantic(analysis, depthEstimator)
            return ObstacleTrackingOutput(
                trackedObstacles = obstacleTracker.update(rawObstacles, frame.timestamp),
            )
        }

        return when (perceptionConfig.inferenceStrategy) {

            // 同时推理：sem（可行走区域）+ seg（障碍物识别）每帧都跑
            // 优点：障碍物信息最及时；缺点：每帧双模型推理，功耗较高
            InferenceStrategy.SIMULTANEOUS -> {
                val instanceOutput = instanceProvider.detect(frame)
                val rawObstacles = obstacleExtractor.extractFromInstances(instanceOutput.instances, depthEstimator)
                ObstacleTrackingOutput(
                    trackedObstacles = obstacleTracker.update(rawObstacles, frame.timestamp),
                    instanceDetections = instanceOutput.instances,
                    instancePreprocessTimeMs = instanceOutput.preprocessTimeMs,
                    instanceInferenceTimeMs = instanceOutput.inferenceTimeMs,
                    instanceOutputReadTimeMs = instanceOutput.outputReadTimeMs,
                    instancePostprocessTimeMs = instanceOutput.postprocessTimeMs,
                )
            }

            // 交替推理：sem 每帧运行，seg 奇偶帧交替运行
            // 优点：seg 推理频率减半，功耗低；缺点：偶数帧用 tracker.predict() 补偿
            InferenceStrategy.ALTERNATING -> {
                if (frameCount % 2 == 1) {
                    // 奇数帧：运行 seg（障碍物识别），更新跟踪器
                    val instanceOutput = instanceProvider.detect(frame)
                    val rawObstacles = obstacleExtractor.extractFromInstances(instanceOutput.instances, depthEstimator)
                    ObstacleTrackingOutput(
                        trackedObstacles = obstacleTracker.update(rawObstacles, frame.timestamp),
                        instanceDetections = instanceOutput.instances,
                        instancePreprocessTimeMs = instanceOutput.preprocessTimeMs,
                        instanceInferenceTimeMs = instanceOutput.inferenceTimeMs,
                        instanceOutputReadTimeMs = instanceOutput.outputReadTimeMs,
                        instancePostprocessTimeMs = instanceOutput.postprocessTimeMs,
                    )
                } else {
                    // 偶数帧：跳过 seg，用跟踪器运动预测补偿
                    ObstacleTrackingOutput(
                        trackedObstacles = obstacleTracker.predict(frame.timestamp),
                    )
                }
            }
        }
    }
}
