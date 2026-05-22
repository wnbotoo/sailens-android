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
    val instanceDetections: List<DetectedInstance>,
    val bottomStats: BottomStats,
    val analysis: SegmentationAnalysis,
    val inferenceTimeMs: Long,
    val semanticPreprocessTimeMs: Long = 0,
    val semanticInferenceTimeMs: Long = 0,
    val semanticOutputReadTimeMs: Long = 0,
    val semanticPostprocessTimeMs: Long = 0,
    val instancePreprocessTimeMs: Long = 0,
    val instanceInferenceTimeMs: Long = 0,
    val instanceOutputReadTimeMs: Long = 0,
    val instancePostprocessTimeMs: Long = 0,
)
