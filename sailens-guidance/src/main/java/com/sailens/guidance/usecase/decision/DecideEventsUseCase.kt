package com.sailens.guidance.usecase.decision

import com.sailens.guidance.model.analysis.SceneSnapshot
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.processor.decision.CooldownManager
import com.sailens.guidance.processor.decision.EventConflictResolver
import com.sailens.guidance.processor.decision.EventGenerator
import com.sailens.guidance.processor.decision.EventMerger
import com.sailens.guidance.repository.DeviceSensorRepository
import com.sailens.guidance.util.Timestamp

/**
 * 决策事件用例
 */
class DecideEventsUseCase(
    private val eventGenerator: EventGenerator,
    private val conflictResolver: EventConflictResolver,
    private val eventMerger: EventMerger,
    private val cooldownManager: CooldownManager,
    private val deviceSensorRepository: DeviceSensorRepository,
) {
    operator fun invoke(snapshot: SceneSnapshot): List<SceneEvent> {
        val now = Timestamp.now()

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

        // 5. 记录冷却
        filteredEvents.forEach { cooldownManager.recordEvent(it, now) }

        // 6. 按优先级排序
        return filteredEvents.sortedByDescending { it.priority.value }
    }
}