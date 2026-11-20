package com.sailens.guidance.repository

import com.sailens.core.frame.ImageFrame
import com.sailens.guidance.model.perception.SegmentationOutput
import com.sailens.runtime.InputPreprocessCache
import com.sailens.vision.ModelRunTiming

/**
 * 感知仓库接口
 */
interface PerceptionRepository {
    val isInitialized: Boolean
    suspend fun initialize()
    suspend fun segment(frame: ImageFrame): Result<SegmentationOutput>
    fun sharedFloatInput(frame: ImageFrame): InputPreprocessCache.SharedFloatInput? = null
    suspend fun segment(
        frame: ImageFrame,
        timing: ModelRunTiming?,
        preferredProducer: InputPreprocessCache.PreferredProducer? = null,
    ): Result<SegmentationOutput> = segment(frame)
    suspend fun release()
}
