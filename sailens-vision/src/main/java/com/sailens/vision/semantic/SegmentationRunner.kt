package com.sailens.vision.semantic

import android.os.SystemClock
import com.google.ai.edge.litert.Accelerator
import com.sailens.core.frame.ImageFrame
import com.sailens.core.runtime.MlRuntimeInfo
import com.sailens.runtime.InputPreprocessBackend
import com.sailens.runtime.InputPreprocessCache
import com.sailens.runtime.ModelInputDataType
import com.sailens.runtime.ModelInputPreprocessor
import com.sailens.runtime.ModelInputQuantization
import com.sailens.runtime.ModelTensorConfig
import com.sailens.runtime.TensorQuantization
import com.sailens.runtime.TfliteTensorElementType
import com.sailens.runtime.session.LiteRtSession

/** One semantic inference: the postprocessed value plus the timings traces are built from. */
data class SegmentationRunResult<R>(
    val value: R,
    val preprocessTimeMs: Long,
    val modelTimeMs: Long,
    val outputReadTimeMs: Long,
    val postprocessTimeMs: Long,
    val runtimeInfo: MlRuntimeInfo,
)

/**
 * Runs one semantic segmentation session: preprocess, infer, postprocess.
 *
 * Generic in its result: the runner owns the tensor work and hands the scores to a
 * [SemanticPostprocessor], which owns what they mean. Guidance supplies a postprocessor that
 * computes navigation statistics in the same native pass as the argmax; a caller that only wants a
 * class map supplies [ClassMapPostprocessor]. Either way the score tensor is scanned once
 * (architecture.md 6.5).
 */
class SegmentationRunner<R>(
    private val session: LiteRtSession,
    private val config: ModelTensorConfig,
    private val inputDataType: ModelInputDataType,
    private val outputElementType: TfliteTensorElementType,
    private val outputQuantization: TensorQuantization?,
    inputQuantization: ModelInputQuantization,
    preferNativeYuvPreprocessing: Boolean,
    preprocessCache: InputPreprocessCache? = null,
    private val postprocessor: SemanticPostprocessor<R>,
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

    // 缓存结果 Mask 数组 (一维数组，存储索引)：整个输出网格，只有 generic argmax 回退路径用它
    private val cachedResultMask = IntArray(config.outputWidth * config.outputHeight)

    // 裁掉 letterbox padding 之后的 class map：只覆盖相机画面。尺寸随帧方向变化，按需重分配。
    private var cachedContentMask = IntArray(0)
    private var cachedContentSpec: SemanticScoreSpec? = null

    private val scoreSpec = SemanticScoreSpec(
        width = config.outputWidth,
        height = config.outputHeight,
        channels = config.outputChannels,
        layout = config.outputLayout,
    )

    private val inputPreprocessor = ModelInputPreprocessor(
        config = config,
        inputQuantization = inputQuantization,
        preferNativeYuvPreprocessing = preferNativeYuvPreprocessing,
        preprocessCache = preprocessCache,
    )
    private val argmaxPostprocessor = ArgmaxPostprocessor(
        config = config,
        kotlinArgmax = { scores, resultMask -> inputPreprocessor.postprocess(scores, resultMask) },
    )

    // Raw LiteRtTensorBuffer* handle for the output tensor, extracted once via reflection.
    // Non-zero only when the JniHandle accessor is available (LiteRT 2.1.3+). When available,
    // run() offers it to the postprocessor so it can skip the 30 MB readFloat() allocation.
    private val outputBufferHandle: Long = extractOutputBufferHandle()

    private fun extractOutputBufferHandle(): Long = runCatching {
        val cls = Class.forName("com.google.ai.edge.litert.JniHandle")
        val method = cls.getDeclaredMethod(
            "getHandle\$third_party_odml_litert_litert_kotlin_litert_kotlin_api"
        )
        method.isAccessible = true
        method.invoke(outputBuffer) as Long
    }.getOrDefault(0L)

    fun run(rawFrame: ImageFrame): SegmentationRunResult<R> {
        val startTime = SystemClock.uptimeMillis()
        val spec = specFor(rawFrame)
        val contentMask = contentMaskFor(spec.content)

        // 1. 预处理: YUV/RGBA frame -> model input tensor layout
        val preprocessBackend = preprocessInput(rawFrame)
        val afterPreprocessTime = SystemClock.uptimeMillis()

        // 2. 推理
        writeInput()
        session.run()
        val afterModelTime = SystemClock.uptimeMillis()

        // 3. Postprocess: offer the still-in-LiteRT tensor first (avoids the 30 MB readFloat()
        //    allocation). Route to the element-type-specific variant so INT8 bytes are never
        //    misread as float* (the default runtime uses a full-integer-quant model -> INT8
        //    output). If the postprocessor declines for any reason (native lib unavailable, dlsym
        //    failure, lock failure, validation error), copy the tensor out and offer it again,
        //    then fall back to generic argmax.
        val handleOutcome: SemanticPostprocessOutcome<R>? = when {
            outputBufferHandle == 0L -> null
            outputElementType == TfliteTensorElementType.FLOAT32 -> postprocessor.postprocessScores(
                scores = SemanticScores.FloatHandle(outputBufferHandle),
                spec = spec,
                reusableClassMap = contentMask,
            )
            outputElementType == TfliteTensorElementType.INT8 -> postprocessor.postprocessScores(
                scores = SemanticScores.Int8Handle(outputBufferHandle),
                spec = spec,
                reusableClassMap = contentMask,
            )
            else -> null
        }

        // When the handle path succeeds there is no tensor copy: set read checkpoint equal
        // to afterModelTime so outputReadTimeMs = 0.  When it fails, copy the tensor now
        // and capture the real copy cost in outputReadTimeMs.
        val outputScores: SemanticOutputScores?
        val afterOutputReadTime: Long
        if (handleOutcome != null) {
            outputScores = null
            afterOutputReadTime = afterModelTime
        } else {
            outputScores = readOutputScores()
            afterOutputReadTime = SystemClock.uptimeMillis()
        }

        val fusedOutcome: SemanticPostprocessOutcome<R>? = handleOutcome
            ?: when (outputScores) {
                is SemanticOutputScores.FloatScores -> postprocessor.postprocessScores(
                    scores = SemanticScores.FloatValues(outputScores.values),
                    spec = spec,
                    reusableClassMap = contentMask,
                )
                is SemanticOutputScores.Int8Scores -> postprocessor.postprocessScores(
                    scores = SemanticScores.Int8Values(outputScores.values),
                    spec = spec,
                    reusableClassMap = contentMask,
                )
                // The native score kernels read bytes as signed int8; feeding UINT8 there would
                // flip sign across the 128 boundary and corrupt argmax. Route UINT8 through the
                // float argmax path (unsigned dequant below) instead.
                is SemanticOutputScores.Uint8Scores -> null
                null -> null
            }

        val outcome: SemanticPostprocessOutcome<R> = fusedOutcome ?: run {
            val scores = outputScores!!.toFloatArray(outputQuantization)
            val backend = argmaxPostprocessor.argmax(scores, cachedResultMask)
            cropClassMap(cachedResultMask, config.outputWidth, spec.content, contentMask)
            SemanticPostprocessOutcome(
                // contentMask 会在下一帧继续复用，fromClassMap 必须做快照
                value = postprocessor.fromClassMap(contentMask, spec),
                backend = backend,
            )
        }
        val afterPostprocessTime = SystemClock.uptimeMillis()

        return SegmentationRunResult(
            value = outcome.value,
            preprocessTimeMs = afterPreprocessTime - startTime,
            modelTimeMs = afterModelTime - afterPreprocessTime,
            outputReadTimeMs = afterOutputReadTime - afterModelTime,
            postprocessTimeMs = afterPostprocessTime - afterOutputReadTime,
            runtimeInfo = MlRuntimeInfo(
                accelerator = session.accelerator.name,
                acceleratorSelection = session.acceleratorSelection,
                preprocessBackend = preprocessBackend.traceName,
                postprocessBackend = outcome.backend,
            ),
        )
    }

    /**
     * 本帧的分数规格：整个输出网格 + 相机画面落在其中的区域。letterbox 只取决于帧尺寸与方向，
     * 相同则复用上一次的结果。
     */
    private fun specFor(frame: ImageFrame): SemanticScoreSpec {
        val content = SemanticContentRegion.forLetterbox(
            frameWidth = frame.width,
            frameHeight = frame.height,
            rotationDegrees = frame.rotationDegrees,
            inputWidth = config.inputWidth,
            inputHeight = config.inputHeight,
            outputWidth = config.outputWidth,
            outputHeight = config.outputHeight,
        )
        cachedContentSpec?.takeIf { it.content == content }?.let { return it }
        return scoreSpec.copy(content = content).also { cachedContentSpec = it }
    }

    private fun contentMaskFor(content: SemanticContentRegion): IntArray {
        if (cachedContentMask.size != content.pixelCount) cachedContentMask = IntArray(content.pixelCount)
        return cachedContentMask
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

            ModelInputDataType.AUTO -> error("AUTO input type must be resolved before creating SegmentationRunner")
        }
    }

    private fun writeInput() {
        when (inputDataType) {
            ModelInputDataType.FLOAT32 -> inputBuffer.writeFloat(cachedInputFloatArray)
            // UINT8 writes the same raw bytes as INT8; the buffer's tensor type decides interpretation.
            ModelInputDataType.INT8,
            ModelInputDataType.UINT8 -> inputBuffer.writeInt8(cachedInputInt8Array)
            ModelInputDataType.AUTO -> error("AUTO input type must be resolved before creating SegmentationRunner")
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
