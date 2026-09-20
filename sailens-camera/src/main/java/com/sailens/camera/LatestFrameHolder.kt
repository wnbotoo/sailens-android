package com.sailens.camera

import com.sailens.core.frame.ImageFrame
import java.util.concurrent.atomic.AtomicReference

/**
 * Holds the most recent converted frame and answers [FrameSnapshotProvider] queries about it.
 *
 * Separate from [ImageFrameAnalyzer] because this is the part with a decision in it -- is the
 * frame still fresh enough to describe? -- while the analyzer is the part with CameraX in it.
 * That keeps the freshness rule unit-testable without an ImageProxy.
 *
 * Written from the camera analysis thread and read from whichever thread asks, hence the atomic.
 */
internal class LatestFrameHolder(
    private val elapsedRealtimeMs: () -> Long,
) {
    private val latest = AtomicReference<TimedFrame?>(null)

    fun record(frame: ImageFrame) {
        latest.set(TimedFrame(frame, elapsedRealtimeMs()))
    }

    /**
     * @return the held frame when it is no older than [maxAgeMs], otherwise null. A negative
     *   bound admits nothing; zero admits only a frame recorded in this same millisecond.
     */
    fun currentFrame(maxAgeMs: Long): ImageFrame? {
        if (maxAgeMs < 0) return null
        val timed = latest.get() ?: return null
        val age = elapsedRealtimeMs() - timed.recordedAtMs
        return timed.frame.takeIf { age in 0..maxAgeMs }
    }

    private data class TimedFrame(val frame: ImageFrame, val recordedAtMs: Long)
}
