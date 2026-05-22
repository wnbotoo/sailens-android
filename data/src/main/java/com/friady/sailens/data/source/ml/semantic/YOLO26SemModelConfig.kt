package com.friady.sailens.data.source.ml.semantic

import com.friady.sailens.data.source.ml.ModelInputDataType
import com.friady.sailens.data.source.ml.ModelInputQuantization

/**
 * YOLO26-sem model contract. Spatial input/output dimensions are read from the
 * TFLite tensor metadata at runtime, so replacing the asset with a lower
 * resolution model does not require changing width/height constants.
 */
data class YOLO26SemModelConfig(
    val assetPath: String = "yolo26n-sem_int8.tflite",
    val inputTensorName: String = "images",
    val outputTensorName: String = "Identity",
    val outputChannels: Int = 19,
    val inputDataType: ModelInputDataType = ModelInputDataType.AUTO,
    val inputQuantization: ModelInputQuantization = ModelInputQuantization(),
    val preferNativeYuvPreprocessing: Boolean = true,
)
