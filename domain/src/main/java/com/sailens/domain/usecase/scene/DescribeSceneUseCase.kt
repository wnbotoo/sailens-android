package com.sailens.domain.usecase.scene

import com.sailens.domain.model.perception.ImageFrame
import com.sailens.domain.repository.SceneDescriber
import com.sailens.domain.repository.SceneDescriptionChunk
import com.sailens.domain.repository.SceneDescriptionRequest
import com.sailens.domain.service.LogService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 用户主动发问一次："我面前是什么？"
 *
 * 与 [StartSceneAnalysisUseCase] 的关系是并列而非包含：那条是连续的、每帧跑的、回答"会不会
 * 撞上"；这条是一次性的、用户按下去才跑的、回答"这是什么"。两者共用相机帧流，但不共用管线，
 * 也不共用冷却——场景描述是用户要来的，不该被播报冷却压掉。
 *
 * 取帧而不是接收帧：调用方给出帧流，这里只取**当前最新一帧**。VLM 一次推理要数秒，期间画面
 * 早就变了，喂多帧毫无意义；而"用户按下按钮那一刻看到的东西"恰好就是他想问的东西。
 */
class DescribeSceneUseCase(
    private val sceneDescriber: SceneDescriber,
    private val logService: LogService,
) {

    /**
     * @param frameFlow 相机帧流，只会从里面取一帧。
     * @param userPrompt 用户的具体问题；为 null 时用引擎的系统提示词（"描述正前方最重要的东西"）。
     * @return 流式的描述分片；集合被取消即中止生成。失败以异常形式抛给调用方，由它决定怎么告知
     *   用户——**这条链路上的失败必须被说出来或震出来，不能只写日志**：用户已经主动发问，
     *   没有回答和"前方什么都没有"在他那里是同一种体验。
     */
    operator fun invoke(
        frameFlow: Flow<ImageFrame>,
        userPrompt: String? = null,
    ): Flow<SceneDescriptionChunk> = flow {
        if (!sceneDescriber.isReady) {
            // 首次调用才加载模型：VLM 权重比 sem/det 大得多，进程启动时就加载会拖慢冷启动，
            // 而多数会话根本不会用到它。initialize() 失败时直接抛，调用方负责告知。
            logService.info(TAG, "Loading VLM on first scene-description request")
            sceneDescriber.initialize()
        }

        // 帧流是 SharedFlow，没有重放；相机没在推帧时 first() 会永远挂着。加超时把它变成一个
        // 会明确失败的调用，否则用户按下按钮后得到的是无声的永久等待。
        val frame = withTimeoutOrNull(FRAME_WAIT_TIMEOUT_MS) { frameFlow.first() }
            ?: error("No camera frame available within ${FRAME_WAIT_TIMEOUT_MS}ms")

        emitAll(sceneDescriber.describe(SceneDescriptionRequest(frame, userPrompt)))
    }

    private companion object {
        const val TAG = "DescribeScene"

        /** 相机正常推帧时这是毫秒级的事；超过这个值说明相机根本没开。 */
        const val FRAME_WAIT_TIMEOUT_MS = 2_000L
    }
}
