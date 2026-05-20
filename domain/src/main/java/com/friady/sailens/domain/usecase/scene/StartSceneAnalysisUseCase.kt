package com.friady.sailens.domain.usecase.scene

import com.friady.sailens.domain.model.perception.ImageFrame
import com.friady.sailens.domain.model.scene.SceneResult
import com.friady.sailens.domain.repository.InstanceSegmentationProvider
import com.friady.sailens.domain.repository.PerceptionRepository
import com.friady.sailens.domain.service.LogService
import com.friady.sailens.domain.usecase.decision.DecideEventsUseCase
import com.friady.sailens.domain.usecase.perception.AnalyzeSceneUseCase
import com.friady.sailens.domain.usecase.perception.ProcessFrameUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach


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
) {
    suspend operator fun invoke(frameFlow: Flow<ImageFrame>): Flow<SceneResult> {
        if (!perceptionRepository.isInitialized) {
            perceptionRepository.initialize()
            logService.info("Navigation", "Perception repository initialized")
        }

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
                // 处理帧
                val perceptionResult = processFrameUseCase(frame).getOrElse {
                    return@mapNotNull null
                }

                logService.debug(
                    "Perception", "Frame processed", mapOf(
                        "obstacles" to perceptionResult.obstacles.size,
                        "inferenceMs" to perceptionResult.inferenceTimeMs
                    )
                )

                // 分析场景
                val sceneSnapshot = analyzeSceneUseCase(perceptionResult)

                // 决策事件
                val events = decideEventsUseCase(sceneSnapshot)

                return@mapNotNull SceneResult(
                    passableMask = perceptionResult.passableMask,
                    events = events
                )
            }
            .onEach { result ->
                val events = result.events
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
                logService.error("Navigation", "Error in navigation flow", e)
                throw e
            }
    }
}