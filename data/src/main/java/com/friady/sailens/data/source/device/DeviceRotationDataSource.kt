package com.friady.sailens.data.source.device

import android.content.Context
import android.view.OrientationEventListener
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class DeviceRotationDataSource(context: Context) {
    // Exposes the rotation data
    private val _rotationState = MutableStateFlow(Surface.ROTATION_0)
    val rotationState: StateFlow<Int> = _rotationState

    // You need the application context for the listener
    private val listener: OrientationEventListener =
        object : OrientationEventListener(context.applicationContext) {
            override fun onOrientationChanged(orientationAngle: Int) {
                // 1. Check for unknown sensor state
                if (orientationAngle == ORIENTATION_UNKNOWN) return

                // 2. Map the 0-359 angle to the four Surface.ROTATION constants
                val newRotation = when (orientationAngle) {
                    // Standard Portrait (0 degrees, usually upright phone)
                    in 315..359 -> Surface.ROTATION_0
                    in 0..44 -> Surface.ROTATION_0

                    // Reverse Landscape (90 degrees clockwise from Portrait)
                    in 45..134 -> Surface.ROTATION_90

                    // Reverse Portrait (180 degrees, upside down)
                    in 135..224 -> Surface.ROTATION_180

                    // Landscape (270 degrees clockwise from Portrait)
                    in 225..314 -> Surface.ROTATION_270

                    else -> Surface.ROTATION_0 // Fallback
                }

                // 3. Update the StateFlow only if the rotation has actually changed
                if (newRotation != _rotationState.value) {
                    _rotationState.value = newRotation
                }
            }
        }

    fun start() {
        if (listener.canDetectOrientation()) {
            listener.enable()
        }
    }

    fun stop() {
        listener.disable()
    }
}