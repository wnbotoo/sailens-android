package com.sailens.runtime

import com.google.ai.edge.litert.Accelerator

enum class ModelInputDataType {
    AUTO,
    FLOAT32,
    INT8,
    UINT8,
}

enum class ModelAcceleratorBackend {
    NPU,
    GPU,
    CPU,
}

enum class ModelAcceleratorSelectionMode {
    EXPLICIT,
    PREFER_BACKEND,
    FIRST_AVAILABLE,
}

enum class InputPreprocessBackend(val traceName: String) {
    NATIVE_YUV("native_yuv"),
    OPENCV_FALLBACK("opencv_fallback"),
    SHARED_NATIVE_YUV("shared_native_yuv"),
    SHARED_OPENCV_FALLBACK("shared_opencv_fallback"),
    SHARED_QUANTIZED_NATIVE_YUV("shared_quantized_native_yuv"),
    SHARED_QUANTIZED_OPENCV_FALLBACK("shared_quantized_opencv_fallback"),
}

enum class ResizeFilter {
    NEAREST,
    BILINEAR,
}

enum class ImageTensorLayout {
    NHWC,
    NCHW,
}

val ImageTensorLayout.nativeValue: Int
    get() = when (this) {
        ImageTensorLayout.NHWC -> 0
        ImageTensorLayout.NCHW -> 1
    }

internal fun InputPreprocessBackend.asSharedCacheHit(): InputPreprocessBackend {
    return when (this) {
        InputPreprocessBackend.NATIVE_YUV,
        InputPreprocessBackend.SHARED_NATIVE_YUV,
        InputPreprocessBackend.SHARED_QUANTIZED_NATIVE_YUV -> InputPreprocessBackend.SHARED_NATIVE_YUV
        InputPreprocessBackend.OPENCV_FALLBACK,
        InputPreprocessBackend.SHARED_OPENCV_FALLBACK,
        InputPreprocessBackend.SHARED_QUANTIZED_OPENCV_FALLBACK -> InputPreprocessBackend.SHARED_OPENCV_FALLBACK
    }
}

internal fun InputPreprocessBackend.asSharedQuantizedCacheHit(): InputPreprocessBackend {
    return when (this) {
        InputPreprocessBackend.NATIVE_YUV,
        InputPreprocessBackend.SHARED_NATIVE_YUV,
        InputPreprocessBackend.SHARED_QUANTIZED_NATIVE_YUV -> InputPreprocessBackend.SHARED_QUANTIZED_NATIVE_YUV
        InputPreprocessBackend.OPENCV_FALLBACK,
        InputPreprocessBackend.SHARED_OPENCV_FALLBACK,
        InputPreprocessBackend.SHARED_QUANTIZED_OPENCV_FALLBACK -> {
            InputPreprocessBackend.SHARED_QUANTIZED_OPENCV_FALLBACK
        }
    }
}

data class ModelInputQuantization(
    val scale: Float = 1f / 255f,
    val zeroPoint: Int = -128,
)

data class ModelTensorConfig(
    val inputWidth: Int,
    val inputHeight: Int,
    val inputLayout: ImageTensorLayout = ImageTensorLayout.NHWC,
    val outputWidth: Int,
    val outputHeight: Int,
    val outputChannels: Int,
    val outputLayout: ImageTensorLayout = ImageTensorLayout.NHWC,
    val mean: Triple<Float, Float, Float>,
    val std: Triple<Float, Float, Float>,
    val confidenceThreshold: Float = 0f,
    val resizeFilter: ResizeFilter = ResizeFilter.NEAREST,
)

data class ImageTensorSpec(
    val width: Int,
    val height: Int,
    val channels: Int,
    val layout: ImageTensorLayout,
)

fun imageTensorSpecOrNull(
    dimensions: List<Int>,
    expectedChannels: Int,
): ImageTensorSpec? {
    if (dimensions.size != 4 || dimensions.firstOrNull() != 1) return null
    return when {
        dimensions[3] == expectedChannels -> ImageTensorSpec(
            width = dimensions[2],
            height = dimensions[1],
            channels = dimensions[3],
            layout = ImageTensorLayout.NHWC,
        )
        dimensions[1] == expectedChannels -> ImageTensorSpec(
            width = dimensions[3],
            height = dimensions[2],
            channels = dimensions[1],
            layout = ImageTensorLayout.NCHW,
        )
        else -> null
    }
}

fun imageTensorSpec(
    dimensions: List<Int>,
    expectedChannels: Int,
    description: String,
): ImageTensorSpec {
    return imageTensorSpecOrNull(dimensions, expectedChannels)
        ?: error("$description must be NHWC or NCHW with $expectedChannels channels, got dimensions=$dimensions")
}

internal fun FloatArray.copyHwcToNchw(
    output: FloatArray,
    width: Int,
    height: Int,
    channels: Int,
) {
    val pixelCount = width * height
    require(size >= pixelCount * channels && output.size >= pixelCount * channels) {
        "Cannot convert HWC to NCHW for ${width}x$height x $channels: input=$size output=${output.size}"
    }
    for (pixelIndex in 0 until pixelCount) {
        val inputBase = pixelIndex * channels
        for (channel in 0 until channels) {
            output[channel * pixelCount + pixelIndex] = this[inputBase + channel]
        }
    }
}

internal fun ByteArray.copyHwcToNchw(
    output: ByteArray,
    width: Int,
    height: Int,
    channels: Int,
) {
    val pixelCount = width * height
    require(size >= pixelCount * channels && output.size >= pixelCount * channels) {
        "Cannot convert HWC to NCHW for ${width}x$height x $channels: input=$size output=${output.size}"
    }
    for (pixelIndex in 0 until pixelCount) {
        val inputBase = pixelIndex * channels
        for (channel in 0 until channels) {
            output[channel * pixelCount + pixelIndex] = this[inputBase + channel]
        }
    }
}

data class ResolvedModelInputDataType(
    val dataType: ModelInputDataType,
    val elementTypeName: String,
)

fun resolveModelInputDataType(
    configured: ModelInputDataType,
    tensorElementType: TfliteTensorElementType,
): ResolvedModelInputDataType {
    val resolved = when (configured) {
        ModelInputDataType.FLOAT32 -> ModelInputDataType.FLOAT32
        ModelInputDataType.INT8 -> ModelInputDataType.INT8
        ModelInputDataType.UINT8 -> ModelInputDataType.UINT8
        ModelInputDataType.AUTO -> when (tensorElementType) {
            TfliteTensorElementType.FLOAT32 -> ModelInputDataType.FLOAT32
            TfliteTensorElementType.INT8 -> ModelInputDataType.INT8
            // Qualcomm AI Hub exports use asymmetric UINT8 (zero-point 0, scale 1/255) rather than
            // the symmetric INT8 the older onnx2tf models used. Both are byte tensors; only the
            // quantization interpretation differs (see quantizeFloatInputUnsigned / unsigned dequant).
            TfliteTensorElementType.UINT8 -> ModelInputDataType.UINT8
            else -> error("Unsupported model input tensor element type: $tensorElementType")
        }
    }
    return ResolvedModelInputDataType(
        dataType = resolved,
        elementTypeName = tensorElementType.name,
    )
}

internal fun ModelAcceleratorBackend.toLiteRtAccelerator(): Accelerator {
    return when (this) {
        ModelAcceleratorBackend.NPU -> Accelerator.NPU
        ModelAcceleratorBackend.GPU -> Accelerator.GPU
        ModelAcceleratorBackend.CPU -> Accelerator.CPU
    }
}
