package com.friady.sailens.data.source.ml.instance

import com.friady.sailens.domain.model.perception.ImageFrame
import com.friady.sailens.domain.model.perception.InstanceSegmentationOutput
import com.friady.sailens.domain.repository.InstanceSegmentationProvider

class DisabledInstanceSegmentationProvider : InstanceSegmentationProvider {
    override val isInitialized: Boolean = false

    override suspend fun initialize() = Unit

    override suspend fun detect(frame: ImageFrame): InstanceSegmentationOutput {
        return InstanceSegmentationOutput(emptyList())
    }

    override fun release() = Unit
}
