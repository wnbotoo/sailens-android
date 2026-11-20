package com.sailens.core.frame

/** Capture checkpoints on the same elapsed-realtime clock as [ImageFrame.receivedElapsedRealtimeNanos]. */
public data class FrameIngressTiming(
    val copyStartedNanos: Long,
    val copyFinishedNanos: Long,
    val offeredNanos: Long,
)

/** Opt-in pipeline timing. Hosts that omit this binding leave the timing path idle. */
public data class PipelineTimingConfig(val mode: PipelineTimingMode?) {
    public val enabled: Boolean get() = mode != null
}

public enum class PipelineTimingMode(public val traceName: String) {
    RACE("race"),
    SEM_FIRST("sem-first"),
}
