package com.sailens.camera

import androidx.camera.core.ImageProxy
import com.sailens.core.frame.FrameSourceGeometry

/**
 * CameraX's own sensor-to-buffer mapping for this frame (`ImageInfo.sensorToBufferTransformMatrix`,
 * from `SENSOR_INFO_ACTIVE_ARRAY_SIZE` coordinates to buffer pixels) and the crop it applied. These
 * cannot be reliably rebuilt later from the active array, the buffer size and the rotation alone.
 *
 * Never fails a frame: a source that cannot report them yields null.
 */
internal fun readSourceGeometry(image: ImageProxy): FrameSourceGeometry? = runCatching {
    val values = FloatArray(MATRIX_VALUES)
    image.imageInfo.sensorToBufferTransformMatrix.getValues(values)
    val crop = image.cropRect
    FrameSourceGeometry(
        sensorToBufferTransform = values.toList(),
        cropLeft = crop.left,
        cropTop = crop.top,
        cropRight = crop.right,
        cropBottom = crop.bottom,
    )
}.getOrNull()

private const val MATRIX_VALUES = 9
