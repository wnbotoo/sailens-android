package com.friady.sailens.domain.model.scene

import com.friady.sailens.domain.model.common.BinaryMask
import com.friady.sailens.domain.model.perception.DetectedInstance
import com.friady.sailens.domain.model.perception.DetectedObstacle
import com.friady.sailens.domain.model.perception.SegmentationMask

data class SceneResult(
    val frameDisplayWidth: Int? = null,
    val frameDisplayHeight: Int? = null,
    val passableMask: BinaryMask?,
    val segmentationMask: SegmentationMask? = null,
    val obstacles: List<DetectedObstacle> = emptyList(),
    val instanceDetections: List<DetectedInstance> = emptyList(),
    val debugInfo: SceneDebugInfo? = null,
    val events: List<SceneEvent>,
)
