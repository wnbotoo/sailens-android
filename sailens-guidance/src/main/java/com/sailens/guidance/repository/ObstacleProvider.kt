package com.sailens.guidance.repository

import com.sailens.core.frame.ImageFrame
import com.sailens.guidance.model.perception.ObstacleModelOutput
import com.sailens.runtime.InputPreprocessCache
import com.sailens.vision.ModelRunTiming

/**
 * Pluggable obstacle detection provider. The realtime pipeline currently consumes bbox/class/
 * confidence detections only; shape-level occlusion is derived later from tracked boxes plus the
 * semantic passable mask.
 */
interface ObstacleProvider {
    val isInitialized: Boolean

    suspend fun initialize()

    suspend fun detect(frame: ImageFrame): ObstacleModelOutput
    suspend fun detect(frame: ImageFrame, timing: ModelRunTiming?): ObstacleModelOutput = detect(frame)
    fun sharedFloatInput(frame: ImageFrame): InputPreprocessCache.SharedFloatInput? = null

    fun release()
}
