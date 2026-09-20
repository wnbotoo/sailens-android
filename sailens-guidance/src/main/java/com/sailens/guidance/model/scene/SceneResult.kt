package com.sailens.guidance.model.scene

import com.sailens.core.mask.BinaryMask
import com.sailens.guidance.model.perception.ObstacleDetection
import com.sailens.guidance.model.perception.DetectedObstacle
import com.sailens.guidance.model.perception.SegmentationMask

data class SceneResult(
    val sequenceNumber: Long = 0L,
    val pipelineCompletedAt: Long = 0L,
    val frameDisplayWidth: Int? = null,
    val frameDisplayHeight: Int? = null,
    val passableMask: BinaryMask?,
    val segmentationMask: SegmentationMask? = null,
    val obstacles: List<DetectedObstacle> = emptyList(),
    val obstacleDetections: List<ObstacleDetection> = emptyList(),
    val debugInfo: SceneDebugInfo? = null,
    val events: List<SceneEvent>,
)
