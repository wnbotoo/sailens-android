package com.sailens.guidance.repository

import com.sailens.guidance.model.common.DistanceLevel
import com.sailens.core.geometry.NormalizedRect

/**
 * 深度估计仓库接口
 */
interface DepthRepository {
    fun estimateDistance(boundingBox: NormalizedRect): DistanceLevel
}