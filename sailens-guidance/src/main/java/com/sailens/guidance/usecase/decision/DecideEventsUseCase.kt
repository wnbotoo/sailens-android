package com.sailens.guidance.usecase.decision

import com.sailens.guidance.model.analysis.SceneSnapshot
import com.sailens.guidance.model.analysis.VisionFaults
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.processor.decision.CooldownManager
import com.sailens.guidance.processor.decision.EventConflictResolver
import com.sailens.guidance.processor.decision.EventGenerator
import com.sailens.guidance.processor.decision.EventMerger
import com.sailens.guidance.repository.DeviceSensorRepository
import com.sailens.guidance.trace.regression.RegressionRecorder

/**
 * 决策事件用例
 *
 * 返回按优先级排序的事件，**第一条是本帧要送达的那条**。冷却只记这一条：同帧的其余事件用户
 * 听不到，记进冷却就等于把它们静音一个冷却周期。这条记录是暂记：送达方送到了就用
 * [ConfirmDeliveredEventUseCase] 确认，没能送出去（例如更高优先级的播报还在念）就用
 * [RevokeUndeliveredEventUseCase] 撤销。
 *
 * @param clock 单调时钟（生产环境是 `SystemClock.elapsedRealtime()`）。冷却和过期都靠时间差，
 *   墙钟回拨会让差值变负、把提示压到时钟追回来为止。
 */
class DecideEventsUseCase(
    private val eventGenerator: EventGenerator,
    private val conflictResolver: EventConflictResolver,
    private val eventMerger: EventMerger,
    private val cooldownManager: CooldownManager,
    private val deviceSensorRepository: DeviceSensorRepository,
    private val clock: () -> Long,
    private val regressionRecorder: RegressionRecorder = RegressionRecorder.None,
) {
    /**
     * Which of the states Guidance must tell the user about hold in [snapshot] (det-first plan D5), by
     * the generator's own test. Decides nothing and changes nothing.
     */
    fun visionFaults(snapshot: SceneSnapshot): VisionFaults = eventGenerator.visionFaults(snapshot)

    operator fun invoke(snapshot: SceneSnapshot): List<SceneEvent> {
        val now = clock()
        val stationary = deviceSensorRepository.isStationary.value
        if (regressionRecorder.isRecording) regressionRecorder.onStationary(stationary)

        // 1.  生成原始事件
        val rawEvents = eventGenerator.generate(snapshot, now)

        // 2. 冲突解决
        val resolvedEvents = conflictResolver.resolve(rawEvents)

        // 3. 事件合并
        val mergedEvents = eventMerger.merge(resolvedEvents)

        // 冷却的操作作为一个整体（见 CooldownManager.decide）：撤销只能落在两次决策之间。
        return cooldownManager.decide {
            // 把运动状态喂给冷却器：站着不动时同一个场景不该被反复播报。
            setStationary(stationary)

            // 4. 冷却过滤
            val filteredEvents = filter(mergedEvents, now)

            // 5. 按优先级排序（稳定排序：同优先级保持生成顺序）
            val prioritized = filteredEvents.sortedByDescending { it.priority.value }

            // 6. 只为要送达的那条记录冷却
            prioritized.firstOrNull()?.let { recordEvent(it, now) }

            prioritized
        }
    }
}

/**
 * 送达方的回执：[DecideEventsUseCase] 选出的那条事件至少有一个通道接收了。它的冷却记录从此不能
 * 再撤回。不改变任何冷却判断，所以不进 M0b 录制：回放只按录下的撤回复现（见 [CooldownManager.confirm]）。
 */
class ConfirmDeliveredEventUseCase(
    private val cooldownManager: CooldownManager,
) {
    operator fun invoke(event: SceneEvent) {
        cooldownManager.confirm(event.id)
    }
}

/**
 * 送达方的回执：[DecideEventsUseCase] 选出的那条事件最终没有送到用户。撤销它的冷却记录，
 * 让它在条件仍然成立时下一帧重新放行。
 */
class RevokeUndeliveredEventUseCase(
    private val cooldownManager: CooldownManager,
    private val regressionRecorder: RegressionRecorder = RegressionRecorder.None,
) {
    operator fun invoke(event: SceneEvent) {
        val decisionsBefore = cooldownManager.revoke(event.id)
        if (regressionRecorder.isRecording) regressionRecorder.onRevoke(event.id, decisionsBefore)
    }
}
