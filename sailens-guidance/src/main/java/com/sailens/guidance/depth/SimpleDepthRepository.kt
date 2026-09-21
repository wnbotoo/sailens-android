package com.sailens.guidance.depth

import com.sailens.guidance.depth.ImagePositionDepthEstimator
import com.sailens.guidance.model.common.DistanceLevel
import com.sailens.core.geometry.NormalizedRect
import com.sailens.guidance.repository.DepthRepository

class SimpleDepthRepository(
    private val imagePositionEstimator: ImagePositionDepthEstimator,
) : DepthRepository {
    override fun estimateDistance(boundingBox: NormalizedRect): DistanceLevel {
        return imagePositionEstimator.estimate(boundingBox)
    }
}