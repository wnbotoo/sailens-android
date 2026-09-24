package com.sailens.guidance.config

data class PipelinePerformanceBudget(
    val targetP95TotalPipelineMs: Long = DEFAULT_TARGET_P95_TOTAL_PIPELINE_MS,
    val maxDroppedFrameRate: Double = DEFAULT_MAX_DROPPED_FRAME_RATE,
    /**
     * How many frames in a row may fail before the session is ended as failed. A single failed
     * frame is dropped and the next one tried; a run of them means the pipeline is not working,
     * and a session that silently keeps "running" produces no prompts at all -- which a blind user
     * cannot tell apart from a clear path.
     */
    val maxConsecutiveFrameFailures: Int = DEFAULT_MAX_CONSECUTIVE_FRAME_FAILURES,
) {
    init {
        require(targetP95TotalPipelineMs > 0) {
            "Target p95 total pipeline time must be positive, got $targetP95TotalPipelineMs"
        }
        require(maxDroppedFrameRate in 0.0..1.0) {
            "Max dropped frame rate must be in [0, 1], got $maxDroppedFrameRate"
        }
        require(maxConsecutiveFrameFailures > 0) {
            "Max consecutive frame failures must be positive, got $maxConsecutiveFrameFailures"
        }
    }

    companion object {
        const val DEFAULT_TARGET_P95_TOTAL_PIPELINE_MS = 85L
        const val DEFAULT_MAX_DROPPED_FRAME_RATE = 0.10
        const val DEFAULT_MAX_CONSECUTIVE_FRAME_FAILURES = 30
    }
}
