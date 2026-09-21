package com.sailens.guidance.perception

import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.perception.ObstacleDetection
import com.sailens.guidance.semantics.NavigationSemantics
import com.sailens.vision.detection.Detection

/**
 * The §6.3 boundary in two functions: Vision reports classes, Guidance decides what they mean.
 *
 * Kept out of [DetectorObstacleProvider] so the mapping can be tested without a LiteRT session.
 */

/**
 * Class ids that map to a real obstacle category, used as the detector's allow-list so classes
 * this product cannot act on never reach NMS.
 */
internal fun NavigationSemantics.allowedObstacleClassIds(classCount: Int): IntArray =
    (0 until classCount)
        .filter { toObstacleCategory(it) != ObstacleCategory.UNKNOWN }
        .toIntArray()

/**
 * Attaches the navigation category. A class with no category is dropped here rather than inside
 * the detector, so the detector stays reusable by a product that cares about a different set of
 * classes.
 */
internal fun List<Detection>.toObstacleDetections(
    navigationSemantics: NavigationSemantics,
): List<ObstacleDetection> = mapNotNull { detection ->
    val category = navigationSemantics.toObstacleCategory(detection.classId)
    if (category == ObstacleCategory.UNKNOWN) return@mapNotNull null
    ObstacleDetection(
        classId = detection.classId,
        className = detection.label,
        confidence = detection.confidence,
        boundingBox = detection.boundingBox,
        category = category,
    )
}
