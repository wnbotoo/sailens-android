package com.friady.sailens.data.source.depth

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/**
 * 硬件深度传感器数据源（ToF/LiDAR）
 */
class HardwareDepthSource(
    private val context: Context,
) {
    private var _isAvailable = false

    fun initialize(): Boolean {
        _isAvailable = checkDepthSensorAvailable()
        return _isAvailable
    }

    fun isAvailable(): Boolean = _isAvailable

    fun getDepthAt(normalizedX: Float, normalizedY: Float): Float {
        if (!_isAvailable) return 0f
        // TODO: 实际硬件深度读取
        return 0f
    }

    private fun checkDepthSensorAvailable(): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraManager.cameraIdList.any { cameraId ->
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val capabilities =
                    characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT) == true
            }
        } catch (e: Exception) {
            false
        }
    }

    fun release() {
        _isAvailable = false
    }
}