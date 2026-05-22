package com.friady.sailens.domain.repository

import com.friady.sailens.domain.model.perception.ImageFrame
import com.friady.sailens.domain.model.perception.InstanceSegmentationOutput

/**
 * 实例分割提供者接口（V2 可插拔）
 */
interface InstanceSegmentationProvider {

    /**
     * 是否已初始化
     */
    val isInitialized: Boolean

    /**
     * 初始化
     */
    suspend fun initialize()

    /**
     * 检测实例
     */
    suspend fun detect(frame: ImageFrame): InstanceSegmentationOutput

    /**
     * 释放资源
     */
    fun release()
}
