package com.friady.sailens.domain.model.perception

/**
 * 类别映射器提供者接口
 * 用于根据模型类型获取对应的映射器
 */
interface ClassMapperProvider {

    /**
     * 获取语义分割类别映射器
     */
    fun getSemanticClassMapper(): ClassMapper

    /**
     * 获取实例分割类别映射器（V2）
     */
    fun getInstanceClassMapper(): ClassMapper?
}