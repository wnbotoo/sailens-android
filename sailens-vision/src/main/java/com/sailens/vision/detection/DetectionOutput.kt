package com.sailens.vision.detection

import com.sailens.core.runtime.MlRuntimeInfo

/**
 * One detection inference: what the model saw, plus the timings traces are built from.
 *
 * Deliberately generic. Detections carry class id, label, confidence and box and nothing else;
 * what a class *means* is resolved by the consumer after inference (architecture.md 6.3).
 */
data class DetectionOutput(
    val detections: List<Detection>,
    val preprocessTimeMs: Long = 0,
    val inferenceTimeMs: Long = 0,
    val outputReadTimeMs: Long = 0,
    val postprocessTimeMs: Long = 0,
    val runtimeInfo: MlRuntimeInfo = MlRuntimeInfo(),
)
