package com.friady.sailens.data.source.ml

import com.friady.sailens.domain.model.perception.ImageFrame

interface FramePreprocessor : AutoCloseable {
    fun preprocess(frame: ImageFrame, rotationDegrees: Int, outputArray: FloatArray)
    fun postprocess(scores: FloatArray, resultMask: IntArray)
}
