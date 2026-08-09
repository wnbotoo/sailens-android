package com.sailens.domain.model.scene

import com.sailens.domain.model.common.DirectionBias
import com.sailens.domain.model.common.DirectionZone
import com.sailens.domain.model.common.DistanceLevel
import com.sailens.domain.model.common.EventCategory
import com.sailens.domain.model.common.EventPriority
import com.sailens.domain.model.common.Severity
import java.util.UUID

/**
 * 导航事件。
 *
 * [messageKey] 只描述"是什么/在哪个方位"。播报时还会拼上两个修饰：
 * - [distance] 为 [DistanceLevel.NEAR] 时加紧迫前缀（"注意，"）
 * - [directionHint] 保留给未来具有侧向连通证据的可执行后缀（"，靠左"）
 *
 * 当前事件生成器不会设置 [directionHint]：现有 suggestedBias 只是通行片段质心偏移，
 * 不能证明建议侧与用户脚下连通。字段暂时保留，避免破坏 trace/replay 数据兼容性。
 */
data class SceneEvent(
    val id: UUID = UUID.randomUUID(),
    val timestamp: Long,
    val category: EventCategory,
    val priority: EventPriority,
    val messageKey: String,
    val messageParams: Map<String, String> = emptyMap(),
    val expiresAt: Long,
    val dedupeKey: String,
    val cooldownKeys: Set<String> = emptySet(),
    val confidence: Float = 1.0f,
    val severity: Severity = Severity.MODERATE,
    val relatedZones: List<DirectionZone> = emptyList(),
    val distance: DistanceLevel? = null,
    val directionHint: DirectionBias? = null,
) {
    fun isExpired(now: Long): Boolean = now > expiresAt

    val isNear: Boolean get() = distance == DistanceLevel.NEAR
}
