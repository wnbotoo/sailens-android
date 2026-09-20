package com.sailens.data.source.ml

import com.sailens.core.frame.ImageFrame

interface FramePreprocessor : AutoCloseable {
    fun preprocess(frame: ImageFrame, rotationDegrees: Int, outputArray: FloatArray)
    fun postprocess(scores: FloatArray, resultMask: IntArray)
}
