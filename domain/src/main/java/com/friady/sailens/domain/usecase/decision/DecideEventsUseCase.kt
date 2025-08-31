package com.friady.sailens.domain.usecase.decision

import com.friady.sailens.domain.model.analysis.SceneSnapshot
import com.friady.sailens.domain.model.scene.SceneEvent
import com.friady.sailens.domain.processor.decision.CooldownManager
import com.friady.sailens.domain.processor.decision.EventConflictResolver
import com.friady.sailens.domain.processor.decision.EventGenerator
import com.friady.sailens.domain.processor.decision.EventMerger
import com.friady.sailens.domain.util.Timestamp

/**
 * 决策事件用例
 */
class DecideEventsUseCase(
    private val eventGenerator: EventGenerator,
    private val conflictResolver: EventConflictResolver,
    private val eventMerger: EventMerger,
    private val cooldownManager: CooldownManager,
) {
    operator fun invoke(snapshot: SceneSnapshot): List<SceneEvent> {
        val now = Timestamp.now()

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