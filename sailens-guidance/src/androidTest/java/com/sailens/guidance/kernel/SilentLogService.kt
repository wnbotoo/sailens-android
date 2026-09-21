package com.sailens.guidance.kernel

import com.sailens.core.log.LogService

/**
 * A [LogService] that drops everything.
 *
 * The native wrappers log which backend they picked once per instance; these tests assert on the
 * returned values, not on that log line.
 */
internal object SilentLogService : LogService {
    override fun debug(tag: String, message: String, data: Map<String, Any>?) = Unit

    override fun info(tag: String, message: String, data: Map<String, Any>?) = Unit

    override fun warning(
        tag: String,
        message: String,
        data: Map<String, Any>?,
        throwable: Throwable?,
    ) = Unit

    override fun error(tag: String, message: String, throwable: Throwable?) = Unit
}
