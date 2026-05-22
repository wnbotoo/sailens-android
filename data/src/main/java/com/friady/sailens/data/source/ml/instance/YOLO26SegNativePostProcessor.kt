package com.friady.sailens.data.source.ml.instance

import com.friady.sailens.data.source.mapper.CocoClassMapper
import com.friady.sailens.data.source.ml.NativeMlLibrary
import com.friady.sailens.domain.model.common.NormalizedRect
import com.friady.sailens.domain.model.common.ObstacleCategory
import com.friady.sailens.domain.model.perception.DetectedInstance
import com.friady.sailens.domain.model.perception.ImageFrame

internal class YOLO26SegNativePostProcessor(
    private val classMapper: CocoClassMapper,
    private val inputSize: Int,
    private val classCount: Int,
    private val maskCoefficientCount: Int,
    private val confidenceThreshold: Float,
    private val nmsThreshold: Float,
    private val maxDetections: Int,
) {
    private val allowedClassIds: IntArray = (0 until classCount)
        .filter { classMapper.toObstacleCategory(it) != ObstacleCategory.UNKNOWN }
        .toIntArray()

    fun postProcess(
        frame: ImageFrame,
        rawDetections: FloatArray,
    ): List<DetectedInstance>? {
        if (!NativeMlLibrary.isAvailable || rawDetections.isEmpty()) return null

        val flatResult = runCatching {
            nativePostProcess(
                rawDetections = rawDetections,
                frameWidth = frame.width,
                frameHeight = frame.height,
                rotationDegrees = frame.rotationDegrees,
                inputSize = inputSize,
                classCount = classCount,
                maskCoefficientCount = maskCoefficientCount,
                confidenceThreshold = confidenceThreshold,
                nmsThreshold = nmsThreshold,
                maxDetections = maxDetections,
                allowedClassIds = allowedClassIds,
            )
        }.getOrElse {
            return null
        }

        if (flatResult.isEmpty()) return emptyList()

        val detections = ArrayList<DetectedInstance>(flatResult.size / VALUES_PER_DETECTION)
        var index = 0
        while (index + VALUES_PER_DETECTION <= flatResult.size) {
            val classId = flatResult[index].toInt()
            val confidence = flatResult[index + 1]
            val category = classMapper.toObstacleCategory(classId)
            if (category != ObstacleCategory.UNKNOWN) {
                detections += DetectedInstance(
                    classId = classId,
                    className = classMapper.getClassName(classId),
                    confidence = confidence,
                    boundingBox = NormalizedRect(
                        x = flatResult[index + 2].coerceIn(0f, 1f),
                        y = flatResult[index + 3].coerceIn(0f, 1f),
                        width = flatResult[index + 4].coerceIn(0f, 1f),
                        height = flatResult[index + 5].coerceIn(0f, 1f),
                    ),
                    mask = null,
                    category = category,
                )
            }
            index += VALUES_PER_DETECTION
        }
        return detections
    }

    private external fun nativePostProcess(
        rawDetections: FloatArray,
        frameWidth: Int,
        frameHeight: Int,
        rotationDegrees: Int,
        inputSize: Int,
        classCount: Int,
        maskCoefficientCount: Int,
        confidenceThreshold: Float,
        nmsThreshold: Float,
        maxDetections: Int,
        allowedClassIds: IntArray,
    ): FloatArray

    private companion object {
        const val VALUES_PER_DETECTION = 6
    }
}
