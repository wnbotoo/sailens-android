package com.sailens.domain.usecase.perception

import com.sailens.domain.config.PerceptionConfig
import com.sailens.domain.model.common.DistanceLevel
import com.sailens.core.geometry.NormalizedRect
import com.sailens.domain.model.common.ObstacleRunKind
import com.sailens.domain.model.perception.ObstacleDetection
import com.sailens.domain.model.perception.DetectedObstacle
import com.sailens.core.frame.ImageFrame
import com.sailens.core.runtime.MlRuntimeInfo
import com.sailens.domain.model.perception.PerceptionResult
import com.sailens.domain.model.perception.SegmentationAnalysis
import com.sailens.domain.processor.perception.ObstacleExtractor
import com.sailens.domain.processor.perception.ObstacleTracker
import com.sailens.domain.processor.perception.PerceptionProfileManager
import com.sailens.domain.processor.perception.PerceptionScheduler
import com.sailens.domain.processor.perception.SegmentationAnalysisProcessor
import com.sailens.domain.repository.DepthRepository
import com.sailens.domain.repository.ObstacleProvider
import com.sailens.domain.repository.PerceptionRepository
import com.sailens.domain.util.Timestamp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * 处理帧用例
 *
 * 每帧的模型组合由 [PerceptionScheduler]（时间间隔）决定，不再使用 frameIndex 奇偶交替：
 *   - sem：按 semanticTargetFps 运行，跳过帧复用缓存的 analysis
 *   - det：按 detectionTargetFps 运行，间隔帧用 ObstacleTracker 预测补偿
 *
 * sem 和 obstacle provider 各有独立的 limitedParallelism(1) dispatcher，用 async{} 让二者
 * 在不同 runtime 支持并发时可以并行——无需等 sem 完成即可启动 obstacle 推理。
 */
class ProcessFrameUseCase(
    private val profileManager: PerceptionProfileManager,
    private val perceptionRepository: PerceptionRepository,
    private val realtimeObstacleProvider: ObstacleProvider,
    private val depthRepository: DepthRepository,
    private val segmentationAnalyzer: SegmentationAnalysisProcessor,
    private val obstacleExtractor: ObstacleExtractor,
    private val obstacleTracker: ObstacleTracker,
    private val clock: () -> Long = Timestamp::now,
) {
    private val scheduler = PerceptionScheduler { profileManager.activeConfig }
    private var cachedSemanticAnalysis: CachedSemanticAnalysis? = null

    private data class ObstacleRun(
        val provider: ObstacleProvider,
        val kind: ObstacleRunKind,
    )

    private data class ObstacleTrackingOutput(
        val trackedObstacles: List<DetectedObstacle>,
        val runKind: ObstacleRunKind = ObstacleRunKind.NONE,
        val obstacleDetections: List<ObstacleDetection> = emptyList(),
        val obstaclePreprocessTimeMs: Long = 0,
        val obstacleInferenceTimeMs: Long = 0,
        val obstacleOutputReadTimeMs: Long = 0,
        val obstaclePostprocessTimeMs: Long = 0,
        val runtimeInfo: MlRuntimeInfo = MlRuntimeInfo(),
    )

    private data class SemanticAnalysisOutput(
        val analysis: SegmentationAnalysis,
        val preprocessTimeMs: Long = 0,
        val inferenceTimeMs: Long = 0,
        val outputReadTimeMs: Long = 0,
        val postprocessTimeMs: Long = 0,
        val runtimeInfo: MlRuntimeInfo = MlRuntimeInfo(),
    )

    private data class CachedSemanticAnalysis(
        val frameWidth: Int,
        val frameHeight: Int,
        val rotationDegrees: Int,
        val analysis: SegmentationAnalysis,
        val runtimeInfo: MlRuntimeInfo,
    )

    fun reset() {
        scheduler.reset()
        cachedSemanticAnalysis = null
    }

    suspend operator fun invoke(frame: ImageFrame): Result<PerceptionResult> = coroutineScope {
        val startTime = clock()
        // 挡位在会话之间可切换（PerceptionProfileManager.activateSelected），会话内不变
        val config = profileManager.activeConfig
        requireConfiguredObstacleProvidersReady(config)

        val obstacleRun = selectObstacleRun(config, startTime)

        val semDeferred = async { getSemanticAnalysis(frame) }
        val obstacleDeferred = obstacleRun?.let { run -> async { run.provider.detect(frame) } }

        val semanticOutput = semDeferred.await().getOrElse {
            return@coroutineScope Result.failure(it)
        }

        val depthEstimator: (NormalizedRect) -> DistanceLevel = { depthRepository.estimateDistance(it) }
        val trackingOutput = if (obstacleRun != null && obstacleDeferred != null) {
            val obstacleOutput = obstacleDeferred.await()
            val completedAt = clock()
            scheduler.markDetectionRun(completedAt)

            val rawObstacles = obstacleExtractor.extractFromDetections(obstacleOutput.detections, depthEstimator)
            ObstacleTrackingOutput(
                trackedObstacles = obstacleTracker.update(rawObstacles, completedAt),
                runKind = obstacleRun.kind,
                obstacleDetections = obstacleOutput.detections,
                obstaclePreprocessTimeMs = obstacleOutput.preprocessTimeMs,
                obstacleInferenceTimeMs = obstacleOutput.inferenceTimeMs,
                obstacleOutputReadTimeMs = obstacleOutput.outputReadTimeMs,
                obstaclePostprocessTimeMs = obstacleOutput.postprocessTimeMs,
                runtimeInfo = obstacleOutput.runtimeInfo,
            )
        } else if (config.detectionEnabled) {
            // det 本帧未到间隔：用跟踪器运动预测补偿
            ObstacleTrackingOutput(
                trackedObstacles = obstacleTracker.predict(clock()),
                runtimeInfo = MlRuntimeInfo.skipped("tracker_predict"),
            )
        } else {
            // BASIC 挡位（或未配置 det provider）：从语义分割提取障碍物
            val rawObstacles = obstacleExtractor.extractFromSemantic(semanticOutput.analysis, depthEstimator)
            ObstacleTrackingOutput(
                trackedObstacles = obstacleTracker.update(rawObstacles, clock()),
                runtimeInfo = MlRuntimeInfo.unavailable("semantic_only"),
            )
        }

        Result.success(buildPerceptionResult(frame, semanticOutput, trackingOutput, startTime))
    }

    /** 决定本帧 obstacle 槽位是否运行 det（按调度间隔），否则不跑（由 tracker 预测补偿）。 */
    private fun selectObstacleRun(config: PerceptionConfig, nowMs: Long): ObstacleRun? {
        if (!config.detectionEnabled) return null

        if (scheduler.shouldRunDetection(nowMs)) {
            return ObstacleRun(realtimeObstacleProvider, ObstacleRunKind.DETECTION)
        }
        return null
    }

    private fun requireConfiguredObstacleProvidersReady(config: PerceptionConfig) {
        if (config.detectionEnabled) {
            check(realtimeObstacleProvider.isInitialized) {
                "Realtime obstacle provider is configured as " +
                    "${config.realtimeObstacleProviderType} but is not initialized"
            }
        }
    }

    private fun buildPerceptionResult(
        frame: ImageFrame,
        semanticOutput: SemanticAnalysisOutput,
        trackingOutput: ObstacleTrackingOutput,
        startTime: Long,
    ): PerceptionResult {
        val analysis = semanticOutput.analysis
        return PerceptionResult(
            timestamp = frame.timestamp,
            passableMask = analysis.passableMask,
            obstacleMask = analysis.obstacleMask,
            obstacles = trackingOutput.trackedObstacles,
            obstacleDetections = trackingOutput.obstacleDetections,
            bottomStats = analysis.bottomStats,
            analysis = analysis,
            inferenceTimeMs = clock() - startTime,
            semanticPreprocessTimeMs = semanticOutput.preprocessTimeMs,
            semanticInferenceTimeMs = semanticOutput.inferenceTimeMs,
            semanticOutputReadTimeMs = semanticOutput.outputReadTimeMs,
            semanticPostprocessTimeMs = semanticOutput.postprocessTimeMs,
            semanticRuntimeInfo = semanticOutput.runtimeInfo,
            obstaclePreprocessTimeMs = trackingOutput.obstaclePreprocessTimeMs,
            obstacleInferenceTimeMs = trackingOutput.obstacleInferenceTimeMs,
            obstacleOutputReadTimeMs = trackingOutput.obstacleOutputReadTimeMs,
            obstaclePostprocessTimeMs = trackingOutput.obstaclePostprocessTimeMs,
            obstacleRuntimeInfo = trackingOutput.runtimeInfo,
            obstacleRunKind = trackingOutput.runKind,
        )
    }

    private suspend fun getSemanticAnalysis(frame: ImageFrame): Result<SemanticAnalysisOutput> {
        val reusableCached = cachedSemanticAnalysis?.takeIf { cached ->
            cached.frameWidth == frame.width &&
                cached.frameHeight == frame.height &&
                cached.rotationDegrees == frame.rotationDegrees
        }

        // sem 是导航底线：无可复用缓存时必须运行，否则按 semanticTargetFps 间隔运行
        if (reusableCached != null && !scheduler.shouldRunSemantic(clock())) {
            return Result.success(
                SemanticAnalysisOutput(
                    analysis = reusableCached.analysis,
                    runtimeInfo = MlRuntimeInfo.cached(reusableCached.runtimeInfo),
                )
            )
        }

        val segmentationOutput = perceptionRepository.segment(frame).getOrElse {
            return Result.failure(it)
        }
        scheduler.markSemanticRun(clock())
        val analysis = segmentationAnalyzer.analyze(
            segmentation = segmentationOutput.mask,
            stats = segmentationOutput.analysisStats,
        )
        cachedSemanticAnalysis = CachedSemanticAnalysis(
            frameWidth = frame.width,
            frameHeight = frame.height,
            rotationDegrees = frame.rotationDegrees,
            analysis = analysis,
            runtimeInfo = segmentationOutput.runtimeInfo,
        )
        return Result.success(
            SemanticAnalysisOutput(
                analysis = analysis,
                preprocessTimeMs = segmentationOutput.preprocessTimeMs,
                inferenceTimeMs = segmentationOutput.modelTimeMs,
                outputReadTimeMs = segmentationOutput.outputReadTimeMs,
                postprocessTimeMs = segmentationOutput.postprocessTimeMs,
                runtimeInfo = segmentationOutput.runtimeInfo,
            )
        )
    }
}
