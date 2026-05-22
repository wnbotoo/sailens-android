package com.friady.sailens.data.source.ml.instance

/**
 * YOLO26-seg model contract. The input size is read from the TFLite tensor
 * metadata at runtime; the current bbox post-processing path expects a square
 * letterboxed model input.
 */
data class YOLO26SegModelConfig(
    val assetPath: String = "yolo26n-seg_int8.tflite",
    val inputTensorName: String = "images",
    val classCount: Int = 80,
    val maskCoefficientCount: Int = 32,
)
