package com.sailens.data.repository

import android.view.Surface
import com.sailens.data.source.device.DeviceMotionDataSource
import com.sailens.data.source.device.DeviceRotationDataSource
import com.sailens.domain.repository.DeviceSensorRepository
import kotlinx.coroutines.flow.StateFlow

class DefaultDeviceSensorRepository(
    private val rotationDataSource: DeviceRotationDataSource,
    private val motionDataSource: DeviceMotionDataSource,
) : DeviceSensorRepository {
    override val deviceRotation: StateFlow<Int>
        get() = rotationDataSource.rotationState

    override val isStationary: StateFlow<Boolean>
        get() = motionDataSource.isStationary

    override val deviceRotationValue: Int
        get() = deviceRotation.value

    override val deviceRotationDegree: Int
        get() =
            when (deviceRotationValue) {
                Surface.ROTATION_0 -> 0
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }

    override fun startObserving() {
        rotationDataSource.start()
        motionDataSource.start()
    }

    override fun stopObserving() {
        rotationDataSource.stop()
        motionDataSource.stop()
    }
}