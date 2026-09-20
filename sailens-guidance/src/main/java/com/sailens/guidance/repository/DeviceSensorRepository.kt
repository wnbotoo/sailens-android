package com.sailens.guidance.repository

import kotlinx.coroutines.flow.StateFlow

interface DeviceSensorRepository {
    /**
     * Exposes the current physical rotation of the device as a flow of Surface.ROTATION constants (0, 90, 180, 270).
     * This value should be used to determine the correct orientation for image processing.
     */
    val deviceRotation: StateFlow<Int>

    val deviceRotationValue: Int

    val deviceRotationDegree: Int

    /**
     * 用户当前是否基本静止（等红灯、站着说话）。
     *
     * 用来拉长播报冷却：人没有移动，场景就没有更新，重复播同一条提示毫无价值，只是打扰。
     * 传感器不可用时恒为 false，也就是退回原来的固定冷却行为。
     */
    val isStationary: StateFlow<Boolean>

    /**
     * Called to start observing the physical sensor rotation events.
     */
    fun startObserving()

    /**
     * Called to stop observing the physical sensor rotation events.
     */
    fun stopObserving()
}