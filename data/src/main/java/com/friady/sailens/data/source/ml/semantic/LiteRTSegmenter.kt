package com.friady.sailens.data.source.ml.semantic

import android.os.SystemClock
import com.friady.sailens.data.source.ml.ModelInputDataType
import com.friady.sailens.data.source.ml.ModelInputQuantization
import com.friady.sailens.data.source.ml.YoloInputPreprocessor
import com.friady.sailens.domain.model.perception.ImageFrame
import com.friady.sailens.domain.model.perception.SegmentationMask
import com.friady.sailens.domain.model.perception.SegmentationOutput
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel

class LiteRTSegmenter(
    private val model: CompiledModel,
    private val config: SegmenterConfig,
    private val inputDataType: ModelInputDataType,
    inputQuantization: ModelInputQuantization,
    preferNativeYuvPreprocessing: Boolean,
    val accelerator: Accelerator,
    private val nativeSegmentationAnalyzer: NativeSegmentationAnalyzer? = null,
) {
    private val inputBuffers = model.createInputBuffers()
    private val outputBuffers = model.createOutputBuffers()
    private val inputElementCount = config.inputWidth * config.inputHeight * 3

    private val cachedInputFloatArray = FloatArray(if (inputDataType == ModelInputDataType.FLOAT32) inputElementCount else 0)
    private val cachedInputInt8Array = ByteArray(if (inputDataType == ModelInputDataType.INT8) inputElementCount else 0)

    // 缓存结果 Mask 数组 (一维数组，存储索引)
    private val cachedResultMask = IntArray(config.outputWidth * config.outputHeight)

    private val inputPreprocessor = YoloInputPreprocessor(
        config = config,
        inputQuantization = inputQuantization,
        preferNativeYuvPreprocessing = preferNativeYuvPreprocessing,
    )
    private val nativePostProcessor = YOLO26SemNativePostProcessor(config)

    fun segment(rawFrame: ImageFrame): SegmentationOutput {
        val startTime = SystemClock.uptimeMillis()

        // 1. 预处理: YUV/RGBA frame -> model input tensor layout
        preprocessInput(rawFrame)
        val afterPreprocessTime = SystemClock.uptimeMillis()

        // 2. 推理
        writeInput()
        model.run(inputBuffers, outputBuffers)
        val afterModelTime = SystemClock.uptimeMillis()

        val outputFloatArray = outputBuffers[0].readFloat()
        val afterOutputReadTime = SystemClock.uptimeMillis()

        // 3. 后处理: FloatArray -> IntArray + semantic analysis. Native fused path keeps
        // the argmax and analyzer in one scan; fallback still produces the same mask.
        val nativeAnalysis = nativeSegmentationAnalyzer?.analyzeScores(
            scores = outputFloatArray,
            reusableResultMask = cachedResultMask,
            width = config.outputWidth,
            height = config.outputHeight,
            channels = config.outputChannels,
        )
        if (nativeAnalysis == null && !nativePostProcessor.postProcess(outputFloatArray, cachedResultMask)) {
            inputPreprocessor.postprocess(outputFloatArray, cachedResultMask)
        }
        val afterPostprocessTime = SystemClock.uptimeMillis()

        // 4. 包装结果
        // cachedResultMask 会在下一帧继续复用，这里必须做快照，避免下游读取时被后续推理覆盖
        val mask = nativeAnalysis?.segmentation ?: SegmentationMask(
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
            nativeAnalysis,
        )
    }

    private fun preprocessInput(rawFrame: ImageFrame) {
        when (inputDataType) {
            ModelInputDataType.FLOAT32 -> {
                inputPreprocessor.preprocessFloat(rawFrame, rawFrame.rotationDegrees, cachedInputFloatArray)
            }

            ModelInputDataType.INT8 -> {
                inputPreprocessor.preprocessInt8(rawFrame, rawFrame.rotationDegrees, cachedInputInt8Array)
            }

            ModelInputDataType.AUTO -> error("AUTO input type must be resolved before creating LiteRTSegmenter")
        }
    }

    private fun writeInput() {
        when (inputDataType) {
            ModelInputDataType.FLOAT32 -> inputBuffers[0].writeFloat(cachedInputFloatArray)
            ModelInputDataType.INT8 -> inputBuffers[0].writeInt8(cachedInputInt8Array)
            ModelInputDataType.AUTO -> error("AUTO input type must be resolved before creating LiteRTSegmenter")
        }
    }

    fun cleanup() {
        inputBuffers.forEach { it.close() }
        outputBuffers.forEach { it.close() }
        model.close()
        inputPreprocessor.close()
    }
}
