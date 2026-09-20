package com.sailens.domain.model.perception
import com.sailens.core.runtime.MlRuntimeInfo

data class ObstacleModelOutput(
    val detections: List<ObstacleDetection>,
    val preprocessTimeMs: Long = 0,
    val inferenceTimeMs: Long = 0,
    val outputReadTimeMs: Long = 0,
    val postprocessTimeMs: Long = 0,
    val runtimeInfo: MlRuntimeInfo = MlRuntimeInfo(),
)
