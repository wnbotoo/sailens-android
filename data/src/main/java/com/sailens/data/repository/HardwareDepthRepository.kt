package com.sailens.data.repository

import com.sailens.data.source.depth.HardwareDepthSource
import com.sailens.domain.model.common.DistanceLevel
import com.sailens.core.geometry.NormalizedRect
import com.sailens.domain.repository.DepthRepository

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