package com.friady.sailens.data.repository

import com.friady.sailens.data.source.ml.segmentation.SegmentationModel
import com.friady.sailens.domain.model.perception.ImageFrame
import com.friady.sailens.domain.model.perception.SegmentationOutput
import com.friady.sailens.domain.repository.PerceptionRepository

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