package com.friady.sailens.domain.repository

import com.friady.sailens.domain.model.common.DistanceLevel
import com.friady.sailens.domain.model.common.NormalizedRect

/**
 * 深度估计仓库接口
 */
interface DepthRepository {
    fun estimateDistance(boundingBox: NormalizedRect): DistanceLevel
}