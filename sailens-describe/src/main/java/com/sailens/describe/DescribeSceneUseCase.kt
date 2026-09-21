package com.sailens.describe

import com.sailens.camera.FrameSnapshotProvider
import com.sailens.vlm.SceneDescriber
import com.sailens.vlm.SceneDescriptionChunk
import com.sailens.vlm.SceneDescriptionRequest
import com.sailens.core.log.LogService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

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
public class DescribeSceneUseCase(
    private val sceneDescriber: SceneDescriber,
    private val frameSnapshots: FrameSnapshotProvider,
    private val logService: LogService,
) {

    /**
     * @param maxFrameAgeMs 快照的新鲜度上限；超过就当作没有画面。
     * @param snapshotTimeoutMs 等相机出一张够新的帧最多等多久；等不到就报错而不是继续等。
     * @param userPrompt 用户的具体问题；为 null 时用引擎的系统提示词（"描述正前方最重要的东西"）。
     * @return 流式的描述分片；集合被取消即中止生成。失败以异常形式抛给调用方，由它决定怎么告知
     *   用户——**这条链路上的失败必须被说出来或震出来，不能只写日志**：用户已经主动发问，
     *   没有回答和"前方什么都没有"在他那里是同一种体验。
     */
    public operator fun invoke(
        userPrompt: String? = null,
        maxFrameAgeMs: Long = DEFAULT_MAX_FRAME_AGE_MS,
        snapshotTimeoutMs: Long = FrameSnapshotProvider.DEFAULT_SNAPSHOT_TIMEOUT_MS,
    ): Flow<SceneDescriptionChunk> = flow {
        if (!sceneDescriber.isReady) {
            // 首次调用才加载模型：VLM 权重比 sem/det 大得多，进程启动时就加载会拖慢冷启动，
            // 而多数会话根本不会用到它。initialize() 失败时直接抛，调用方负责告知。
            logService.info(TAG, "Loading VLM on first scene-description request")
            sceneDescriber.initialize()
        }

        // 只取当前这一帧，并且只接受足够新的那一张（architecture.md §6.1）。描述一张几秒前的
        // 画面比不回答更糟：用户看不见画面已经过期，会拿旧信息当成眼前的现实。
        //
        // awaitCurrentFrame 会自己开一张 frame lease：相机一直在出帧，但只有有人要的时候才做
        // 转换，所以导航停着的时候这里必须先把需求声明出来，否则永远拿不到帧。等不到就立刻
        // 失败，而不是挂在那里——用户按了按钮，沉默是最坏的回答。
        val frame = frameSnapshots.awaitCurrentFrame(
            maxAgeMs = maxFrameAgeMs,
            timeoutMs = snapshotTimeoutMs,
        ) ?: error(
            "No camera frame newer than ${maxFrameAgeMs}ms arrived within ${snapshotTimeoutMs}ms"
        )

        emitAll(sceneDescriber.describe(SceneDescriptionRequest(frame, userPrompt)))
    }

    private companion object {
        const val TAG = "DescribeScene"

        /** 相机在推帧时快照是毫秒级新鲜的；超过这个值说明相机没在跑，或者画面已经不算"眼前"。 */
        const val DEFAULT_MAX_FRAME_AGE_MS = 2_000L
    }
}
