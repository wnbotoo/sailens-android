package com.friady.sailens.domain.util

import java.util.ArrayDeque
import kotlin.math.abs

/**
 * 统一稳定器基类
 */
sealed class Stabilizer<T> {
    abstract fun update(value: T): T
    abstract fun reset()
    abstract fun getCurrentValue(): T
}

/**
 * 布尔值稳定器
 * 需要连续 N 帧相同状态才改变输出
 */
class BooleanStabilizer(
    private val requiredFrames: Int = 3,
) : Stabilizer<Boolean>() {
    private var currentState: Boolean = false
    private var pendingState: Boolean = false
    private var pendingCount: Int = 0

    override fun update(value: Boolean): Boolean {
        if (requiredFrames <= 1) {
            currentState = value
            pendingState = value
            pendingCount = 0
            return currentState
        }

        if (value == currentState) {
            pendingState = value
            pendingCount = 0
        } else if (value == pendingState) {
            pendingCount++
            if (pendingCount >= requiredFrames) {
                currentState = value
                pendingCount = 0
            }
        } else {
            pendingState = value
            pendingCount = 1
        }
        return currentState
    }

    override fun reset() {
        currentState = false
        pendingState = false
        pendingCount = 0
    }

    override fun getCurrentValue(): Boolean = currentState
}

/**
 * 枚举值稳定器
 */
class EnumStabilizer<E>(
    private val requiredFrames: Int = 3,
    private val defaultValue: E,
) : Stabilizer<E>() {
    private var currentState: E = defaultValue
    private var pendingState: E = defaultValue
    private var pendingCount: Int = 0

    override fun update(value: E): E {
        if (requiredFrames <= 1) {
            currentState = value
            pendingState = value
            pendingCount = 0
            return currentState
        }

        if (value == currentState) {
            pendingState = value
            pendingCount = 0
        } else if (value == pendingState) {
            pendingCount++
            if (pendingCount >= requiredFrames) {
                currentState = value
                pendingCount = 0
            }
        } else {
            pendingState = value
            pendingCount = 1
        }
        return currentState
    }

    override fun reset() {
        currentState = defaultValue
        pendingState = defaultValue
        pendingCount = 0
    }

    override fun getCurrentValue(): E = currentState
}

/**
 * 可空枚举稳定器
 */
class NullableEnumStabilizer<E : Any>(
    private val requiredFrames: Int = 3,
) : Stabilizer<E?>() {
    private var currentState: E? = null
    private var pendingState: E? = null
    private var pendingCount: Int = 0

    override fun update(value: E?): E? {
        if (requiredFrames <= 1) {
            currentState = value
            pendingState = value
            pendingCount = 0
            return currentState
        }

        if (value == currentState) {
            pendingState = value
            pendingCount = 0
        } else if (value == pendingState) {
            pendingCount++
            if (pendingCount >= requiredFrames) {
                currentState = value
                pendingCount = 0
            }
        } else {
            pendingState = value
            pendingCount = 1
        }
        return currentState
    }

    override fun reset() {
        currentState = null
        pendingState = null
        pendingCount = 0
    }

    override fun getCurrentValue(): E? = currentState
}

/**
 * 浮点数平滑器（移动平均）
 */
class FloatSmoother(
    private val windowSize: Int = 5,
) : Stabilizer<Float>() {
    private val values = ArrayDeque<Float>(windowSize)
    private var currentAverage: Float = 0f

    override fun update(value: Float): Float {
        values.addLast(value)
        if (values.size > windowSize) {
            values.removeFirst()
        }
        currentAverage = if (values.isNotEmpty()) {
            values.average().toFloat()
        } else {
            0f
        }
        return currentAverage
    }

    override fun reset() {
        values.clear()
        currentAverage = 0f
    }

    override fun getCurrentValue(): Float = currentAverage
}

/**
 * 带阈值的浮点数稳定器
 * 只有变化超过阈值才更新
 */
class ThresholdStabilizer(
    private val threshold: Float = 0.05f,
) : Stabilizer<Float>() {
    private var currentValue: Float = 0f

    override fun update(value: Float): Float {
        if (abs(value - currentValue) > threshold) {
            currentValue = value
        }
        return currentValue
    }

    override fun reset() {
        currentValue = 0f
    }

    override fun getCurrentValue(): Float = currentValue
}
