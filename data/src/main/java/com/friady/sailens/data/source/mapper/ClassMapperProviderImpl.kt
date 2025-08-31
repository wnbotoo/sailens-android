package com.friady.sailens.data.source.mapper

import com.friady.sailens.domain.config.PerceptionConfig
import com.friady.sailens.domain.model.common.InstanceProviderType
import com.friady.sailens.domain.model.perception.ClassMapper
import com.friady.sailens.domain.model.perception.ClassMapperProvider

/**
 * 类别映射器提供者实现
 */
class ClassMapperProviderImpl(
    private val config: PerceptionConfig,
) : ClassMapperProvider {

    private val cityscapesMapper = CityscapesClassMapper()
    private val cocoMapper = CocoClassMapper()

    override fun getSemanticClassMapper(): ClassMapper {
        return cityscapesMapper
    }

    override fun getInstanceClassMapper(): ClassMapper? {
        return when (config.instanceProviderType) {
            InstanceProviderType.NONE -> null
            InstanceProviderType.YOLO11, InstanceProviderType.YOLO8 -> cocoMapper
            InstanceProviderType.CUSTOM -> null
        }
    }
}