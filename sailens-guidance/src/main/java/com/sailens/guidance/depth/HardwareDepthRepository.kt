package com.sailens.guidance.depth

import com.sailens.guidance.depth.HardwareDepthSource
import com.sailens.guidance.model.common.DistanceLevel
import com.sailens.core.geometry.NormalizedRect
import com.sailens.guidance.repository.DepthRepository

class HardwareDepthRepository(
    private val hardwareDepthSource: HardwareDepthSource,
) : DepthRepository {
    override fun estimateDistance(boundingBox: NormalizedRect): DistanceLevel {
        val depthMeters = hardwareDepthSource.getDepthAt(boundingBox.centerX, boundingBox.maxY)
        return depthToDistanceLevel(depthMeters)
    }

    private fun depthToDistanceLevel(depthMeters: Float): DistanceLevel {
        return when {
            depthMeters < 1.5f -> DistanceLevel.NEAR
            depthMeters < 4.0f -> DistanceLevel.MEDIUM
            else -> DistanceLevel.FAR
        }
    }
}