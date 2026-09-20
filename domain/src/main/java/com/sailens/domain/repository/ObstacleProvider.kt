package com.sailens.domain.repository

import com.sailens.core.frame.ImageFrame
import com.sailens.domain.model.perception.ObstacleModelOutput

/**
 * Pluggable obstacle detection provider. The realtime pipeline currently consumes bbox/class/
 * confidence detections only; shape-level occlusion is derived later from tracked boxes plus the
 * semantic passable mask.
 */
interface ObstacleProvider {
    val isInitialized: Boolean

    suspend fun initialize()

    suspend fun detect(frame: ImageFrame): ObstacleModelOutput

    fun release()
}
