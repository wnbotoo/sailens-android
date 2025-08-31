package com.friady.sailens.data.source.ml.detection

import com.friady.sailens.domain.model.perception.DetectedInstance
import com.friady.sailens.domain.model.perception.ImageFrame
import com.friady.sailens.domain.repository.InstanceSegmentationProvider

/**
 * 空实现的实例分割提供者
 * 用于 V1 版本（只使用语义分割）
 */
class NoOpInstanceProvider : InstanceSegmentationProvider {

    override val isInitialized: Boolean = false

    override suspend fun initialize() {
        // 无需初始化
    }

    override suspend fun detect(frame: ImageFrame): List<DetectedInstance> {
        return emptyList()
    }

    override fun release() {
        // 无需释放
    }
}