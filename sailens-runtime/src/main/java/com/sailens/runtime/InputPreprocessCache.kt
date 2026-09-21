package com.sailens.runtime

import com.sailens.core.frame.ImageFrame

class InputPreprocessCache {
    // Same-frame cache only: entries keep provider-owned reusable buffers and are valid until
    // that provider writes the next frame. Consumers must copy values out through copy*Input().
    private var floatEntry: FloatEntry? = null
    private var int8Entry: Int8Entry? = null

    @Synchronized
    fun copyFloatInput(
        key: Key,
        outputArray: FloatArray,
    ): InputPreprocessBackend? {
        val entry = floatEntry ?: return null
        if (entry.key != key || entry.values.size != outputArray.size) return null

        entry.values.copyInto(outputArray)
        return entry.backend.asSharedCacheHit()
    }

    @Synchronized
    fun storeFloatInput(
        key: Key,
        inputArray: FloatArray,
        backend: InputPreprocessBackend,
    ) {
        floatEntry = FloatEntry(
            key = key,
            values = inputArray,
            backend = backend,
        )
    }

    @Synchronized
    fun copyInt8Input(
        key: Key,
        quantization: ModelInputQuantization,
        outputArray: ByteArray,
    ): InputPreprocessBackend? {
        val entry = int8Entry ?: return null
        if (
            entry.key != key ||
            entry.quantization != quantization ||
            entry.values.size != outputArray.size
        ) {
            return null
        }

        entry.values.copyInto(outputArray)
        return when (entry.backend) {
            InputPreprocessBackend.SHARED_QUANTIZED_NATIVE_YUV,
            InputPreprocessBackend.SHARED_QUANTIZED_OPENCV_FALLBACK -> entry.backend
            else -> entry.backend.asSharedCacheHit()
        }
    }

    @Synchronized
    fun storeInt8Input(
        key: Key,
        quantization: ModelInputQuantization,
        inputArray: ByteArray,
        backend: InputPreprocessBackend,
    ) {
        int8Entry = Int8Entry(
            key = key,
            quantization = quantization,
            values = inputArray,
            backend = backend,
        )
    }

    @Synchronized
    fun clear() {
        floatEntry = null
        int8Entry = null
    }

    data class Key(
        val sequenceNumber: Long,
        val timestamp: Long,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val pixelFormat: String,
        val rotationDegrees: Int,
        val targetWidth: Int,
        val targetHeight: Int,
        val inputLayout: ImageTensorLayout,
        val mean: Triple<Float, Float, Float>,
        val std: Triple<Float, Float, Float>,
        val resizeFilter: ResizeFilter,
    ) {
        companion object {
            fun from(
                frame: ImageFrame,
                rotationDegrees: Int,
                config: ModelTensorConfig,
            ): Key {
                return Key(
                    sequenceNumber = frame.sequenceNumber,
                    timestamp = frame.timestamp,
                    sourceWidth = frame.width,
                    sourceHeight = frame.height,
                    pixelFormat = frame.pixelFormat.name,
                    rotationDegrees = rotationDegrees,
                    targetWidth = config.inputWidth,
                    targetHeight = config.inputHeight,
                    inputLayout = config.inputLayout,
                    mean = config.mean,
                    std = config.std,
                    resizeFilter = config.resizeFilter,
                )
            }
        }
    }

    private data class FloatEntry(
        val key: Key,
        val values: FloatArray,
        val backend: InputPreprocessBackend,
    )

    private data class Int8Entry(
        val key: Key,
        val quantization: ModelInputQuantization,
        val values: ByteArray,
        val backend: InputPreprocessBackend,
    )
}
