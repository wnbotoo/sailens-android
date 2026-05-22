package com.friady.sailens.data.source.ml.semantic

import com.friady.sailens.domain.model.perception.ImageFrame
import com.friady.sailens.domain.model.perception.SegmentationOutput

/**
 * 语义分割模型
 */
interface SegmentationModel {
    val isInitialized: Boolean
    suspend fun initialize()
    suspend fun segment(frame: ImageFrame): Result<SegmentationOutput>
    suspend fun release()
}

data class SegmenterConfig(
    val inputWidth: Int,
    val inputHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val outputChannels: Int,
    val mean: Triple<Float, Float, Float>,
    val std: Triple<Float, Float, Float>,
    val confidenceThreshold: Float = 0f,
)
