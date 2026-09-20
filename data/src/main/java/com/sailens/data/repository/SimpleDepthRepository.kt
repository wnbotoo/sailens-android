package com.sailens.data.repository

import com.sailens.data.source.depth.ImagePositionDepthEstimator
import com.sailens.domain.model.common.DistanceLevel
import com.sailens.core.geometry.NormalizedRect
import com.sailens.domain.repository.DepthRepository

class SimpleDepthRepository(
    private val imagePositionEstimator: ImagePositionDepthEstimator,
) : DepthRepository {
    override fun estimateDistance(boundingBox: NormalizedRect): DistanceLevel {
        return imagePositionEstimator.estimate(boundingBox)
    }
}