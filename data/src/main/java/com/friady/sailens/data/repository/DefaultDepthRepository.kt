package com.friady.sailens.data.repository

import com.friady.sailens.data.source.depth.HardwareDepthSource
import com.friady.sailens.data.source.depth.ImagePositionDepthEstimator
import com.friady.sailens.domain.model.common.DistanceLevel
import com.friady.sailens.domain.model.common.NormalizedRect
import com.friady.sailens.domain.repository.DepthRepository

class DefaultDepthRepository(
    private val imagePositionEstimator: ImagePositionDepthEstimator,
    private val hardwareDepthSource: HardwareDepthSource?,   // 可选，ToF/LiDAR
) : DepthRepository {

    override fun estimateDistance(boundingBox: NormalizedRect): DistanceLevel {
        // 优先使用硬件深度
        hardwareDepthSource?.getDepthAt(boundingBox.centerX, boundingBox.maxY)?.let {
            return depthToDistanceLevel(it)
        }

        // 回退到基于图像位置的估计
        return imagePositionEstimator.estimate(boundingBox)
    }

    private fun depthToDistanceLevel(depthMeters: Float): DistanceLevel {
        return when {
            depthMeters < 1.5f -> DistanceLevel.NEAR
            depthMeters < 4.0f -> DistanceLevel.MEDIUM
            else -> DistanceLevel.FAR
        }
    }
}