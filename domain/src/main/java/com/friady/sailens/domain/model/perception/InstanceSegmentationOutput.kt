package com.friady.sailens.domain.model.perception

data class InstanceSegmentationOutput(
    val instances: List<DetectedInstance>,
    val preprocessTimeMs: Long = 0,
    val inferenceTimeMs: Long = 0,
    val outputReadTimeMs: Long = 0,
    val postprocessTimeMs: Long = 0,
)
