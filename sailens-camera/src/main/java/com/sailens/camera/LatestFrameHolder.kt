package com.sailens.camera

import com.sailens.core.frame.ImageFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull

/**
 * Holds the most recent converted frame and answers [FrameSnapshotProvider] queries about it.
 *
 * Separate from [ImageFrameAnalyzer] because this is the part with a decision in it -- is the
 * frame still fresh enough to describe? -- while the analyzer is the part with CameraX in it.
 * That keeps the freshness rule unit-testable without an ImageProxy.
 *
 * Written from the camera analysis thread and read from whichever thread asks, hence the
 * [MutableStateFlow]: it is safe across threads and lets a waiter be woken by the next frame
 * instead of polling.
 */
internal class LatestFrameHolder(
    private val elapsedRealtimeMs: () -> Long,
) {
    private val latest = MutableStateFlow<TimedFrame?>(null)

    fun record(frame: ImageFrame) {
        latest.value = TimedFrame(frame, elapsedRealtimeMs())
    }

    /**
     * @return the held frame when it is no older than [maxAgeMs], otherwise null. A negative
     *   bound admits nothing; zero admits only a frame recorded in this same millisecond.
     */
    fun currentFrame(maxAgeMs: Long): ImageFrame? = latest.value.freshOrNull(maxAgeMs)

    /**
     * Suspends until a held frame satisfies [maxAgeMs]. The caller owns the timeout — and the
     * demand that makes new frames arrive at all.
     */
    suspend fun awaitFrame(maxAgeMs: Long): ImageFrame =
        latest.mapNotNull { it.freshOrNull(maxAgeMs) }.first()

    private fun TimedFrame?.freshOrNull(maxAgeMs: Long): ImageFrame? {
        if (maxAgeMs < 0) return null
        val timed = this ?: return null
        val age = elapsedRealtimeMs() - timed.recordedAtMs
        return timed.frame.takeIf { age in 0..maxAgeMs }
    }

    private data class TimedFrame(val frame: ImageFrame, val recordedAtMs: Long)
}
