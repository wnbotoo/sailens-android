package com.sailens.guidance.repository

import com.sailens.core.geometry.NormalizedRect
import com.sailens.guidance.depth.DistanceEstimate
import com.sailens.guidance.depth.DistanceFrame
import com.sailens.guidance.model.common.ObstacleCategory

/**
 * How far an obstacle is: a [com.sailens.guidance.model.common.DistanceLevel] for speech, with the
 * evidence behind it for the trace.
 */
interface DepthRepository {
    fun estimateDistance(boundingBox: NormalizedRect, category: ObstacleCategory, frame: DistanceFrame): DistanceEstimate
}
