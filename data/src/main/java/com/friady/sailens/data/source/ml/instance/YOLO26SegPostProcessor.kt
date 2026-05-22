package com.friady.sailens.data.source.ml.instance

import com.friady.sailens.data.source.mapper.CocoClassMapper
import com.friady.sailens.domain.model.common.BinaryMask
import com.friady.sailens.domain.model.common.NormalizedRect
import com.friady.sailens.domain.model.common.ObstacleCategory
import com.friady.sailens.domain.model.perception.DetectedInstance
import com.friady.sailens.domain.model.perception.ImageFrame

private enum class DetectionLayout {
    ATTRIBUTE_MAJOR,
    DETECTION_MAJOR,
}

private enum class PrototypeLayout {
    CHANNELS_FIRST,
    CHANNELS_LAST,
}

/**
 * Post-processes YOLO26-seg outputs into lightweight instance detections.
 *
 * 当前实现同时支持：
 * 1. bbox/class/confidence 解码
 * 2. 原型掩码（prototype mask）重建，生成 BitSet-backed BinaryMask
 */
class YOLO26SegPostProcessor(
    private val classMapper: CocoClassMapper = CocoClassMapper(),
    private val inputSize: Int = 640,
    private val classCount: Int = 80,
    private val maskCoefficientCount: Int = 32,
    private val confidenceThreshold: Float = 0.25f,
    private val nmsThreshold: Float = 0.45f,
    private val maxDetections: Int = 10,
    private val enableMaskReconstruction: Boolean = false,
) {

    private val legacyAttributeCount = 4 + classCount + maskCoefficientCount
    private val endToEndAttributeCount = 4 + 1 + 1 + maskCoefficientCount
    private val nativePostProcessor = YOLO26SegNativePostProcessor(
        classMapper = classMapper,
        inputSize = inputSize,
        classCount = classCount,
        maskCoefficientCount = maskCoefficientCount,
        confidenceThreshold = confidenceThreshold,
        nmsThreshold = nmsThreshold,
        maxDetections = maxDetections,
    )

    fun postProcess(
        frame: ImageFrame,
        rawDetections: FloatArray,
        rawPrototypes: FloatArray? = null,
    ): List<DetectedInstance> {
        if (rawDetections.isEmpty()) return emptyList()
        if (!enableMaskReconstruction) {
            nativePostProcessor.postProcess(frame, rawDetections)?.let { nativeResult ->
                return nativeResult
            }
        }
        return when {
            rawDetections.size % endToEndAttributeCount == 0 -> {
                postProcessEndToEnd(frame, rawDetections, rawPrototypes)
            }

            rawDetections.size % legacyAttributeCount == 0 -> {
                postProcessLegacy(frame, rawDetections, rawPrototypes)
            }

            else -> emptyList()
        }
    }

    private fun postProcessEndToEnd(
        frame: ImageFrame,
        rawDetections: FloatArray,
        rawPrototypes: FloatArray?,
    ): List<DetectedInstance> {
        val detectionCount = rawDetections.size / endToEndAttributeCount
        if (detectionCount == 0) return emptyList()

        val geometry = LetterboxGeometry.from(frame, inputSize)
        val prototypeSpec = rawPrototypes?.let { PrototypeSpec.from(it, maskCoefficientCount) }
        val candidates = ArrayList<Candidate>(minOf(detectionCount, maxDetections * 4))

        for (detectionIndex in 0 until detectionCount) {
            val baseIndex = detectionIndex * endToEndAttributeCount
            val confidence = rawDetections[baseIndex + 4]
            if (confidence < confidenceThreshold) continue

            val classId = rawDetections[baseIndex + 5].toInt()
            val category = classMapper.toObstacleCategory(classId)
            if (category == ObstacleCategory.UNKNOWN) continue

            val decodedRect = decodeEndToEndRect(geometry, rawDetections, baseIndex) ?: continue

            candidates += Candidate(
                classId = classId,
                confidence = confidence,
                boundingBox = decodedRect.normalizedRect,
                modelRect = decodedRect.modelRect,
                maskCoefficients = extractEndToEndMaskCoefficients(rawDetections, baseIndex),
            )
        }

        if (candidates.isEmpty()) return emptyList()

        return candidates
            .sortedByDescending { it.confidence }
            .take(maxDetections)
            .map { candidate ->
                candidate.toDetectedInstance(
                    geometry = geometry,
                    prototypeSpec = prototypeSpec,
                    prototypeLayout = PrototypeLayout.CHANNELS_LAST,
                )
            }
    }

    private fun postProcessLegacy(
        frame: ImageFrame,
        rawDetections: FloatArray,
        rawPrototypes: FloatArray?,
    ): List<DetectedInstance> {
        val detectionCount = rawDetections.size / legacyAttributeCount
        if (detectionCount == 0) return emptyList()

        val layout = chooseLayout(rawDetections, detectionCount)
        val geometry = LetterboxGeometry.from(frame, inputSize)
        val prototypeSpec = rawPrototypes?.let { PrototypeSpec.from(it, maskCoefficientCount) }
        val candidates = ArrayList<Candidate>(maxDetections * 2)

        for (detectionIndex in 0 until detectionCount) {
            val bestClass = findBestClass(rawDetections, layout, detectionIndex)
            if (bestClass.confidence < confidenceThreshold) continue

            val category = classMapper.toObstacleCategory(bestClass.classId)
            if (category == ObstacleCategory.UNKNOWN) continue

            val decodedRect = decodeRect(geometry, rawDetections, layout, detectionIndex) ?: continue

            candidates += Candidate(
                classId = bestClass.classId,
                confidence = bestClass.confidence,
                boundingBox = decodedRect.normalizedRect,
                modelRect = decodedRect.modelRect,
                maskCoefficients = extractMaskCoefficients(rawDetections, layout, detectionIndex),
            )
        }

        if (candidates.isEmpty()) return emptyList()

        val kept = runNms(candidates)
        val prototypeLayout = choosePrototypeLayout(prototypeSpec, kept.firstOrNull(), geometry)

        return kept.take(maxDetections).map { candidate ->
            candidate.toDetectedInstance(
                geometry = geometry,
                prototypeSpec = prototypeSpec,
                prototypeLayout = prototypeLayout,
            )
        }
    }

    private fun chooseLayout(
        rawDetections: FloatArray,
        detectionCount: Int,
    ): DetectionLayout {
        val sampleSize = minOf(detectionCount, 64)
        var attributeMajorHits = 0
        var detectionMajorHits = 0

        for (index in 0 until sampleSize) {
            if (findBestClass(rawDetections, DetectionLayout.ATTRIBUTE_MAJOR, index).confidence.isPlausibleConfidence()) {
                attributeMajorHits++
            }
            if (findBestClass(rawDetections, DetectionLayout.DETECTION_MAJOR, index).confidence.isPlausibleConfidence()) {
                detectionMajorHits++
            }
        }

        return if (detectionMajorHits > attributeMajorHits) {
            DetectionLayout.DETECTION_MAJOR
        } else {
            DetectionLayout.ATTRIBUTE_MAJOR
        }
    }

    private fun findBestClass(
        rawDetections: FloatArray,
        layout: DetectionLayout,
        detectionIndex: Int,
    ): ClassScore {
        var bestClassId = -1
        var bestConfidence = Float.NEGATIVE_INFINITY

        for (classOffset in 0 until classCount) {
            val confidence = rawDetections.valueAt(layout, detectionIndex, 4 + classOffset, legacyAttributeCount)
            if (confidence > bestConfidence) {
                bestConfidence = confidence
                bestClassId = classOffset
            }
        }

        return ClassScore(bestClassId, bestConfidence)
    }

    private fun decodeRect(
        geometry: LetterboxGeometry,
        rawDetections: FloatArray,
        layout: DetectionLayout,
        detectionIndex: Int,
    ): DecodedRect? {
        val cx = toModelPixels(rawDetections.valueAt(layout, detectionIndex, 0, legacyAttributeCount))
        val cy = toModelPixels(rawDetections.valueAt(layout, detectionIndex, 1, legacyAttributeCount))
        val width = toModelPixels(rawDetections.valueAt(layout, detectionIndex, 2, legacyAttributeCount))
        val height = toModelPixels(rawDetections.valueAt(layout, detectionIndex, 3, legacyAttributeCount))

        if (width <= 0f || height <= 0f) return null

        val modelLeft = cx - width / 2f
        val modelTop = cy - height / 2f
        val modelRight = cx + width / 2f
        val modelBottom = cy + height / 2f

        return decodeModelRect(geometry, modelLeft, modelTop, modelRight, modelBottom)
    }

    private fun decodeEndToEndRect(
        geometry: LetterboxGeometry,
        rawDetections: FloatArray,
        baseIndex: Int,
    ): DecodedRect? {
        val modelLeft = toModelPixels(rawDetections[baseIndex])
        val modelTop = toModelPixels(rawDetections[baseIndex + 1])
        val modelRight = toModelPixels(rawDetections[baseIndex + 2])
        val modelBottom = toModelPixels(rawDetections[baseIndex + 3])

        return decodeModelRect(
            geometry = geometry,
            modelLeft = minOf(modelLeft, modelRight),
            modelTop = minOf(modelTop, modelBottom),
            modelRight = maxOf(modelLeft, modelRight),
            modelBottom = maxOf(modelTop, modelBottom),
        )
    }

    private fun decodeModelRect(
        geometry: LetterboxGeometry,
        modelLeft: Float,
        modelTop: Float,
        modelRight: Float,
        modelBottom: Float,
    ): DecodedRect? {
        if (modelRight <= modelLeft || modelBottom <= modelTop) return null

        val unpaddedLeft = ((modelLeft - geometry.padX) / geometry.scale).coerceIn(0f, geometry.rotatedWidth.toFloat())
        val unpaddedTop = ((modelTop - geometry.padY) / geometry.scale).coerceIn(0f, geometry.rotatedHeight.toFloat())
        val unpaddedRight = ((modelRight - geometry.padX) / geometry.scale).coerceIn(0f, geometry.rotatedWidth.toFloat())
        val unpaddedBottom = ((modelBottom - geometry.padY) / geometry.scale).coerceIn(0f, geometry.rotatedHeight.toFloat())

        if (unpaddedRight <= unpaddedLeft || unpaddedBottom <= unpaddedTop) return null

        return DecodedRect(
            normalizedRect = NormalizedRect(
                x = (unpaddedLeft / geometry.rotatedWidth).coerceIn(0f, 1f),
                y = (unpaddedTop / geometry.rotatedHeight).coerceIn(0f, 1f),
                width = ((unpaddedRight - unpaddedLeft) / geometry.rotatedWidth).coerceIn(0f, 1f),
                height = ((unpaddedBottom - unpaddedTop) / geometry.rotatedHeight).coerceIn(0f, 1f),
            ),
            modelRect = FloatRect(
                left = modelLeft.coerceIn(0f, inputSize.toFloat()),
                top = modelTop.coerceIn(0f, inputSize.toFloat()),
                right = modelRight.coerceIn(0f, inputSize.toFloat()),
                bottom = modelBottom.coerceIn(0f, inputSize.toFloat()),
            ),
        )
    }

    private fun extractMaskCoefficients(
        rawDetections: FloatArray,
        layout: DetectionLayout,
        detectionIndex: Int,
    ): FloatArray {
        val coefficients = FloatArray(maskCoefficientCount)
        for (index in 0 until maskCoefficientCount) {
            coefficients[index] = rawDetections.valueAt(
                layout = layout,
                detectionIndex = detectionIndex,
                attributeIndex = 4 + classCount + index,
                attributesPerDetection = legacyAttributeCount,
            )
        }
        return coefficients
    }

    private fun extractEndToEndMaskCoefficients(
        rawDetections: FloatArray,
        baseIndex: Int,
    ): FloatArray {
        val coefficients = FloatArray(maskCoefficientCount)
        val coefficientStart = baseIndex + 6
        for (index in 0 until maskCoefficientCount) {
            coefficients[index] = rawDetections[coefficientStart + index]
        }
        return coefficients
    }

    private fun choosePrototypeLayout(
        prototypeSpec: PrototypeSpec?,
        candidate: Candidate?,
        geometry: LetterboxGeometry,
    ): PrototypeLayout {
        if (prototypeSpec == null || candidate == null) return PrototypeLayout.CHANNELS_FIRST

        val channelsFirstScore = estimateMaskAlignmentScore(
            candidate = candidate,
            geometry = geometry,
            prototypeSpec = prototypeSpec,
            layout = PrototypeLayout.CHANNELS_FIRST,
        )
        val channelsLastScore = estimateMaskAlignmentScore(
            candidate = candidate,
            geometry = geometry,
            prototypeSpec = prototypeSpec,
            layout = PrototypeLayout.CHANNELS_LAST,
        )

        return if (channelsLastScore > channelsFirstScore) {
            PrototypeLayout.CHANNELS_LAST
        } else {
            PrototypeLayout.CHANNELS_FIRST
        }
    }

    private fun estimateMaskAlignmentScore(
        candidate: Candidate,
        geometry: LetterboxGeometry,
        prototypeSpec: PrototypeSpec,
        layout: PrototypeLayout,
    ): Float {
        val midX = ((candidate.modelRect.left + candidate.modelRect.right) / 2f / inputSize)
            .coerceIn(0f, 0.999f)
        val midY = ((candidate.modelRect.top + candidate.modelRect.bottom) / 2f / inputSize)
            .coerceIn(0f, 0.999f)
        val edgeX = ((candidate.modelRect.left / inputSize) * 0.7f).coerceIn(0f, 0.999f)
        val edgeY = ((candidate.modelRect.top / inputSize) * 0.7f).coerceIn(0f, 0.999f)

        val inside = maskLogitAt(candidate.maskCoefficients, prototypeSpec, layout, midX, midY)
        val outside = maskLogitAt(candidate.maskCoefficients, prototypeSpec, layout, edgeX, edgeY)
        return inside - (outside * 0.5f) + geometry.scale
    }

    private fun buildMask(
        candidate: Candidate,
        geometry: LetterboxGeometry,
        prototypeSpec: PrototypeSpec?,
        prototypeLayout: PrototypeLayout,
    ): BinaryMask? {
        if (!enableMaskReconstruction || prototypeSpec == null) return null

        val mask = BinaryMask(geometry.rotatedWidth, geometry.rotatedHeight)
        val xStart = (candidate.boundingBox.x * geometry.rotatedWidth).toInt().coerceIn(0, geometry.rotatedWidth - 1)
        val yStart = (candidate.boundingBox.y * geometry.rotatedHeight).toInt().coerceIn(0, geometry.rotatedHeight - 1)
        val xEnd = ((candidate.boundingBox.maxX) * geometry.rotatedWidth).toInt().coerceIn(0, geometry.rotatedWidth)
        val yEnd = ((candidate.boundingBox.maxY) * geometry.rotatedHeight).toInt().coerceIn(0, geometry.rotatedHeight)

        if (xEnd <= xStart || yEnd <= yStart) return null

        var foregroundCount = 0
        for (y in yStart until yEnd) {
            val modelY = y * geometry.scale + geometry.padY
            val normalizedY = (modelY / inputSize).coerceIn(0f, 0.999f)

            for (x in xStart until xEnd) {
                val modelX = x * geometry.scale + geometry.padX
                val normalizedX = (modelX / inputSize).coerceIn(0f, 0.999f)
                val probability = sigmoid(
                    maskLogitAt(candidate.maskCoefficients, prototypeSpec, prototypeLayout, normalizedX, normalizedY)
                )
                if (probability >= 0.5f) {
                    mask.set(x, y, true)
                    foregroundCount++
                }
            }
        }

        return if (foregroundCount > 0) mask else null
    }

    private fun maskLogitAt(
        coefficients: FloatArray,
        prototypeSpec: PrototypeSpec,
        layout: PrototypeLayout,
        normalizedX: Float,
        normalizedY: Float,
    ): Float {
        val protoX = (normalizedX * prototypeSpec.width).toInt().coerceIn(0, prototypeSpec.width - 1)
        val protoY = (normalizedY * prototypeSpec.height).toInt().coerceIn(0, prototypeSpec.height - 1)

        var sum = 0f
        for (channel in coefficients.indices) {
            val prototypeValue = when (layout) {
                PrototypeLayout.CHANNELS_FIRST -> prototypeSpec.data[
                    channel * prototypeSpec.width * prototypeSpec.height +
                        protoY * prototypeSpec.width +
                        protoX
                ]

                PrototypeLayout.CHANNELS_LAST -> prototypeSpec.data[
                    (protoY * prototypeSpec.width + protoX) * prototypeSpec.channels + channel
                ]
            }
            sum += coefficients[channel] * prototypeValue
        }
        return sum
    }

    private fun runNms(candidates: List<Candidate>): List<Candidate> {
        val sorted = candidates.sortedByDescending { it.confidence }
        val kept = ArrayList<Candidate>(minOf(sorted.size, maxDetections))

        for (candidate in sorted) {
            val overlaps = kept.any { existing ->
                existing.classId == candidate.classId && existing.boundingBox.iou(candidate.boundingBox) >= nmsThreshold
            }
            if (!overlaps) {
                kept += candidate
                if (kept.size >= maxDetections) break
            }
        }

        return kept
    }

    private fun FloatArray.valueAt(
        layout: DetectionLayout,
        detectionIndex: Int,
        attributeIndex: Int,
        attributesPerDetection: Int,
    ): Float {
        return when (layout) {
            DetectionLayout.ATTRIBUTE_MAJOR -> this[attributeIndex * (size / attributesPerDetection) + detectionIndex]
            DetectionLayout.DETECTION_MAJOR -> this[detectionIndex * attributesPerDetection + attributeIndex]
        }
    }

    private fun toModelPixels(value: Float): Float {
        return if (value <= 2f) value * inputSize else value
    }

    private fun Float.isPlausibleConfidence(): Boolean {
        return this >= confidenceThreshold && this <= 1.5f
    }

    private fun sigmoid(value: Float): Float = (1f / (1f + kotlin.math.exp(-value)))

    private fun Candidate.toDetectedInstance(
        geometry: LetterboxGeometry,
        prototypeSpec: PrototypeSpec?,
        prototypeLayout: PrototypeLayout,
    ): DetectedInstance {
        return DetectedInstance(
            classId = classId,
            className = classMapper.getClassName(classId),
            confidence = confidence,
            boundingBox = boundingBox,
            mask = buildMask(
                candidate = this,
                geometry = geometry,
                prototypeSpec = prototypeSpec,
                prototypeLayout = prototypeLayout,
            ),
            category = classMapper.toObstacleCategory(classId),
        )
    }

    private data class ClassScore(
        val classId: Int,
        val confidence: Float,
    )

    private data class DecodedRect(
        val normalizedRect: NormalizedRect,
        val modelRect: FloatRect,
    )

    private data class FloatRect(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )

    private class Candidate(
        val classId: Int,
        val confidence: Float,
        val boundingBox: NormalizedRect,
        val modelRect: FloatRect,
        val maskCoefficients: FloatArray,
    )

    private class PrototypeSpec(
        val data: FloatArray,
        val channels: Int,
        val width: Int,
        val height: Int,
    ) {
        companion object {
            fun from(data: FloatArray, channels: Int): PrototypeSpec? {
                if (data.isEmpty() || data.size % channels != 0) return null
                val pixels = data.size / channels
                val side = kotlin.math.sqrt(pixels.toDouble()).toInt()
                if (side * side != pixels) return null
                return PrototypeSpec(
                    data = data,
                    channels = channels,
                    width = side,
                    height = side,
                )
            }
        }
    }

    private data class LetterboxGeometry(
        val rotatedWidth: Int,
        val rotatedHeight: Int,
        val scale: Float,
        val padX: Float,
        val padY: Float,
    ) {
        companion object {
            fun from(frame: ImageFrame, inputSize: Int): LetterboxGeometry {
                val rotatedWidth = if (frame.rotationDegrees == 90 || frame.rotationDegrees == 270) {
                    frame.height
                } else {
                    frame.width
                }
                val rotatedHeight = if (frame.rotationDegrees == 90 || frame.rotationDegrees == 270) {
                    frame.width
                } else {
                    frame.height
                }

                val scale = minOf(
                    inputSize.toFloat() / rotatedWidth,
                    inputSize.toFloat() / rotatedHeight,
                )
                val resizedWidth = rotatedWidth * scale
                val resizedHeight = rotatedHeight * scale

                return LetterboxGeometry(
                    rotatedWidth = rotatedWidth,
                    rotatedHeight = rotatedHeight,
                    scale = scale,
                    padX = (inputSize - resizedWidth) / 2f,
                    padY = (inputSize - resizedHeight) / 2f,
                )
            }
        }
    }
}
