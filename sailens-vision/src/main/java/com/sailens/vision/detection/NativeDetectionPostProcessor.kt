package com.sailens.vision.detection

import com.sailens.vision.NativeVisionLibrary
import com.sailens.runtime.TensorQuantization
import com.sailens.core.geometry.NormalizedRect
import com.sailens.core.frame.ImageFrame
import com.sailens.vision.taxonomy.Taxonomy

internal class NativeDetectionPostProcessor(
    private val taxonomy: Taxonomy,
    private val inputSize: Int,
    private val confidenceThreshold: Float,
    private val maxDetections: Int,
    /**
     * Which of the taxonomy's classes the caller wants at all. Supplied rather than derived:
     * deciding that a bench matters and a sandwich does not is a product judgement, and this
     * module does not make those (architecture.md §6.3).
     */
    private val allowedClassIds: IntArray,
) {

    fun postProcessFloat(
        frame: ImageFrame,
        rawDetections: FloatArray,
        attributesPerDetection: Int,
    ): List<Detection>? {
        if (!NativeVisionLibrary.isAvailable || rawDetections.isEmpty()) return null

        val flatResult = runCatching {
            nativePostProcessRawFloat(
                rawDetections = rawDetections,
                frameWidth = frame.width,
                frameHeight = frame.height,
                rotationDegrees = frame.rotationDegrees,
                inputSize = inputSize,
                attributesPerDetection = attributesPerDetection,
                confidenceThreshold = confidenceThreshold,
                maxDetections = maxDetections,
                allowedClassIds = allowedClassIds,
            )
        }.getOrElse {
            return null
        }

        return decodeNativeResult(flatResult)
    }

    fun postProcessInt8(
        frame: ImageFrame,
        rawDetections: ByteArray,
        attributesPerDetection: Int,
        quantization: TensorQuantization?,
    ): List<Detection>? {
        if (!NativeVisionLibrary.isAvailable || rawDetections.isEmpty()) return null

        val flatResult = runCatching {
            nativePostProcessRawInt8(
                rawDetections = rawDetections,
                frameWidth = frame.width,
                frameHeight = frame.height,
                rotationDegrees = frame.rotationDegrees,
                inputSize = inputSize,
                attributesPerDetection = attributesPerDetection,
                quantScale = quantization?.scale ?: 1f,
                quantZeroPoint = quantization?.zeroPoint ?: 0,
                confidenceThreshold = confidenceThreshold,
                maxDetections = maxDetections,
                allowedClassIds = allowedClassIds,
            )
        }.getOrElse {
            return null
        }

        return decodeNativeResult(flatResult)
    }

    /** Zero-copy FLOAT32 variant: decodes straight from the model's output `TensorBuffer` handle,
     * skipping the per-frame `readFloat()` allocation for raw detection tensors. */
    fun postProcessFloatFromHandle(
        tensorBufferHandle: Long,
        rawElementCount: Int,
        frame: ImageFrame,
        attributesPerDetection: Int,
    ): List<Detection>? {
        if (!NativeVisionLibrary.isAvailable || tensorBufferHandle == 0L || rawElementCount <= 0) return null

        val flatResult = runCatching {
            nativePostProcessRawFloatFromHandle(
                tensorBufferHandle = tensorBufferHandle,
                rawElementCount = rawElementCount,
                frameWidth = frame.width,
                frameHeight = frame.height,
                rotationDegrees = frame.rotationDegrees,
                inputSize = inputSize,
                attributesPerDetection = attributesPerDetection,
                confidenceThreshold = confidenceThreshold,
                maxDetections = maxDetections,
                allowedClassIds = allowedClassIds,
            )
        }.getOrElse {
            return null
        } ?: return null

        return decodeNativeResult(flatResult)
    }

    /** Zero-copy INT8 variant: decodes straight from the model's output `TensorBuffer` handle,
     * skipping the per-frame `readInt8()` ByteArray allocation. [rawElementCount] is the flattened
     * detection tensor size (cannot be read from the handle). Returns null only when the handle path
     * is unavailable; an empty list still means the handle path succeeded with no detections. */
    fun postProcessInt8FromHandle(
        tensorBufferHandle: Long,
        rawElementCount: Int,
        frame: ImageFrame,
        attributesPerDetection: Int,
        quantization: TensorQuantization?,
    ): List<Detection>? {
        if (!NativeVisionLibrary.isAvailable || tensorBufferHandle == 0L || rawElementCount <= 0) return null

        val flatResult = runCatching {
            nativePostProcessRawInt8FromHandle(
                tensorBufferHandle = tensorBufferHandle,
                rawElementCount = rawElementCount,
                frameWidth = frame.width,
                frameHeight = frame.height,
                rotationDegrees = frame.rotationDegrees,
                inputSize = inputSize,
                attributesPerDetection = attributesPerDetection,
                quantScale = quantization?.scale ?: 1f,
                quantZeroPoint = quantization?.zeroPoint ?: 0,
                confidenceThreshold = confidenceThreshold,
                maxDetections = maxDetections,
                allowedClassIds = allowedClassIds,
            )
        }.getOrElse {
            return null
        } ?: return null

        return decodeNativeResult(flatResult)
    }

    private fun decodeNativeResult(flatResult: FloatArray): List<Detection> {
        if (flatResult.isEmpty()) return emptyList()

        val detections = ArrayList<Detection>(flatResult.size / VALUES_PER_DETECTION)
        var index = 0
        while (index + VALUES_PER_DETECTION <= flatResult.size) {
            val classId = flatResult[index].toInt()
            detections += Detection(
                classId = classId,
                label = taxonomy.label(classId),
                confidence = flatResult[index + 1],
                boundingBox = NormalizedRect(
                    x = flatResult[index + 2].coerceIn(0f, 1f),
                    y = flatResult[index + 3].coerceIn(0f, 1f),
                    width = flatResult[index + 4].coerceIn(0f, 1f),
                    height = flatResult[index + 5].coerceIn(0f, 1f),
                ),
            )
            index += VALUES_PER_DETECTION
        }
        return detections
    }

    private external fun nativePostProcessRawFloat(
        rawDetections: FloatArray,
        frameWidth: Int,
        frameHeight: Int,
        rotationDegrees: Int,
        inputSize: Int,
        attributesPerDetection: Int,
        confidenceThreshold: Float,
        maxDetections: Int,
        allowedClassIds: IntArray,
    ): FloatArray

    private external fun nativePostProcessRawInt8(
        rawDetections: ByteArray,
        frameWidth: Int,
        frameHeight: Int,
        rotationDegrees: Int,
        inputSize: Int,
        attributesPerDetection: Int,
        quantScale: Float,
        quantZeroPoint: Int,
        confidenceThreshold: Float,
        maxDetections: Int,
        allowedClassIds: IntArray,
    ): FloatArray

    private external fun nativePostProcessRawFloatFromHandle(
        tensorBufferHandle: Long,
        rawElementCount: Int,
        frameWidth: Int,
        frameHeight: Int,
        rotationDegrees: Int,
        inputSize: Int,
        attributesPerDetection: Int,
        confidenceThreshold: Float,
        maxDetections: Int,
        allowedClassIds: IntArray,
    ): FloatArray?

    private external fun nativePostProcessRawInt8FromHandle(
        tensorBufferHandle: Long,
        rawElementCount: Int,
        frameWidth: Int,
        frameHeight: Int,
        rotationDegrees: Int,
        inputSize: Int,
        attributesPerDetection: Int,
        quantScale: Float,
        quantZeroPoint: Int,
        confidenceThreshold: Float,
        maxDetections: Int,
        allowedClassIds: IntArray,
    ): FloatArray?

    private companion object {
        const val VALUES_PER_DETECTION = 6
    }
}
