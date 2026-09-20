package com.sailens.data.source.ml.semantic

import com.sailens.core.frame.ImageFrame
import com.sailens.guidance.model.perception.SegmentationOutput

/**
 * 语义分割模型
 */
interface SegmentationModel {
    val isInitialized: Boolean
    suspend fun initialize()
    suspend fun segment(frame: ImageFrame): Result<SegmentationOutput>
    suspend fun release()
}
