package com.friady.sailens.domain.config

data class PipelinePerformanceBudget(
    val targetP95TotalPipelineMs: Long = DEFAULT_TARGET_P95_TOTAL_PIPELINE_MS,
    val maxDroppedFrameRate: Double = DEFAULT_MAX_DROPPED_FRAME_RATE,
) {
    init {
        require(targetP95TotalPipelineMs > 0) {
            "Target p95 total pipeline time must be positive, got $targetP95TotalPipelineMs"
        }
        require(maxDroppedFrameRate in 0.0..1.0) {
            "Max dropped frame rate must be in [0, 1], got $maxDroppedFrameRate"
        }
    }

    companion object {
        const val DEFAULT_TARGET_P95_TOTAL_PIPELINE_MS = 85L
        const val DEFAULT_MAX_DROPPED_FRAME_RATE = 0.10
    }
}
