package com.sailens.guidance.depth

import com.sailens.core.geometry.NormalizedRect
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.repository.DepthRepository

/**
 * Today's level from the box's image position, and the ground-contact estimate beside it.
 * The contact may make the level nearer, never farther ([DistanceFusion]); off by default, it is
 * still computed so the trace shows it.
 */
class DefaultDepthRepository(
    private val imagePositionEstimator: ImagePositionDepthEstimator,
    private val groundContactEstimator: GroundContactDistanceEstimator,
) : DepthRepository {

    override fun estimateDistance(boundingBox: NormalizedRect, category: ObstacleCategory, frame: DistanceFrame): DistanceEstimate {
        val today = imagePositionEstimator.estimate(boundingBox)
        val contact = groundContactEstimator.estimate(boundingBox, category, frame)
        val contactLevel = contact.lowerM?.let { DistanceFusion.level(it, frame.config) }
        val used = DistanceFusion.fuse(
            today = today,
            contact = contact,
            contactLevel = contactLevel,
            enabled = frame.config.enabled,
            heightSource = frame.geometry?.phone?.source,
        )
        return DistanceEstimate(used, today, contact, contactLevel)
    }
}
