package com.friady.sailens.domain.model.perception

import com.friady.sailens.domain.model.common.BinaryMask
import com.friady.sailens.domain.model.common.BottomStats

/**
 * 感知结果
 */
data class PerceptionResult(
    val timestamp: Long,
    val passableMask: BinaryMask,
    val obstacleMask: BinaryMask,
    val obstacles: List<DetectedObstacle>,
    val bottomStats: BottomStats,
    val analysis: SegmentationAnalysis,
    val inferenceTimeMs: Long,
)

