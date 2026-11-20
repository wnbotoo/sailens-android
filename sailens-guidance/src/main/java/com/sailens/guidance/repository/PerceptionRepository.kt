package com.sailens.guidance.repository

import com.sailens.core.frame.ImageFrame
import com.sailens.guidance.model.perception.SegmentationOutput
import com.sailens.vision.ModelRunTiming

/**
 * 感知仓库接口
 */
interface PerceptionRepository {
    val isInitialized: Boolean
    suspend fun initialize()
    suspend fun segment(frame: ImageFrame): Result<SegmentationOutput>
    suspend fun segment(frame: ImageFrame, timing: ModelRunTiming?): Result<SegmentationOutput> = segment(frame)
    suspend fun release()
}
