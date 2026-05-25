package com.friady.sailens.data.source.ml

import com.friady.sailens.domain.model.perception.ImageFrame
import kotlin.math.roundToInt

internal class YoloInputPreprocessor(
    private val config: YoloTensorConfig,
    private val inputQuantization: ModelInputQuantization,
    private val preferNativeYuvPreprocessing: Boolean,
) : AutoCloseable {
    private val fallbackProcessor = OpenCVImageProcessor(config)
    private val nativeProcessor = NativeYuvInputPreprocessor(config, inputQuantization)
    private var fallbackFloatInput = FloatArray(0)

    fun preprocessFloat(
        frame: ImageFrame,
        rotationDegrees: Int,
        outputArray: FloatArray,
    ) {
        if (preferNativeYuvPreprocessing &&
            nativeProcessor.preprocessFloat(frame, rotationDegrees, outputArray)
        ) {
            return
        }
        fallbackProcessor.preprocess(frame, rotationDegrees, outputArray)
    }

    fun preprocessInt8(
        frame: ImageFrame,
        rotationDegrees: Int,
        outputArray: ByteArray,
    ) {
        if (preferNativeYuvPreprocessing &&
            nativeProcessor.preprocessInt8(frame, rotationDegrees, outputArray)
        ) {
            return
        }

        val expectedSize = config.inputWidth * config.inputHeight * 3
        if (fallbackFloatInput.size != expectedSize) {
            fallbackFloatInput = FloatArray(expectedSize)
        }
        fallbackProcessor.preprocess(frame, rotationDegrees, fallbackFloatInput)
        quantizeFloatInput(fallbackFloatInput, outputArray)
    }

    fun postprocess(scores: FloatArray, resultMask: IntArray) {
        fallbackProcessor.postprocess(scores, resultMask)
    }

    private fun quantizeFloatInput(
        input: FloatArray,
        output: ByteArray,
    ) {
        require(input.size == output.size) {
            "Input float size ${input.size} does not match int8 output size ${output.size}"
        }
        val scale = inputQuantization.scale
        val zeroPoint = inputQuantization.zeroPoint
        require(scale > 0f) { "Input quantization scale must be > 0, got $scale" }

        for (index in input.indices) {
            val quantized = (input[index] / scale + zeroPoint).roundToInt()
            output[index] = quantized.coerceIn(Byte.MIN_VALUE.toInt(), Byte.MAX_VALUE.toInt()).toByte()
        }
    }

    override fun close() {
        fallbackProcessor.close()
    }
}
