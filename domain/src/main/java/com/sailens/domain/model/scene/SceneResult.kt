package com.sailens.domain.model.scene

import com.sailens.core.mask.BinaryMask
import com.sailens.domain.model.perception.ObstacleDetection
import com.sailens.domain.model.perception.DetectedObstacle
import com.sailens.domain.model.perception.SegmentationMask

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
