package com.sailens.domain.repository

import com.sailens.core.frame.ImageFrame
import com.sailens.domain.model.perception.SegmentationOutput

/**
 * 感知仓库接口
 */
interface PerceptionRepository {
    val isInitialized: Boolean
    suspend fun initialize()
    suspend fun segment(frame: ImageFrame): Result<SegmentationOutput>
    suspend fun release()
}
