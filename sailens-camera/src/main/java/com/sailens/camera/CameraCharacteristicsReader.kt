package com.sailens.camera

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo

/**
 * The only place that talks Camera2 interop. `Camera2CameraInfo` is deprecated from CameraX 1.7 in
 * favour of `CameraInfo.cameraCharacteristics`; keeping it here means that migration touches this
 * file and nothing else.
 */
@OptIn(ExperimentalCamera2Interop::class)
internal fun readCharacteristicsSnapshot(
    cameraInfo: CameraInfo,
    capturedAtElapsedRealtimeNanos: Long,
): CameraCharacteristicsSnapshot {
    val camera2 = Camera2CameraInfo.from(cameraInfo)
    fun <T> get(key: CameraCharacteristics.Key<T>): T? = camera2.getCameraCharacteristic(key)

    return CameraCharacteristicsSnapshot(
        cameraId = camera2.cameraId,
        capturedAtElapsedRealtimeNanos = capturedAtElapsedRealtimeNanos,
        sensorOrientationDegrees = get(CameraCharacteristics.SENSOR_ORIENTATION),
        timestampSource = timestampSourceOf(get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)),
        lensIntrinsicCalibration = get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)?.toList(),
        lensDistortion = get(CameraCharacteristics.LENS_DISTORTION)?.toList(),
        lensPoseRotation = get(CameraCharacteristics.LENS_POSE_ROTATION)?.toList(),
        lensPoseTranslation = get(CameraCharacteristics.LENS_POSE_TRANSLATION)?.toList(),
        activeArray = get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.toPixelRect(),
        preCorrectionActiveArray =
            get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)?.toPixelRect(),
        pixelArrayWidth = get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.width,
        pixelArrayHeight = get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.height,
        physicalSizeWidthMm = get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width,
        physicalSizeHeightMm = get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.height,
        availableFocalLengthsMm =
            get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty(),
    )
}

internal fun timestampSourceOf(value: Int?): CameraTimestampSource = when (value) {
    CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME -> CameraTimestampSource.REALTIME
    CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN -> CameraTimestampSource.UNKNOWN
    else -> CameraTimestampSource.NOT_REPORTED
}

private fun Rect.toPixelRect() = PixelRect(left = left, top = top, right = right, bottom = bottom)
