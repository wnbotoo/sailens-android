package com.sailens.guidance.usecase.decision

import com.sailens.guidance.model.analysis.SceneSnapshot
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.processor.decision.CooldownManager
import com.sailens.guidance.processor.decision.EventConflictResolver
import com.sailens.guidance.processor.decision.EventGenerator
import com.sailens.guidance.processor.decision.EventMerger
import com.sailens.guidance.repository.DeviceSensorRepository

/**
 * 决策事件用例
 *
 * 返回按优先级排序的事件，**第一条是本帧要送达的那条**。冷却只记这一条：同帧的其余事件用户
 * 听不到，记进冷却就等于把它们静音一个冷却周期。送达方如果最终没能把第一条送出去（例如更高
 * 优先级的播报还在念），用 [RevokeUndeliveredEventUseCase] 撤销这次记录。
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
) {
    operator fun invoke(snapshot: SceneSnapshot): List<SceneEvent> {
        val now = clock()

        // 0. 把运动状态喂给冷却器：站着不动时同一个场景不该被反复播报。
        cooldownManager.setStationary(deviceSensorRepository.isStationary.value)

        // 1.  生成原始事件
        val rawEvents = eventGenerator.generate(snapshot, now)

        // 2. 冲突解决
        val resolvedEvents = conflictResolver.resolve(rawEvents)

        // 3. 事件合并
        val mergedEvents = eventMerger.merge(resolvedEvents)

        // 4. 冷却过滤
        val filteredEvents = cooldownManager.filter(mergedEvents, now)

        // 5. 按优先级排序（稳定排序：同优先级保持生成顺序）
        val prioritized = filteredEvents.sortedByDescending { it.priority.value }

        // 6. 只为要送达的那条记录冷却
        prioritized.firstOrNull()?.let { cooldownManager.recordEvent(it, now) }

        return prioritized
    }
}

/**
 * 送达方的回执：[DecideEventsUseCase] 选出的那条事件最终没有送到用户。撤销它的冷却记录，
 * 让它在条件仍然成立时下一帧重新放行。
 */
class RevokeUndeliveredEventUseCase(
    private val cooldownManager: CooldownManager,
) {
    operator fun invoke(event: SceneEvent) {
        cooldownManager.revoke(event.id)
    }
}
