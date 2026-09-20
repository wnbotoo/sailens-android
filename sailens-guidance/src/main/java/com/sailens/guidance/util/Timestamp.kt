package com.sailens.guidance.util

/**
 * 时间戳工具
 */
object Timestamp {
    fun now(): Long = System.currentTimeMillis()
    fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0
}