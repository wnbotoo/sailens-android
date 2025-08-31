package com.friady.sailens.domain.model.scene

import com.friady.sailens.domain.model.common.BinaryMask

data class SceneResult(
    val passableMask: BinaryMask?,
    val events: List<SceneEvent>,
)
