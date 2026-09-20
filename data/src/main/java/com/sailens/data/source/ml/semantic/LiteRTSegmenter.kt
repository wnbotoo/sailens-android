package com.sailens.data.source.ml.semantic
import com.sailens.vision.semantic.NativeSemanticArgmaxPostprocessor

import android.os.SystemClock
import com.sailens.runtime.InputPreprocessBackend
import com.sailens.runtime.ModelInputDataType
import com.sailens.runtime.ModelInputQuantization
import com.sailens.runtime.TensorQuantization
import com.sailens.runtime.TfliteTensorElementType
import com.sailens.runtime.ModelTensorConfig
import com.sailens.runtime.ModelInputPreprocessor
import com.sailens.runtime.InputPreprocessCache
import com.sailens.runtime.session.LiteRtSession
import com.sailens.core.runtime.MlRuntimeInfo
import com.sailens.core.frame.ImageFrame
import com.sailens.domain.model.perception.SegmentationMask
import com.sailens.domain.model.perception.SegmentationOutput
import com.google.ai.edge.litert.Accelerator

internal class LiteRTSegmenter(
    private val session: LiteRtSession,
    private val config: ModelTensorConfig,
    private val inputDataType: ModelInputDataType,
    private val outputElementType: TfliteTensorElementType,
    private val outputQuantization: TensorQuantization?,
    inputQuantization: ModelInputQuantization,
    preferNativeYuvPreprocessing: Boolean,
    preprocessCache: InputPreprocessCache? = null,
    private val nativeScorePostprocessor: NativeSemanticScorePostprocessor? = null,
) {
    val accelerator: Accelerator get() = session.accelerator
    private val inputBuffer = session.inputBuffers.single()
    private val outputBuffer = session.outputBuffers.single()
    private val inputElementCount = config.inputWidth * config.inputHeight * 3

    private val cachedInputFloatArray = FloatArray(if (inputDataType == ModelInputDataType.FLOAT32) inputElementCount else 0)
    // INT8 and UINT8 both feed a byte tensor; the difference is only in how the bytes are quantized.
    private val cachedInputInt8Array = ByteArray(
        if (inputDataType == ModelInputDataType.INT8 || inputDataType == ModelInputDataType.UINT8) inputElementCount else 0
    )

    // 缓存结果 Mask 数组 (一维数组，存储索引)
    private val cachedResultMask = IntArray(config.outputWidth * config.outputHeight)

    private val inputPreprocessor = ModelInputPreprocessor(
        config = config,
        inputQuantization = inputQuantization,
        preferNativeYuvPreprocessing = preferNativeYuvPreprocessing,
        preprocessCache = preprocessCache,
    )
    private val nativeArgmaxPostprocessor = NativeSemanticArgmaxPostprocessor(config)

    // Raw LiteRtTensorBuffer* handle for the output tensor, extracted once via reflection.
    // Non-zero only when the JniHandle accessor is available (LiteRT 2.1.3+). When available,
    // segment() uses this to lock the buffer directly and skip the 30 MB readFloat() allocation.
    private val outputBufferHandle: Long = extractOutputBufferHandle()

    private fun extractOutputBufferHandle(): Long = runCatching {
        val cls = Class.forName("com.google.ai.edge.litert.JniHandle")
        val method = cls.getDeclaredMethod(
            "getHandle\$third_party_odml_litert_litert_kotlin_litert_kotlin_api"
        )
        method.isAccessible = true
        method.invoke(outputBuffer) as Long
    }.getOrDefault(0L)

    fun segment(rawFrame: ImageFrame): SegmentationOutput {
        val startTime = SystemClock.uptimeMillis()

        // 1. 预处理: YUV/RGBA frame -> model input tensor layout
        val preprocessBackend = preprocessInput(rawFrame)
        val afterPreprocessTime = SystemClock.uptimeMillis()

        // 2. 推理
        writeInput()
        session.run()
        val afterModelTime = SystemClock.uptimeMillis()

        // 3. Postprocess: try zero-copy handle path first (avoids 30 MB readFloat() allocation).
        //    Route to the element-type-specific variant so INT8 bytes are never misread as
        //    float* (the default runtime uses a full-integer-quant model -> INT8 output).
        //    If the handle path returns null for any reason (native lib unavailable, dlsym
        //    failure, lock failure, validation error), fall back to readOutputScores() and
        //    proceed through the normal native/Kotlin postprocess chain.
        val handleResult: SemanticPostprocessResult? = when {
            outputBufferHandle == 0L -> null
            outputElementType == TfliteTensorElementType.FLOAT32 ->
                nativeScorePostprocessor?.postprocessScoresFromHandle(
                    tensorBufferHandle = outputBufferHandle,
                    reusableResultMask = cachedResultMask,
                    width = config.outputWidth,
                    height = config.outputHeight,
                    channels = config.outputChannels,
                    scoreLayout = config.outputLayout,
                )
            outputElementType == TfliteTensorElementType.INT8 ->
                nativeScorePostprocessor?.postprocessInt8ScoresFromHandle(
                    tensorBufferHandle = outputBufferHandle,
                    reusableResultMask = cachedResultMask,
                    width = config.outputWidth,
                    height = config.outputHeight,
                    channels = config.outputChannels,
                    scoreLayout = config.outputLayout,
                )
            else -> null
        }

        // When the handle path succeeds there is no tensor copy: set read checkpoint equal
        // to afterModelTime so outputReadTimeMs = 0.  When it fails, copy the tensor now
        // and capture the real copy cost in outputReadTimeMs.
        val outputScores: SemanticOutputScores?
        val afterOutputReadTime: Long
        if (handleResult != null) {
            outputScores = null
            afterOutputReadTime = afterModelTime
        } else {
            outputScores = readOutputScores()
            afterOutputReadTime = SystemClock.uptimeMillis()
        }

        val nativePostprocessResult: SemanticPostprocessResult? = handleResult
            ?: when (outputScores) {
                is SemanticOutputScores.FloatScores -> nativeScorePostprocessor?.postprocessScores(
                    scores = outputScores.values,
                    reusableResultMask = cachedResultMask,
                    width = config.outputWidth,
                    height = config.outputHeight,
                    channels = config.outputChannels,
                    scoreLayout = config.outputLayout,
                )
                is SemanticOutputScores.Int8Scores -> nativeScorePostprocessor?.postprocessInt8Scores(
                    scores = outputScores.values,
                    reusableResultMask = cachedResultMask,
                    width = config.outputWidth,
                    height = config.outputHeight,
                    channels = config.outputChannels,
                    scoreLayout = config.outputLayout,
                )
                // The native score postprocessor reads bytes as signed int8; feeding UINT8 there would
                // flip sign across the 128 boundary and corrupt argmax. Route UINT8 through the float
                // argmax path (unsigned dequant below) instead. A native UINT8 score path is a later step.
                is SemanticOutputScores.Uint8Scores -> null
                null -> null
            }

        val postprocessBackend = when {
            handleResult != null && outputElementType == TfliteTensorElementType.INT8 -> "native_score_int8"
            handleResult != null -> "native_score"
            nativePostprocessResult != null && outputScores is SemanticOutputScores.Int8Scores -> "native_score_int8"
            nativePostprocessResult != null -> "native_score"
            else -> {
                val scores = outputScores!!.toFloatArray(outputQuantization)
                if (nativeArgmaxPostprocessor.argmaxScores(scores, cachedResultMask)) "native_argmax"
                else { inputPreprocessor.postprocess(scores, cachedResultMask); "kotlin_argmax" }
            }
        }
        val afterPostprocessTime = SystemClock.uptimeMillis()

        // 4. 包装结果
        // cachedResultMask 会在下一帧继续复用，这里必须做快照，避免下游读取时被后续推理覆盖
        val mask = nativePostprocessResult?.mask ?: SegmentationMask(
            config.outputWidth,
            config.outputHeight,
            cachedResultMask.clone(),
        )

        val preprocessTimeMs = afterPreprocessTime - startTime
        val modelTimeMs = afterModelTime - afterPreprocessTime
        val outputReadTimeMs = afterOutputReadTime - afterModelTime
        val postprocessTimeMs = afterPostprocessTime - afterOutputReadTime

        return SegmentationOutput(
            mask,
            preprocessTimeMs,
            modelTimeMs + outputReadTimeMs,
            postprocessTimeMs,
            modelTimeMs,
            outputReadTimeMs,
            nativePostprocessResult?.stats,
            MlRuntimeInfo(
                accelerator = session.accelerator.name,
                acceleratorSelection = session.acceleratorSelection,
                preprocessBackend = preprocessBackend.traceName,
                postprocessBackend = postprocessBackend,
            ),
        )
    }

    private fun preprocessInput(rawFrame: ImageFrame): InputPreprocessBackend {
        return when (inputDataType) {
            ModelInputDataType.FLOAT32 -> {
                inputPreprocessor.preprocessFloat(rawFrame, rawFrame.rotationDegrees, cachedInputFloatArray)
            }

            ModelInputDataType.INT8 -> {
                inputPreprocessor.preprocessInt8(rawFrame, rawFrame.rotationDegrees, cachedInputInt8Array)
            }

            ModelInputDataType.UINT8 -> {
                inputPreprocessor.preprocessUint8(rawFrame, rawFrame.rotationDegrees, cachedInputInt8Array)
            }

            ModelInputDataType.AUTO -> error("AUTO input type must be resolved before creating LiteRTSegmenter")
        }
    }

    private fun writeInput() {
        when (inputDataType) {
            ModelInputDataType.FLOAT32 -> inputBuffer.writeFloat(cachedInputFloatArray)
            // UINT8 writes the same raw bytes as INT8; the buffer's tensor type decides interpretation.
            ModelInputDataType.INT8,
            ModelInputDataType.UINT8 -> inputBuffer.writeInt8(cachedInputInt8Array)
            ModelInputDataType.AUTO -> error("AUTO input type must be resolved before creating LiteRTSegmenter")
        }
    }

    private fun readOutputScores(): SemanticOutputScores {
        return when (outputElementType) {
            TfliteTensorElementType.FLOAT32 -> SemanticOutputScores.FloatScores(outputBuffer.readFloat())
            TfliteTensorElementType.INT8 -> SemanticOutputScores.Int8Scores(outputBuffer.readInt8())
            // UINT8 shares readInt8() (raw bytes); Uint8Scores.toFloatArray dequantizes unsigned.
            TfliteTensorElementType.UINT8 -> SemanticOutputScores.Uint8Scores(outputBuffer.readInt8())
            else -> error("Unsupported semantic output element type: $outputElementType")
        }
    }

    private fun ByteArray.toDequantizedFloatArray(
        quantization: TensorQuantization?,
    ): FloatArray {
        return if (quantization == null) {
            FloatArray(size) { index -> this[index].toFloat() }
        } else {
            FloatArray(size) { index -> quantization.dequantize(this[index]) }
        }
    }

    fun cleanup() {
        // Buffers + compiled model are owned by the session.
        session.close()
        inputPreprocessor.close()
    }

    private sealed interface SemanticOutputScores {
        data class FloatScores(val values: FloatArray) : SemanticOutputScores
        data class Int8Scores(val values: ByteArray) : SemanticOutputScores
        data class Uint8Scores(val values: ByteArray) : SemanticOutputScores
    }

    private fun SemanticOutputScores.toFloatArray(
        quantization: TensorQuantization?,
    ): FloatArray {
        return when (this) {
            is SemanticOutputScores.FloatScores -> values
            is SemanticOutputScores.Int8Scores -> values.toDequantizedFloatArray(quantization)
            is SemanticOutputScores.Uint8Scores -> values.toUnsignedDequantizedFloatArray(quantization)
        }
    }

    private fun ByteArray.toUnsignedDequantizedFloatArray(
        quantization: TensorQuantization?,
    ): FloatArray {
        val zeroPoint = quantization?.zeroPoint ?: 0
        val scale = quantization?.scale ?: 1f
        // Read each byte as its unsigned 0..255 value before dequantizing. argmax is invariant to a
        // positive-scale affine map, so this is really only about preserving the unsigned ordering.
        return FloatArray(size) { index -> ((this[index].toInt() and 0xFF) - zeroPoint) * scale }
    }
}
