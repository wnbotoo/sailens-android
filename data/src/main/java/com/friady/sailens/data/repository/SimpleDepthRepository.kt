package com.friady.sailens.data.repository

import com.friady.sailens.data.source.depth.ImagePositionDepthEstimator
import com.friady.sailens.domain.model.common.DistanceLevel
import com.friady.sailens.domain.model.common.NormalizedRect
import com.friady.sailens.domain.repository.DepthRepository

class SimpleDepthRepository(
    private val imagePositionEstimator: ImagePositionDepthEstimator,
) : DepthRepository {
    override fun estimateDistance(boundingBox: NormalizedRect): DistanceLevel {
        return imagePositionEstimator.estimate(boundingBox)
    }
}