package com.sailens.domain.model.perception

import com.sailens.core.geometry.NormalizedRect
import com.sailens.domain.model.common.ObstacleCategory

/**
 * 障碍物模型检测结果。
 */
data class ObstacleDetection(
    val classId: Int,
    val className: String,
    val confidence: Float,
    val boundingBox: NormalizedRect,
    val category: ObstacleCategory,
)
