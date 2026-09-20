package com.sailens.data.repository

import com.sailens.data.source.ml.semantic.SegmentationModel
import com.sailens.core.frame.ImageFrame
import com.sailens.guidance.model.perception.SegmentationOutput
import com.sailens.guidance.repository.PerceptionRepository

/**
 * 感知仓库实现
 */
class MLPerceptionRepository(
    private val segmentationModel: SegmentationModel,
) : PerceptionRepository {

    override val isInitialized: Boolean
        get() = segmentationModel.isInitialized

    override suspend fun initialize() {
        segmentationModel.initialize()
    }

    override suspend fun segment(frame: ImageFrame): Result<SegmentationOutput> {
        return segmentationModel.segment(frame)
    }

    override suspend fun release() {
        segmentationModel.release()
    }

}
