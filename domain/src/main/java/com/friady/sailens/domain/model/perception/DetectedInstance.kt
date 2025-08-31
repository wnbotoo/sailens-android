package com.friady.sailens.domain.model.perception

import com.friady.sailens.domain.model.common.BinaryMask
import com.friady.sailens.domain.model.common.NormalizedRect
import com.friady.sailens.domain.model.common.ObstacleCategory

/**
 * 检测到的实例
 */
data class DetectedInstance(
    val classId: Int,
    val className: String,
    val confidence: Float,
    val boundingBox: NormalizedRect,
    val mask: BinaryMask?,
    val category: ObstacleCategory,
)
