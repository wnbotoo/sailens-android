package com.friady.sailens.data.source.ml

import com.google.ai.edge.litert.TensorType

enum class ModelInputDataType {
    AUTO,
    FLOAT32,
    INT8,
}

data class ModelInputQuantization(
    val scale: Float = 1f / 255f,
    val zeroPoint: Int = -128,
)

data class YoloTensorConfig(
    val inputWidth: Int,
    val inputHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int,
    val outputChannels: Int,
    val mean: Triple<Float, Float, Float>,
    val std: Triple<Float, Float, Float>,
    val confidenceThreshold: Float = 0f,
)

data class ResolvedModelInputDataType(
    val dataType: ModelInputDataType,
    val elementTypeName: String,
)

internal fun resolveModelInputDataType(
    configured: ModelInputDataType,
    tensorElementType: TensorType.ElementType,
): ResolvedModelInputDataType {
    val resolved = when (configured) {
        ModelInputDataType.FLOAT32 -> ModelInputDataType.FLOAT32
        ModelInputDataType.INT8 -> ModelInputDataType.INT8
        ModelInputDataType.AUTO -> when (tensorElementType) {
            TensorType.ElementType.FLOAT -> ModelInputDataType.FLOAT32
            TensorType.ElementType.INT8 -> ModelInputDataType.INT8
            else -> error("Unsupported YOLO input tensor element type: $tensorElementType")
        }
    }
    return ResolvedModelInputDataType(
        dataType = resolved,
        elementTypeName = tensorElementType.name,
    )
}
