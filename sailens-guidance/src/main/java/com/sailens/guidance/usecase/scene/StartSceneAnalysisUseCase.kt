package com.sailens.guidance.usecase.scene

import com.sailens.guidance.config.PerceptionConfig
import com.sailens.guidance.config.PipelinePerformanceBudget
import com.sailens.guidance.model.common.ObstacleRunKind
import com.sailens.guidance.processor.perception.PerceptionProfileManager
import com.sailens.guidance.config.TraceRuntimeConfig
import com.sailens.guidance.model.trace.FrameTrace
import com.sailens.guidance.model.trace.SessionTraceAccumulator
import com.sailens.guidance.model.trace.SessionTraceMetadata
import com.sailens.core.frame.ImageFrame
import com.sailens.guidance.model.scene.SceneDebugInfo
import com.sailens.guidance.model.perception.SegmentationMask
import com.sailens.guidance.model.scene.SceneResult
import com.sailens.guidance.processor.analysis.FrameQualityAnalyzer
import com.sailens.guidance.repository.ObstacleProvider
import com.sailens.guidance.repository.PerceptionRepository
import com.sailens.core.log.LogService
import com.sailens.guidance.service.TraceService
import com.sailens.guidance.usecase.decision.DecideEventsUseCase
import com.sailens.guidance.usecase.perception.AnalyzeSceneUseCase
import com.sailens.guidance.usecase.perception.ProcessFrameUseCase
import com.sailens.guidance.util.Timestamp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import java.util.UUID
import kotlin.math.abs

private data class PipelineFrameResult(
    val sceneResult: SceneResult,
    val frameTrace: FrameTrace,
)


/**
 * 开始导航用例
 */
class StartSceneAnalysisUseCase(
    private val profileManager: PerceptionProfileManager,
    private val perceptionRepository: PerceptionRepository,
    private val realtimeObstacleProvider: ObstacleProvider,
    private val processFrameUseCase: ProcessFrameUseCase,
    private val analyzeSceneUseCase: AnalyzeSceneUseCase,
    private val decideEventsUseCase: DecideEventsUseCase,
    private val frameQualityAnalyzer: FrameQualityAnalyzer,
    private val logService: LogService,
    private val traceService: TraceService,
    private val traceRuntimeConfig: TraceRuntimeConfig,
    private val pipelineBudget: PipelinePerformanceBudget,
) {
    /**
     * @param semanticMaskSnapshot asked once per frame whether the caller wants
     *   [SceneResult.segmentationMask]. The pipeline's own mask is reused once a newer semantic
     *   run replaces it, so what goes out is a copy the caller owns -- made only when asked, and
     *   shared by the frames that reuse one semantic run.
     */
    suspend operator fun invoke(
        frameFlow: Flow<ImageFrame>,
        semanticMaskSnapshot: () -> Boolean = { false },
    ): Flow<SceneResult> = flow {
        // 会话开始时把设置页选中的挡位落成运行配置；必须在 obstacle provider 初始化之前，
        // 且先于首帧处理（ProcessFrameUseCase 逐帧读取 activeConfig）
        val perceptionConfig = profileManager.activateSelected()
        val sessionId = UUID.randomUUID().toString()
        val sessionStartedAt = Timestamp.now()
        val accumulator = if (traceRuntimeConfig.enabled) {
            SessionTraceAccumulator(sessionId, sessionStartedAt)
        } else {
            null
        }
        val runtimeWindow = PipelineRuntimeWindow()
        frameQualityAnalyzer.reset()
        var lastSequenceNumber: Long? = null
        var lastFrameTimestamp: Long? = null
        var lastPipelineCompletedAt: Long? = null
        var lastNavigationPassableRatio: Double? = null
        var lastRoadRatio: Double? = null
        var lastBlockageConfidence: Double? = null
        var lastVerticalReachRatio: Double? = null
        var lastFloodReachRatio: Double? = null
        var lastWidthRetentionP25: Double? = null
        var lastOccludedPassableRatio: Double? = null
        var lastObstacleCount: Int? = null
        var lastRawObstacleDetectionCountOnRun: Int? = null
        var traceSessionStarted = false
        var consecutiveFrameFailures = 0
        val maskSnapshots = SegmentationMaskSnapshots()
        processFrameUseCase.reset()

        try {
        if (!perceptionRepository.isInitialized) {
            perceptionRepository.initialize()
            check(perceptionRepository.isInitialized) {
                "Perception repository initialization completed but repository is still not initialized"
            }
            logService.info("Navigation", "Perception repository initialized")
        }

        if (traceRuntimeConfig.enabled) {
            traceService.startSession(
                SessionTraceMetadata(
                    sessionId = sessionId,
                    startedAt = sessionStartedAt,
                    pipelineMode = perceptionConfig.profile.name.lowercase(),
                    targetHardwareProfile = perceptionConfig.targetHardwareProfile,
                )
            )
            traceSessionStarted = true
        }

        logService.info("Navigation", "Navigation started")
        logService.info(
            "Navigation",
            "Perception config",
            mapOf(
                "profile" to perceptionConfig.profile.name,
                "semanticProvider" to perceptionConfig.semanticProviderType.name,
                "realtimeObstacleProvider" to perceptionConfig.realtimeObstacleProviderType.name,
                "semanticTargetFps" to perceptionConfig.semanticTargetFps,
                "detectionTargetFps" to perceptionConfig.detectionTargetFps,
                "runtimeProfile" to perceptionConfig.runtimeProfileName,
                "targetHardwareProfile" to perceptionConfig.targetHardwareProfile,
            ),
        )

        // 2. 初始化障碍物模型提供者（挡位决定是否参与运行）
        if (perceptionConfig.detectionEnabled) {
            initializeObstacleProvider(
                provider = realtimeObstacleProvider,
                label = "Realtime obstacle provider",
            )
        }

        emitAll(
            frameFlow
            .mapNotNull { frame ->
                val pipelineStart = Timestamp.now()

                // 处理帧
                val perceptionResult = processFrameUseCase(frame).getOrElse {
                    traceService.recordError(sessionId, "process_frame", it)
                    logService.error(
                        "Perception",
                        "Failed to process frame ${frame.sequenceNumber}",
                        it,
                    )
                    // 单帧失败丢掉、试下一帧；连续失败说明管线已经不工作了，结束会话让上层告警，
                    // 而不是一直"运行"着却什么都不播。
                    consecutiveFrameFailures++
                    if (consecutiveFrameFailures >= pipelineBudget.maxConsecutiveFrameFailures) {
                        throw GuidancePipelineFailedException(consecutiveFrameFailures, it)
                    }
                    return@mapNotNull null
                }
                consecutiveFrameFailures = 0
                val processFrameCompletedAt = Timestamp.now()

                val analyzeStartedAt = processFrameCompletedAt

                logService.debug(
                    "Perception", "Frame processed", mapOf(
                        "obstacles" to perceptionResult.obstacles.size,
                        "inferenceMs" to perceptionResult.inferenceTimeMs
                    )
                )

                // 分析场景。帧质量单独判定后回填：它看的是原始画面而非模型输出，
                // 恰恰要在模型"照常给出结果"时告诉用户这些结果不可信。
                val sceneSnapshot = analyzeSceneUseCase(perceptionResult).copy(
                    frameQuality = frameQualityAnalyzer.analyze(frame),
                )
                val analyzeCompletedAt = Timestamp.now()

                // 决策事件
                val events = decideEventsUseCase(sceneSnapshot)
                val decideCompletedAt = Timestamp.now()

                val droppedFrames = lastSequenceNumber?.let { previousSequence ->
                    (frame.sequenceNumber - previousSequence - 1).coerceAtLeast(0).toInt()
                } ?: 0
                lastSequenceNumber = frame.sequenceNumber
                val cameraFrameIntervalMs = lastFrameTimestamp?.let { previousTimestamp ->
                    frameTimestampDeltaMs(previousTimestamp, frame.timestamp)
                } ?: 0
                lastFrameTimestamp = frame.timestamp
                val pipelineOutputIntervalMs = lastPipelineCompletedAt?.let { previousCompletedAt ->
                    (decideCompletedAt - previousCompletedAt).coerceAtLeast(0)
                } ?: 0
                lastPipelineCompletedAt = decideCompletedAt

                val navigationPassableRatio = perceptionResult.analysis.navigationPassableRatio.toDouble()
                val roadRatio = perceptionResult.analysis.roadRatio.toDouble()
                val blockageConfidence = sceneSnapshot.connectivity.blockageConfidence.toDouble()
                val blockageReason = sceneSnapshot.connectivity.blockageReason
                val verticalReachRatio = sceneSnapshot.connectivity.verticalReachRatio.toDouble()
                val floodReachRatio = sceneSnapshot.connectivity.floodReachRatio.toDouble()
                val widthRetentionP25 = sceneSnapshot.connectivity.widthRetentionP25.toDouble()
                val occludedPassableRatio = sceneSnapshot.occludedPassableRatio.toDouble()
                val obstacleCount = perceptionResult.obstacles.size
                val rawObstacleDetectionCount = perceptionResult.obstacleDetections.size
                val ranObstacleInference = perceptionResult.obstacleRunKind != ObstacleRunKind.NONE
                val rawObstacleClassNames = if (ranObstacleInference) {
                    perceptionResult.obstacleDetections.map { it.className }
                } else {
                    emptyList()
                }
                val trackedObstacleCategories =
                    perceptionResult.obstacles.map { it.category.name }
                val navigationPassableDelta =
                    lastNavigationPassableRatio?.let { abs(navigationPassableRatio - it) } ?: 0.0
                val roadRatioDelta = lastRoadRatio?.let { abs(roadRatio - it) } ?: 0.0
                val blockageConfidenceDelta =
                    lastBlockageConfidence?.let { abs(blockageConfidence - it) } ?: 0.0
                val verticalReachDelta =
                    lastVerticalReachRatio?.let { abs(verticalReachRatio - it) } ?: 0.0
                val floodReachDelta =
                    lastFloodReachRatio?.let { abs(floodReachRatio - it) } ?: 0.0
                val widthRetentionP25Delta =
                    lastWidthRetentionP25?.let { abs(widthRetentionP25 - it) } ?: 0.0
                val occludedPassableDelta =
                    lastOccludedPassableRatio?.let { abs(occludedPassableRatio - it) } ?: 0.0
                val obstacleCountDelta = lastObstacleCount?.let { abs(obstacleCount - it) } ?: 0
                val rawObstacleDetectionCountDelta = if (ranObstacleInference) {
                    lastRawObstacleDetectionCountOnRun?.let { abs(rawObstacleDetectionCount - it) } ?: 0
                } else {
                    0
                }
                lastNavigationPassableRatio = navigationPassableRatio
                lastRoadRatio = roadRatio
                lastBlockageConfidence = blockageConfidence
                lastVerticalReachRatio = verticalReachRatio
                lastFloodReachRatio = floodReachRatio
                lastWidthRetentionP25 = widthRetentionP25
                lastOccludedPassableRatio = occludedPassableRatio
                lastObstacleCount = obstacleCount
                if (ranObstacleInference) {
                    lastRawObstacleDetectionCountOnRun = rawObstacleDetectionCount
                }

                val frameTrace = FrameTrace(
                    sessionId = sessionId,
                    sequenceNumber = frame.sequenceNumber,
                    frameTimestamp = frame.timestamp,
                    frameWidth = frame.width,
                    frameHeight = frame.height,
                    droppedFramesSinceLast = droppedFrames,
                    processFrameMs = processFrameCompletedAt - pipelineStart,
                    inferenceMs = perceptionResult.inferenceTimeMs,
                    analyzeSceneMs = analyzeCompletedAt - analyzeStartedAt,
                    decideEventsMs = decideCompletedAt - analyzeCompletedAt,
                    totalPipelineMs = decideCompletedAt - pipelineStart,
                    pipelineStartedAt = pipelineStart,
                    pipelineCompletedAt = decideCompletedAt,
                    cameraFrameIntervalMs = cameraFrameIntervalMs,
                    pipelineOutputIntervalMs = pipelineOutputIntervalMs,
                    obstacleCount = obstacleCount,
                    eventCount = events.size,
                    isBlocked = sceneSnapshot.connectivity.isBlocked,
                    isNarrowing = sceneSnapshot.connectivity.isNarrowing,
                    isRoadDangerous = sceneSnapshot.roadSafety.isDangerous,
                    navigationPassableRatio = navigationPassableRatio,
                    navigationPassableDelta = navigationPassableDelta,
                    roadRatio = roadRatio,
                    roadRatioDelta = roadRatioDelta,
                    blockageConfidence = blockageConfidence,
                    blockageConfidenceDelta = blockageConfidenceDelta,
                    blockageReason = blockageReason,
                    verticalReachRatio = verticalReachRatio,
                    verticalReachDelta = verticalReachDelta,
                    floodReachRatio = floodReachRatio,
                    floodReachDelta = floodReachDelta,
                    widthRetentionP25 = widthRetentionP25,
                    widthRetentionP25Delta = widthRetentionP25Delta,
                    occludedPassableRatio = occludedPassableRatio,
                    groundRecognition = sceneSnapshot.groundRecognition.name.lowercase(),
                    unrecognizedGroundRatio = sceneSnapshot.unrecognizedGroundRatio.toDouble(),
                    occludedPassableDelta = occludedPassableDelta,
                    rawObstacleDetectionCount = rawObstacleDetectionCount,
                    obstacleCountDelta = obstacleCountDelta,
                    rawObstacleDetectionCountDelta = rawObstacleDetectionCountDelta,
                    rawObstacleClassNames = rawObstacleClassNames,
                    trackedObstacleCategories = trackedObstacleCategories,
                    roadVehicleSource = sceneSnapshot.roadSafety.vehicleOnRoadSource.name.lowercase(),
                    roadVehicleConfidence = sceneSnapshot.roadSafety.vehicleOnRoadConfidence.toDouble(),
                    roadVehicleReason = sceneSnapshot.roadSafety.vehicleOnRoadReason.name.lowercase(),
                    roadVehicleBottomY = sceneSnapshot.roadSafety.vehicleOnRoadBottomY.toDouble(),
                    roadVehicleCenterBandOverlap = sceneSnapshot.roadSafety.vehicleOnRoadCenterBandOverlap.toDouble(),
                    roadVehicleAreaRatio = sceneSnapshot.roadSafety.vehicleOnRoadAreaRatio.toDouble(),
                    dominantClasses = perceptionResult.analysis.dominantClassNames,
                    dominantClassPercentages = perceptionResult.analysis.dominantClassPercentages,
                    messageKeys = events.map { it.messageKey },
                    semanticPreprocessMs = perceptionResult.semanticPreprocessTimeMs,
                    semanticInferenceMs = perceptionResult.semanticInferenceTimeMs,
                    semanticOutputReadMs = perceptionResult.semanticOutputReadTimeMs,
                    semanticPostprocessMs = perceptionResult.semanticPostprocessTimeMs,
                    semanticAccelerator = perceptionResult.semanticRuntimeInfo.accelerator,
                    semanticAcceleratorSelection = perceptionResult.semanticRuntimeInfo.acceleratorSelection,
                    semanticPreprocessBackend = perceptionResult.semanticRuntimeInfo.preprocessBackend,
                    semanticPostprocessBackend = perceptionResult.semanticRuntimeInfo.postprocessBackend,
                    obstaclePreprocessMs = perceptionResult.obstaclePreprocessTimeMs,
                    obstacleInferenceMs = perceptionResult.obstacleInferenceTimeMs,
                    obstacleOutputReadMs = perceptionResult.obstacleOutputReadTimeMs,
                    obstaclePostprocessMs = perceptionResult.obstaclePostprocessTimeMs,
                    obstacleRunKind = perceptionResult.obstacleRunKind.traceName,
                    obstacleAccelerator = perceptionResult.obstacleRuntimeInfo.accelerator,
                    obstacleAcceleratorSelection = perceptionResult.obstacleRuntimeInfo.acceleratorSelection,
                    obstaclePreprocessBackend = perceptionResult.obstacleRuntimeInfo.preprocessBackend,
                    obstaclePostprocessBackend = perceptionResult.obstacleRuntimeInfo.postprocessBackend,
                )
                val runtimeStats = runtimeWindow.record(frameTrace, pipelineBudget)

                return@mapNotNull PipelineFrameResult(
                    sceneResult = SceneResult(
                        sequenceNumber = frame.sequenceNumber,
                        pipelineCompletedAt = decideCompletedAt,
                        frameDisplayWidth = frame.displayWidth(),
                        frameDisplayHeight = frame.displayHeight(),
                        passableMask = perceptionResult.passableMask,
                        segmentationMask = if (semanticMaskSnapshot()) {
                            maskSnapshots.of(perceptionResult.analysis.segmentation)
                        } else {
                            null
                        },
                        obstacles = perceptionResult.obstacles,
                        obstacleDetections = perceptionResult.obstacleDetections,
                        debugInfo = SceneDebugInfo(
                            semanticProvider = perceptionConfig.semanticProviderType.name,
                            obstacleProvider = obstacleProviderSummary(perceptionConfig),
                            perceptionProfile = perceptionConfig.profile.name,
                            semanticRuntimeInfo = perceptionResult.semanticRuntimeInfo,
                            obstacleRuntimeInfo = perceptionResult.obstacleRuntimeInfo,
                            passableRatio = perceptionResult.analysis.passablePixelCount.toFloat() /
                                (perceptionResult.analysis.width * perceptionResult.analysis.height),
                            navigationPassableRatio = perceptionResult.analysis.navigationPassableRatio,
                            navigationPassableDelta = navigationPassableDelta.toFloat(),
                            obstacleRatio = perceptionResult.analysis.obstaclePixelCount.toFloat() /
                                (perceptionResult.analysis.width * perceptionResult.analysis.height),
                            roadRatio = perceptionResult.analysis.roadRatio,
                            roadRatioDelta = roadRatioDelta.toFloat(),
                            bottomCoverage = perceptionResult.analysis.bottomStats.coverage,
                            bottomMaxRunWidthRatio = perceptionResult.analysis.bottomStats.maxRunWidthRatio,
                            blockageConfidence = sceneSnapshot.connectivity.blockageConfidence,
                            blockageConfidenceDelta = blockageConfidenceDelta.toFloat(),
                            blockageReason = blockageReason,
                            occludedPassableRatio = sceneSnapshot.occludedPassableRatio,
                            groundRecognition = sceneSnapshot.groundRecognition.name.lowercase(),
                            unrecognizedGroundRatio = sceneSnapshot.unrecognizedGroundRatio,
                            verticalReachRatio = sceneSnapshot.connectivity.verticalReachRatio,
                            verticalReachDelta = verticalReachDelta.toFloat(),
                            floodReachRatio = sceneSnapshot.connectivity.floodReachRatio,
                            floodReachDelta = floodReachDelta.toFloat(),
                            widthRetentionP25 = sceneSnapshot.connectivity.widthRetentionP25,
                            widthRetentionP25Delta = widthRetentionP25Delta.toFloat(),
                            validLayers = sceneSnapshot.connectivity.validLayers,
                            totalLayers = sceneSnapshot.connectivity.totalLayers,
                            dominantClasses = perceptionResult.analysis.dominantClassPercentages
                                .ifEmpty { perceptionResult.analysis.dominantClassNames },
                            processFrameMs = frameTrace.processFrameMs,
                            inferenceMs = frameTrace.inferenceMs,
                            analyzeSceneMs = frameTrace.analyzeSceneMs,
                            decideEventsMs = frameTrace.decideEventsMs,
                            totalPipelineMs = frameTrace.totalPipelineMs,
                            droppedFramesSinceLast = frameTrace.droppedFramesSinceLast,
                            recentAvgTotalPipelineMs = runtimeStats.avgTotalPipelineMs,
                            recentP95TotalPipelineMs = runtimeStats.p95TotalPipelineMs,
                            recentDroppedFrameRate = runtimeStats.droppedFrameRate,
                            isRuntimeOverBudget = runtimeStats.isOverBudget,
                            trackedObstacleCount = obstacleCount,
                            rawObstacleDetectionCount = rawObstacleDetectionCount,
                            obstacleCountDelta = obstacleCountDelta,
                            rawObstacleDetectionCountDelta = rawObstacleDetectionCountDelta,
                            rawObstacleClassNames = rawObstacleClassNames,
                            trackedObstacleCategories = trackedObstacleCategories,
                            roadVehicleSource = sceneSnapshot.roadSafety.vehicleOnRoadSource.name.lowercase(),
                            roadVehicleConfidence = sceneSnapshot.roadSafety.vehicleOnRoadConfidence,
                            roadVehicleReason = sceneSnapshot.roadSafety.vehicleOnRoadReason.name.lowercase(),
                            roadVehicleBottomY = sceneSnapshot.roadSafety.vehicleOnRoadBottomY,
                            roadVehicleCenterBandOverlap = sceneSnapshot.roadSafety.vehicleOnRoadCenterBandOverlap,
                            roadVehicleAreaRatio = sceneSnapshot.roadSafety.vehicleOnRoadAreaRatio,
                        ),
                        events = events
                    ),
                    frameTrace = frameTrace,
                )
            }
            .onEach { result ->
                if (traceSessionStarted) {
                    accumulator?.record(result.frameTrace)
                    // The first event is the prompt the output side offers and records an outcome
                    // for; that frame is kept whatever the sampling, so the outcome can join to it.
                    val offeredPrompt = result.sceneResult.events.isNotEmpty()
                    if (traceRuntimeConfig.shouldRecordFrame(result.frameTrace.sequenceNumber, offeredPrompt)) {
                        traceService.recordFrame(result.frameTrace)
                    }
                }

                val events = result.sceneResult.events
                // 输出事件
                if (events.isNotEmpty()) {
                    logService.info(
                        "Decision", "Events generated", mapOf(
                            "count" to events.size,
                            "events" to events.map { it.category.name }
                        ))
                }
            }
            .map { it.sceneResult }
        )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (traceSessionStarted) {
                traceService.recordError(sessionId, "navigation_flow", error)
            }
            logService.error("Navigation", "Error in navigation flow", error)
            throw error
        } finally {
            if (traceSessionStarted && accumulator != null) {
                traceService.finishSession(
                    accumulator.build(completedAt = Timestamp.now())
                )
            }
        }
        // 把整条感知/分析/决策链路移出收集者所在的 Main 线程：ML 推理本就各自切到 IO
        // dispatcher，但推理之间的域分析、决策、FrameTrace 构造与 trace 编码原本会回到 Main。
        // 下沉到 Default 后，UI 更新仍留在收集端（ViewModel 的 collectLatest）。
    }.flowOn(Dispatchers.Default)

    private suspend fun initializeObstacleProvider(
        provider: ObstacleProvider,
        label: String,
    ) {
        if (provider.isInitialized) return

        provider.initialize()
        check(provider.isInitialized) {
            "$label initialization completed but provider is still not initialized"
        }
        logService.info("Navigation", "$label initialized")
    }
}

/** The pipeline failed on [consecutiveFailures] frames in a row; [cause] is the last failure. */
class GuidancePipelineFailedException(
    val consecutiveFailures: Int,
    cause: Throwable,
) : IllegalStateException("Guidance failed on $consecutiveFailures consecutive frames", cause)

/**
 * Caller-owned copies of the pipeline's semantic mask. Frames that reuse one semantic run share
 * its copy, so an overlay that shows the mask costs one copy per run, not one per frame.
 */
internal class SegmentationMaskSnapshots {
    // Compared by identity only, never read: its array may already carry a later run.
    private var source: SegmentationMask? = null
    private var snapshot: SegmentationMask? = null

    fun of(mask: SegmentationMask): SegmentationMask {
        snapshot?.takeIf { mask === source }?.let { return it }
        return SegmentationMask(mask.width, mask.height, mask.classMap.copyOf()).also {
            source = mask
            snapshot = it
        }
    }
}

private fun obstacleProviderSummary(config: PerceptionConfig): String {
    return if (config.detectionEnabled) config.realtimeObstacleProviderType.name else "NONE"
}

private fun ImageFrame.displayWidth(): Int {
    return if (rotationDegrees == 90 || rotationDegrees == 270) height else width
}

private fun ImageFrame.displayHeight(): Int {
    return if (rotationDegrees == 90 || rotationDegrees == 270) width else height
}

private fun frameTimestampDeltaMs(previousTimestamp: Long, currentTimestamp: Long): Long {
    val delta = currentTimestamp - previousTimestamp
    if (delta <= 0) return 0
    return if (delta > 1_000_000L) delta / 1_000_000L else delta
}

private data class PipelineRuntimeStats(
    val avgTotalPipelineMs: Double,
    val p95TotalPipelineMs: Long,
    val droppedFrameRate: Double,
    val isOverBudget: Boolean,
)

private class PipelineRuntimeWindow(
    private val capacity: Int = 30,
) {
    private val totalPipelineTimes = LongArray(capacity)
    private val droppedFrames = IntArray(capacity)
    private var nextIndex = 0
    private var size = 0

    fun record(
        frameTrace: FrameTrace,
        budget: PipelinePerformanceBudget,
    ): PipelineRuntimeStats {
        totalPipelineTimes[nextIndex] = frameTrace.totalPipelineMs
        droppedFrames[nextIndex] = frameTrace.droppedFramesSinceLast
        nextIndex = (nextIndex + 1) % capacity
        if (size < capacity) size++

        var totalPipelineMs = 0L
        var totalDroppedFrames = 0
        val sortedTotals = LongArray(size)
        for (index in 0 until size) {
            val total = totalPipelineTimes[index]
            sortedTotals[index] = total
            totalPipelineMs += total
            totalDroppedFrames += droppedFrames[index]
        }
        sortedTotals.sort()

        val p95Index = if (size == 0) {
            0
        } else {
            kotlin.math.ceil(size * 0.95).toInt().coerceIn(1, size) - 1
        }
        val observedFrames = size + totalDroppedFrames
        val droppedFrameRate = if (observedFrames > 0) {
            totalDroppedFrames.toDouble() / observedFrames
        } else {
            0.0
        }
        val p95 = sortedTotals.getOrElse(p95Index) { 0L }

        return PipelineRuntimeStats(
            avgTotalPipelineMs = if (size > 0) totalPipelineMs.toDouble() / size else 0.0,
            p95TotalPipelineMs = p95,
            droppedFrameRate = droppedFrameRate,
            isOverBudget = p95 > budget.targetP95TotalPipelineMs ||
                droppedFrameRate > budget.maxDroppedFrameRate,
        )
    }
}
