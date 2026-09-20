package com.sailens.data.source.ml.obstacle

import com.sailens.core.frame.ImageFrame
import com.sailens.core.runtime.MlRuntimeInfo
import com.sailens.domain.model.perception.ObstacleModelOutput
import com.sailens.domain.repository.ObstacleProvider

class DisabledObstacleProvider : ObstacleProvider {
    override val isInitialized: Boolean = false

    override suspend fun initialize() = Unit

    override suspend fun detect(frame: ImageFrame): ObstacleModelOutput {
        return ObstacleModelOutput(
            detections = emptyList(),
            runtimeInfo = MlRuntimeInfo.unavailable("disabled"),
        )
    }

    override fun release() = Unit
}
