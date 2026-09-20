package com.sailens.vision.detection

import com.sailens.core.geometry.NormalizedRect

/**
 * What a detection model saw: a class from its taxonomy, how sure it is, and where.
 *
 * Deliberately free of any navigation meaning (architecture.md §6.3). Whether class 2 is a hazard
 * to the person holding the phone is a Guidance judgement, resolved from NavigationSemantics after
 * inference -- so this type stays reusable by a product that asks a different question of the same
 * model.
 */
data class Detection(
    val classId: Int,
    val label: String,
    val confidence: Float,
    val boundingBox: NormalizedRect,
)
