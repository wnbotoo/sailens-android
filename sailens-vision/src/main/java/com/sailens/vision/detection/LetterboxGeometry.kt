package com.sailens.vision.detection

import com.sailens.core.frame.ImageFrame

/**
 * Letterbox geometry for the obstacle bbox decoder. Maps between the rotated camera frame and the
 * square letterboxed model input.
 */
internal data class LetterboxGeometry(
    val rotatedWidth: Int,
    val rotatedHeight: Int,
    val scale: Float,
    val padX: Float,
    val padY: Float,
) {
    companion object {
        fun from(frame: ImageFrame, inputSize: Int): LetterboxGeometry {
            val rotated = frame.rotationDegrees == 90 || frame.rotationDegrees == 270
            val rotatedWidth = if (rotated) frame.height else frame.width
            val rotatedHeight = if (rotated) frame.width else frame.height

            val scale = minOf(
                inputSize.toFloat() / rotatedWidth,
                inputSize.toFloat() / rotatedHeight,
            )
            val resizedWidth = rotatedWidth * scale
            val resizedHeight = rotatedHeight * scale

            return LetterboxGeometry(
                rotatedWidth = rotatedWidth,
                rotatedHeight = rotatedHeight,
                scale = scale,
                padX = (inputSize - resizedWidth) / 2f,
                padY = (inputSize - resizedHeight) / 2f,
            )
        }
    }
}
