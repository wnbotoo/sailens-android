package com.friady.sailens.domain.model.analysis

import com.friady.sailens.domain.model.common.GroundType

/**
 * 地面类型变化
 */
data class GroundTypeChange(
    val from: GroundType,
    val to: GroundType,
)