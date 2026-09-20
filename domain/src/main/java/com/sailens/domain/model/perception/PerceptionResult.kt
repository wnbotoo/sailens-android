package com.sailens.domain.model.perception
import com.sailens.core.runtime.MlRuntimeInfo

import com.sailens.core.mask.BinaryMask
import com.sailens.core.mask.BottomStats
import com.sailens.domain.model.common.ObstacleRunKind

/**
 * 感知结果
 */
data class PerceptionResult(
    val timestamp: Long,
    val passableMask: BinaryMask,
    val obstacleMask: BinaryMask,
    val obstacles: List<DetectedObstacle>,
    val obstacleDetections: List<ObstacleDetection>,
    val bottomStats: BottomStats,
    val analysis: SegmentationAnalysis,
    val inferenceTimeMs: Long,
    val semanticPreprocessTimeMs: Long = 0,
    val semanticInferenceTimeMs: Long = 0,
    val semanticOutputReadTimeMs: Long = 0,
    val semanticPostprocessTimeMs: Long = 0,
    val semanticRuntimeInfo: MlRuntimeInfo = MlRuntimeInfo(),
    val obstaclePreprocessTimeMs: Long = 0,
    val obstacleInferenceTimeMs: Long = 0,
    val obstacleOutputReadTimeMs: Long = 0,
    val obstaclePostprocessTimeMs: Long = 0,
    val obstacleRuntimeInfo: MlRuntimeInfo = MlRuntimeInfo(),
    /** 本帧 obstacle 槽位是否实际运行 det（det / none） */
    val obstacleRunKind: ObstacleRunKind = ObstacleRunKind.NONE,
)
