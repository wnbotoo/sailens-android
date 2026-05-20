package com.friady.sailens.domain.usecase.scene

import com.friady.sailens.domain.model.trace.FrameTrace
import com.friady.sailens.domain.model.trace.SessionTraceAccumulator
import com.friady.sailens.domain.model.trace.SessionTraceMetadata
import com.friady.sailens.domain.model.perception.ImageFrame
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

private const val TARGET_HARDWARE_PROFILE = "snapdragon_8_gen_3_plus"

private data class PipelineFrameResult(
    val sceneResult: SceneResult,
    val frameTrace: FrameTrace,
)


/**
 * 开始导航用例
 */
class StartSceneAnalysisUseCase(
    private val perceptionRepository: PerceptionRepository,
    private val instanceProvider: InstanceSegmentationProvider?,
    private val processFrameUseCase: ProcessFrameUseCase,
    private val analyzeSceneUseCase: AnalyzeSceneUseCase,
    private val decideEventsUseCase: DecideEventsUseCase,
    private val logService: LogService,
    private val traceService: TraceService,
) {
    suspend operator fun invoke(frameFlow: Flow<ImageFrame>): Flow<SceneResult> {
        val sessionId = UUID.randomUUID().toString()
        val sessionStartedAt = Timestamp.now()
        val accumulator = SessionTraceAccumulator(sessionId, sessionStartedAt)
        var lastSequenceNumber: Long? = null

        if (!perceptionRepository.isInitialized) {
            perceptionRepository.initialize()
            logService.info("Navigation", "Perception repository initialized")
        }

        traceService.startSession(
            SessionTraceMetadata(
                sessionId = sessionId,
                startedAt = sessionStartedAt,
                pipelineMode = if (instanceProvider != null) "combined" else "semantic_only",
                targetHardwareProfile = TARGET_HARDWARE_PROFILE,
            )
        )

        logService.info("Navigation", "Navigation started")

        // 2. 初始化实例分割提供者（可选）
        instanceProvider?.let {
            if (!it.isInitialized) {
                it.initialize()
                logService.info("Navigation", "Instance provider initialized")
            }
        }

        return frameFlow
            .mapNotNull { frame ->
                val pipelineStart = Timestamp.now()

                // 处理帧
                val perceptionResult = processFrameUseCase(frame).getOrElse {
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
                    obstacleCount = perceptionResult.obstacles.size,
                    eventCount = events.size,
                    isBlocked = sceneSnapshot.connectivity.isBlocked,
                    isNarrowing = sceneSnapshot.connectivity.isNarrowing,
                    isRoadDangerous = sceneSnapshot.roadSafety.isDangerous,
                    messageKeys = events.map { it.messageKey },
                )

                return@mapNotNull PipelineFrameResult(
                    sceneResult = SceneResult(
                        passableMask = perceptionResult.passableMask,
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