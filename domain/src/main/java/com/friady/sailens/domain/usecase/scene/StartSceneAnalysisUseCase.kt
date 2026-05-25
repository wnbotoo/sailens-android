package com.friady.sailens.domain.usecase.scene

import com.friady.sailens.domain.config.PerceptionConfig
import com.friady.sailens.domain.config.PipelinePerformanceBudget
import com.friady.sailens.domain.model.common.InstanceProviderType
import com.friady.sailens.domain.model.common.PerceptionMode
import com.friady.sailens.domain.model.trace.FrameTrace
import com.friady.sailens.domain.model.trace.SessionTraceAccumulator
import com.friady.sailens.domain.model.trace.SessionTraceMetadata
import com.friady.sailens.domain.model.perception.ImageFrame
import com.friady.sailens.domain.model.scene.SceneDebugInfo
import com.friady.sailens.domain.model.scene.SceneResult
import com.friady.sailens.domain.repository.InstanceSegmentationProvider
import com.friady.sailens.domain.repository.PerceptionRepository
import com.friady.sailens.domain.service.LogService
import com.friady.sailens.domain.service.TraceService
import com.friady.sailens.domain.usecase.decision.DecideEventsUseCase
import com.friady.sailens.domain.usecase.perception.AnalyzeSceneUseCase
import com.friady.sailens.domain.usecase.perception.ProcessFrameUseCase
import com.friady.sailens.domain.util.Timestamp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import java.util.UUID

private data class PipelineFrameResult(
    val sceneResult: SceneResult,
    val frameTrace: FrameTrace,
)


/**
 * 开始导航用例
 */
class StartSceneAnalysisUseCase(
    private val perceptionConfig: PerceptionConfig,
    private val perceptionRepository: PerceptionRepository,
    private val instanceProvider: InstanceSegmentationProvider,
    private val processFrameUseCase: ProcessFrameUseCase,
    private val analyzeSceneUseCase: AnalyzeSceneUseCase,
    private val decideEventsUseCase: DecideEventsUseCase,
    private val logService: LogService,
    private val traceService: TraceService,
    private val pipelineBudget: PipelinePerformanceBudget,
) {
    suspend operator fun invoke(frameFlow: Flow<ImageFrame>): Flow<SceneResult> {
        val sessionId = UUID.randomUUID().toString()
        val sessionStartedAt = Timestamp.now()
        val accumulator = SessionTraceAccumulator(sessionId, sessionStartedAt)
        val runtimeWindow = PipelineRuntimeWindow()
        var lastSequenceNumber: Long? = null
        var lastFrameTimestamp: Long? = null
        var lastPipelineCompletedAt: Long? = null
        processFrameUseCase.reset()

        if (!perceptionRepository.isInitialized) {
            perceptionRepository.initialize()
            check(perceptionRepository.isInitialized) {
                "Perception repository initialization completed but repository is still not initialized"
            }
            logService.info("Navigation", "Perception repository initialized")
        }

        traceService.startSession(
            SessionTraceMetadata(
                sessionId = sessionId,
                startedAt = sessionStartedAt,
                pipelineMode = perceptionConfig.mode.traceName(),
                targetHardwareProfile = perceptionConfig.targetHardwareProfile,
            )
        )

        logService.info("Navigation", "Navigation started")
        logService.info(
            "Navigation",
            "Perception config",
            mapOf(
                "mode" to perceptionConfig.mode.name,
                "semanticProvider" to perceptionConfig.semanticProviderType.name,
                "instanceProvider" to perceptionConfig.instanceProviderType.name,
                "inferenceStrategy" to perceptionConfig.inferenceStrategy.name,
                "runtimeProfile" to perceptionConfig.runtimeProfileName,
                "targetHardwareProfile" to perceptionConfig.targetHardwareProfile,
            ),
        )

        // 2. 初始化实例分割提供者
        if (
            perceptionConfig.mode == PerceptionMode.COMBINED &&
            perceptionConfig.instanceProviderType != InstanceProviderType.NONE
        ) {
            if (!instanceProvider.isInitialized) {
                instanceProvider.initialize()
                check(instanceProvider.isInitialized) {
                    "Instance provider initialization completed but provider is still not initialized"
                }
                logService.info("Navigation", "Instance provider initialized")
            }
        }

        return frameFlow
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
                    return@mapNotNull null
                }
                val processFrameCompletedAt = Timestamp.now()

                val analyzeStartedAt = processFrameCompletedAt

                logService.debug(
                    "Perception", "Frame processed", mapOf(
                        "obstacles" to perceptionResult.obstacles.size,
                        "inferenceMs" to perceptionResult.inferenceTimeMs
                    )
                )

                // 分析场景
                val sceneSnapshot = analyzeSceneUseCase(perceptionResult)
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
                    obstacleCount = perceptionResult.obstacles.size,
                    eventCount = events.size,
                    isBlocked = sceneSnapshot.connectivity.isBlocked,
                    isNarrowing = sceneSnapshot.connectivity.isNarrowing,
                    isRoadDangerous = sceneSnapshot.roadSafety.isDangerous,
                    navigationPassableRatio = perceptionResult.analysis.navigationPassableRatio.toDouble(),
                    blockageConfidence = sceneSnapshot.connectivity.blockageConfidence.toDouble(),
                    verticalReachRatio = sceneSnapshot.connectivity.verticalReachRatio.toDouble(),
                    floodReachRatio = sceneSnapshot.connectivity.floodReachRatio.toDouble(),
                    widthRetentionP25 = sceneSnapshot.connectivity.widthRetentionP25.toDouble(),
                    messageKeys = events.map { it.messageKey },
                    semanticPreprocessMs = perceptionResult.semanticPreprocessTimeMs,
                    semanticInferenceMs = perceptionResult.semanticInferenceTimeMs,
                    semanticOutputReadMs = perceptionResult.semanticOutputReadTimeMs,
                    semanticPostprocessMs = perceptionResult.semanticPostprocessTimeMs,
                    instancePreprocessMs = perceptionResult.instancePreprocessTimeMs,
                    instanceInferenceMs = perceptionResult.instanceInferenceTimeMs,
                    instanceOutputReadMs = perceptionResult.instanceOutputReadTimeMs,
                    instancePostprocessMs = perceptionResult.instancePostprocessTimeMs,
                )
                val runtimeStats = runtimeWindow.record(frameTrace, pipelineBudget)

                return@mapNotNull PipelineFrameResult(
                    sceneResult = SceneResult(
                        frameDisplayWidth = frame.displayWidth(),
                        frameDisplayHeight = frame.displayHeight(),
                        passableMask = perceptionResult.passableMask,
                        segmentationMask = perceptionResult.analysis.segmentation,
                        obstacles = perceptionResult.obstacles,
                        instanceDetections = perceptionResult.instanceDetections,
                        debugInfo = SceneDebugInfo(
                            semanticProvider = perceptionConfig.semanticProviderType.name,
                            instanceProvider = perceptionConfig.instanceProviderType.name,
                            inferenceStrategy = perceptionConfig.inferenceStrategy.name,
                            passableRatio = perceptionResult.analysis.passablePixelCount.toFloat() /
                                (perceptionResult.analysis.width * perceptionResult.analysis.height),
                            navigationPassableRatio = perceptionResult.analysis.navigationPassableRatio,
                            obstacleRatio = perceptionResult.analysis.obstaclePixelCount.toFloat() /
                                (perceptionResult.analysis.width * perceptionResult.analysis.height),
                            roadRatio = perceptionResult.analysis.roadRatio,
                            bottomCoverage = perceptionResult.analysis.bottomStats.coverage,
                            bottomMaxRunWidthRatio = perceptionResult.analysis.bottomStats.maxRunWidthRatio,
                            blockageConfidence = sceneSnapshot.connectivity.blockageConfidence,
                            verticalReachRatio = sceneSnapshot.connectivity.verticalReachRatio,
                            floodReachRatio = sceneSnapshot.connectivity.floodReachRatio,
                            widthRetentionP25 = sceneSnapshot.connectivity.widthRetentionP25,
                            validLayers = sceneSnapshot.connectivity.validLayers,
                            totalLayers = sceneSnapshot.connectivity.totalLayers,
                            dominantClasses = perceptionResult.analysis.dominantClassNames,
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
                            trackedObstacleCount = perceptionResult.obstacles.size,
                            rawInstanceCount = perceptionResult.instanceDetections.size,
                            rawInstanceMaskCount = perceptionResult.instanceDetections.count { it.mask != null },
                        ),
                        events = events
                    ),
                    frameTrace = frameTrace,
                )
            }
            .onEach { result ->
                accumulator.record(result.frameTrace)
                traceService.recordFrame(result.frameTrace)

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
            .catch { e ->
                traceService.recordError(sessionId, "navigation_flow", e)
                logService.error("Navigation", "Error in navigation flow", e)
                throw e
            }
            .onCompletion {
                traceService.finishSession(
                    accumulator.build(completedAt = Timestamp.now())
                )
            }
            .map { it.sceneResult }
    }
}

private fun ImageFrame.displayWidth(): Int {
    return if (rotationDegrees == 90 || rotationDegrees == 270) height else width
}

private fun ImageFrame.displayHeight(): Int {
    return if (rotationDegrees == 90 || rotationDegrees == 270) width else height
}

private fun PerceptionMode.traceName(): String {
    return when (this) {
        PerceptionMode.COMBINED -> "combined"
        PerceptionMode.SEMANTIC_ONLY -> "semantic_only"
    }
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
