package com.sailens.core.log

/**
 * 日志服务接口
 */
interface LogService {
    fun debug(tag: String, message: String, data: Map<String, Any>?  = null)
    fun info(tag: String, message: String, data: Map<String, Any>? = null)
    fun warning(tag: String, message: String, data: Map<String, Any>? = null, throwable: Throwable? = null)
    fun error(tag: String, message: String, throwable: Throwable?  = null)
}
