package com.friady.sailens.data.source.mapper

import com.friady.sailens.domain.model.perception.ClassMapper
import com.friady.sailens.domain.model.perception.ClassMapperProvider

/**
 * 类别映射器提供者实现
 */
class ClassMapperProviderImpl : ClassMapperProvider {

    private val cityscapesMapper = CityscapesClassMapper()
    private val cocoMapper = CocoClassMapper()

    override fun getSemanticClassMapper(): ClassMapper {
        return cityscapesMapper
    }

    override fun getInstanceClassMapper(): ClassMapper? {
        return cocoMapper
    }
}
