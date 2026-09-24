package com.sailens.guidance.model.scene

import com.sailens.guidance.model.common.DirectionBias
import com.sailens.guidance.model.common.DirectionZone
import com.sailens.guidance.model.common.DistanceLevel
import com.sailens.guidance.model.common.EventCategory
import com.sailens.guidance.model.common.EventPriority
import com.sailens.guidance.model.common.Severity
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
 *
 * [timestamp] 与 [expiresAt] 用**单调时钟**（`SystemClock.elapsedRealtime()`），不是墙钟：
 * 冷却和过期判定都拿它们做差，墙钟被 NTP/手动改时间往回拨时，差值变负会把提示压到时钟追回来为止。
 * [messageKey] 必须是 [SceneEventMessageKeys.all] 里的一个。
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
    /**
     * 冷却按组判定：任意一组全部通过（或优先级升级）即放行。空表示只有一组 = [cooldownKeys]。
     * 合并事件每个组成事件各占一组，这样"有一个方位是新的"就足以让合并提示放行。
     */
    val cooldownGroups: List<Set<String>> = emptyList(),
    val confidence: Float = 1.0f,
    val severity: Severity = Severity.MODERATE,
    val relatedZones: List<DirectionZone> = emptyList(),
    val distance: DistanceLevel? = null,
    val directionHint: DirectionBias? = null,
) {
    fun isExpired(now: Long): Boolean = now > expiresAt

    val isNear: Boolean get() = distance == DistanceLevel.NEAR
}
