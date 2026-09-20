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

    fun shouldRecordFrame(sequenceNumber: Long): Boolean {
        return enabled && sequenceNumber % sampleEveryNFrames == 0L
    }
}
