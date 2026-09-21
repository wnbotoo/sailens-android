package com.sailens.guidance.model.analysis

import com.sailens.guidance.model.common.GroundType

/**
 * 地面类型变化
 */
data class GroundTypeChange(
    val from: GroundType,
    val to: GroundType,
)