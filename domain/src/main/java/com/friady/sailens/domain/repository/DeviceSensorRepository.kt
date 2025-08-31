package com.friady.sailens.domain.repository

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
     * Called to start observing the physical sensor rotation events.
     */
    fun startObserving()

    /**
     * Called to stop observing the physical sensor rotation events.
     */
    fun stopObserving()
}