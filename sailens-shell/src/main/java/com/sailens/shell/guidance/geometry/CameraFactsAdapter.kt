package com.sailens.shell.guidance.geometry

import com.sailens.camera.CameraCharacteristicsProvider
import com.sailens.camera.CameraCharacteristicsSnapshot
import com.sailens.camera.CameraTimestampSource
import com.sailens.camera.PixelRect
import com.sailens.guidance.geometry.CameraClock
import com.sailens.guidance.geometry.CameraFacts
import com.sailens.guidance.geometry.CameraFactsSource
import com.sailens.guidance.geometry.SensorRect

/**
 * Hands Guidance the bound camera's facts for its camera geometry. The shell is where both sides meet:
 * Guidance does not know CameraX, and the camera module does not know Guidance.
 */
class CameraFactsAdapter(private val camera: CameraCharacteristicsProvider) : CameraFactsSource {
    override fun current(): CameraFacts? = camera.currentSnapshot()?.toCameraFacts()
}

internal fun CameraCharacteristicsSnapshot.toCameraFacts() = CameraFacts(
    sensorOrientationDegrees = sensorOrientationDegrees,
    clock = when (timestampSource) {
        CameraTimestampSource.REALTIME -> CameraClock.REALTIME
        CameraTimestampSource.UNKNOWN -> CameraClock.UNKNOWN
        CameraTimestampSource.NOT_REPORTED -> CameraClock.NOT_REPORTED
    },
    lensIntrinsicCalibration = lensIntrinsicCalibration,
    activeArray = activeArray?.toSensorRect(),
    preCorrectionActiveArray = preCorrectionActiveArray?.toSensorRect(),
    pixelArrayWidth = pixelArrayWidth,
    pixelArrayHeight = pixelArrayHeight,
    physicalSizeWidthMm = physicalSizeWidthMm,
    physicalSizeHeightMm = physicalSizeHeightMm,
    availableFocalLengthsMm = availableFocalLengthsMm,
)

private fun PixelRect.toSensorRect() = SensorRect(left, top, right, bottom)
