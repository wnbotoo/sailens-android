package com.sailens.camera

import com.sailens.core.frame.ImageFrame
import kotlinx.coroutines.flow.Flow

/**
 * A continuous stream of camera frames, for work that runs on every frame.
 *
 * Guidance consumes this. Collecting it is what keeps capture converting, so a caller that only
 * wants to look at the current view should use [FrameSnapshotProvider] instead of subscribing
 * (architecture.md §6.1).
 */
interface FrameSource {
    val frames: Flow<ImageFrame>
}

/**
 * The current view, on demand and with an explicit freshness bound.
 *
 * Returns null rather than an old frame: describing what the camera saw several seconds ago is
 * worse than saying nothing, because the person asking cannot see that it is stale.
 *
 * This is deliberately not tied to CameraX -- another capture source can implement it later.
 *
 * **Known gap until step 9 of the migration:** the CameraX implementation only converts frames
 * while something is collecting [FrameSource.frames], so this returns null while Guidance is
 * stopped. Moving Describe onto it, and deciding whether capture should keep converting for
 * Describe alone, changes behaviour and belongs to that step rather than this one.
 */
interface FrameSnapshotProvider {
    /**
     * @param maxAgeMs how old the frame may be, in milliseconds.
     * @return the most recent frame if it is no older than [maxAgeMs], otherwise null.
     */
    fun currentFrame(maxAgeMs: Long): ImageFrame?
}
