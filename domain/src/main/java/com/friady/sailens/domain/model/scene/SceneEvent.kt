package com.friady.sailens.domain.model.scene

import com.friady.sailens.domain.model.common.DirectionZone
import com.friady.sailens.domain.model.common.EventCategory
import com.friady.sailens.domain.model.common.EventPriority
import com.friady.sailens.domain.model.common.Severity
import java.util.UUID

/**
 * 导航事件
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
    val confidence: Float = 1.0f,
    val severity: Severity = Severity.MODERATE,
    val relatedZones: List<DirectionZone> = emptyList(),
) {
    fun isExpired(now: Long): Boolean = now > expiresAt
}