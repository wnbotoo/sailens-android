package com.friady.sailens.data.source.ml.segmentation

import android.os.SystemClock
import android.util.Log
import com.friady.sailens.data.source.ml.OpenCVImageProcessor
import com.friady.sailens.domain.model.perception.ImageFrame
import com.friady.sailens.domain.model.perception.SegmentationMask
import com.friady.sailens.domain.model.perception.SegmentationOutput
import com.google.ai.edge.litert.CompiledModel

private const val TAG = "LiteRTSegmenter"

class LiteRTSegmenter(
    private val model: CompiledModel,
    private val config: SegmenterConfig,
) {
    private val inputBuffers = model.createInputBuffers()
    private val outputBuffers = model.createOutputBuffers()

    // 缓存数组，避免重复分配
    private val cachedInputFloatArray = FloatArray(config.inputWidth * config.inputHeight * 3)

    // 缓存结果 Mask 数组 (一维数组，存储索引)
    private val cachedResultMask = IntArray(config.outputWidth * config.outputHeight)

    // 使用 LiteRT 处理器
    private val processor = OpenCVImageProcessor(config)

    suspend fun segment(rawFrame: ImageFrame): SegmentationOutput {
        val startTime = SystemClock.uptimeMillis()

        // 1. 预处理: Bitmap -> FloatArray (返回新数组，由 TensorBuffer 内部分配)
        processor.preprocess(rawFrame.bitmap, rawFrame.rotationDegrees, cachedInputFloatArray)
        val afterPreprocessTime = SystemClock.uptimeMillis()

        // 2. 推理
        val outputFloatArray = runModel(cachedInputFloatArray)
        val afterInferenceTime = SystemClock.uptimeMillis()

        // 4. 后处理: FloatArray -> IntArray (ArgMax)
        processor.postprocess(outputFloatArray, cachedResultMask)
        val afterPostprocessTime = SystemClock.uptimeMillis()

        // 3. 包装结果
        // cachedResultMask 会在下一帧继续复用，这里必须做快照，避免下游读取时被后续推理覆盖
        val stableResultMask = cachedResultMask.clone()
        val mask = SegmentationMask(
            config.outputWidth,
            config.outputHeight,
            stableResultMask,
        )

        val preprocessTimeMs = afterPreprocessTime - startTime
        val inferenceTimeMs = afterInferenceTime - afterPreprocessTime
        val postprocessTimeMs = afterPostprocessTime - afterInferenceTime

        Log.d(
            TAG,
            "preprocessTimeMs ${preprocessTimeMs}, inferenceTimeMs ${inferenceTimeMs}, postprocessTimeMs ${postprocessTimeMs}"
        )

        return SegmentationOutput(
            mask,
            preprocessTimeMs,
            inferenceTimeMs,
            postprocessTimeMs
        )
    }

    private fun runModel(inputFloatArray: FloatArray): FloatArray {
        // 写入输入
        inputBuffers[0].writeFloat(inputFloatArray)

        // 运行模型
        model.run(inputBuffers, outputBuffers)

        // 读取输出 (假设输出是 NHWC [1, 128, 256, 19] 扁平化后的数据)
        return outputBuffers[0].readFloat()
    }

    fun cleanup() {
        inputBuffers.forEach { it.close() }
        outputBuffers.forEach { it.close() }
        model.close()
        processor.close()
    }
}
