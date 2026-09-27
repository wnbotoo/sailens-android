package com.sailens.guidance.config

data class TraceRuntimeConfig(
    val enabled: Boolean = false,
    val sampleEveryNFrames: Int = 1,
    val maxQueuedEntries: Int = 4000,
) {
    init {
        require(sampleEveryNFrames > 0) {
            "Trace sample interval must be positive, got $sampleEveryNFrames"
        }
        require(maxQueuedEntries > 0) {
            "Trace queue capacity must be positive, got $maxQueuedEntries"
        }
    }

    /**
     * Whether to write this frame's record. Sampling thins out ordinary frames, but a frame that
     * offered the user a prompt is always kept: its `prompt_outcome` record joins to it, and the
     * frame is the evidence for labelling that prompt.
     */
    fun shouldRecordFrame(sequenceNumber: Long, offeredPrompt: Boolean = false): Boolean {
        return enabled && (offeredPrompt || sequenceNumber % sampleEveryNFrames == 0L)
    }
}
