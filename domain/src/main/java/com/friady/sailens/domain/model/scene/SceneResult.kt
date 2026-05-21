package com.friady.sailens.domain.model.scene

import com.friady.sailens.domain.model.common.BinaryMask
import com.friady.sailens.domain.model.perception.SegmentationMask

data class SceneResult(
    val passableMask: BinaryMask?,
    val segmentationMask: SegmentationMask? = null,
    val debugInfo: SceneDebugInfo? = null,
    val events: List<SceneEvent>,
)
