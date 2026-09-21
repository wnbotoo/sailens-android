package com.sailens.vision.detection

import com.sailens.runtime.TensorQuantization
import com.sailens.core.geometry.NormalizedRect
import com.sailens.core.frame.ImageFrame
import com.sailens.vision.taxonomy.CocoTaxonomy
import com.sailens.vision.taxonomy.Taxonomy
import kotlin.math.roundToInt

data class DetectionPostProcessOutput(
    val detections: List<Detection>,
    val backend: String,
)

enum class DetectionLayout {
    RAW_TRANSPOSED,
    END_TO_END,
}

/**
 * Post-processes single-network detection heads.
 *
 * Supported raw detection tensor layout:
 *   [1, 4 + classCount, detectionCount]
 *
 * Supported end-to-end detection tensor layout:
 *   [1, detectionCount, 6]
 *
 * Raw tensors expose center-x, center-y, width, height, and class scores. End-to-end tensors
 * expose x1, y1, x2, y2, confidence, and class id.
 */
class DetectionPostProcessor(
    private val taxonomy: Taxonomy = CocoTaxonomy,
    private val inputSize: Int = 640,
    private val classCount: Int = taxonomy.classCount,
    private val detectionLayout: DetectionLayout = DetectionLayout.RAW_TRANSPOSED,
    private val confidenceThreshold: Float = 0.25f,
    private val maxDetections: Int = 10,
    /**
     * Which classes the caller cares about. Everything else is dropped before NMS, which also
     * keeps the hot loop off classes nobody will read. Which classes matter is a product
     * judgement and is therefore supplied, not decided here (architecture.md §6.3).
     */
    allowedClassIds: IntArray = IntArray(classCount) { it },
) {

    private val rawAttributesPerDetection = RAW_BOX_ATTRIBUTES + classCount
    private val attributesPerDetection = when (detectionLayout) {
        DetectionLayout.RAW_TRANSPOSED -> rawAttributesPerDetection
        DetectionLayout.END_TO_END -> END_TO_END_FIXED_ATTRIBUTES
    }
    private val allowedClassIds: IntArray = allowedClassIds.filter { it in 0 until classCount }.toIntArray()
    private val allowedClassLookup: BooleanArray = BooleanArray(classCount).also { lookup ->
        this.allowedClassIds.forEach { lookup[it] = true }
    }
    private val nativePostProcessor = NativeDetectionPostProcessor(
        taxonomy = taxonomy,
        inputSize = inputSize,
        confidenceThreshold = confidenceThreshold,
        maxDetections = maxDetections,
        allowedClassIds = this.allowedClassIds,
    )

    fun postProcess(
        frame: ImageFrame,
        rawDetections: FloatArray,
    ): List<Detection> = postProcessWithBackend(
        frame = frame,
        rawDetections = rawDetections,
    ).detections

    fun postProcessWithBackend(
        frame: ImageFrame,
        rawDetections: FloatArray,
    ): DetectionPostProcessOutput {
        if (rawDetections.isEmpty()) return DetectionPostProcessOutput(emptyList(), "empty")
        if (!isValidShape(rawDetections.size)) {
            return DetectionPostProcessOutput(emptyList(), "invalid_shape")
        }

        if (detectionLayout == DetectionLayout.END_TO_END) {
            return DetectionPostProcessOutput(
                detections = postProcessEndToEnd(
                    frame = frame,
                    elementCount = rawDetections.size,
                    scoreAt = { index -> rawDetections[index] },
                ),
                backend = "kotlin_e2e",
            )
        }

        nativePostProcessor.postProcessFloat(
            frame = frame,
            rawDetections = rawDetections,
            attributesPerDetection = rawAttributesPerDetection,
        )?.let { nativeResult ->
            return DetectionPostProcessOutput(nativeResult, "native_bbox_nms")
        }

        return DetectionPostProcessOutput(
            detections = postProcessRaw(
                frame = frame,
                elementCount = rawDetections.size,
                scoreAt = { index -> rawDetections[index] },
            ),
            backend = "kotlin_bbox_nms",
        )
    }

    fun postProcessWithBackend(
        frame: ImageFrame,
        rawDetections: ByteArray,
        quantization: TensorQuantization?,
    ): DetectionPostProcessOutput {
        if (rawDetections.isEmpty()) return DetectionPostProcessOutput(emptyList(), "empty")
        if (!isValidShape(rawDetections.size)) {
            return DetectionPostProcessOutput(emptyList(), "invalid_shape")
        }

        val scale = quantization?.scale ?: 1f
        val zeroPoint = quantization?.zeroPoint ?: 0

        if (detectionLayout == DetectionLayout.END_TO_END) {
            return DetectionPostProcessOutput(
                detections = postProcessEndToEnd(
                    frame = frame,
                    elementCount = rawDetections.size,
                    scoreAt = { index -> (rawDetections[index].toInt() - zeroPoint) * scale },
                ),
                backend = "kotlin_e2e_int8",
            )
        }

        nativePostProcessor.postProcessInt8(
            frame = frame,
            rawDetections = rawDetections,
            attributesPerDetection = rawAttributesPerDetection,
            quantization = quantization,
        )?.let { nativeResult ->
            return DetectionPostProcessOutput(nativeResult, "native_bbox_nms_int8")
        }

        return DetectionPostProcessOutput(
            detections = postProcessRaw(
                frame = frame,
                elementCount = rawDetections.size,
                scoreAt = { index -> (rawDetections[index].toInt() - zeroPoint) * scale },
            ),
            backend = "kotlin_bbox_nms_int8",
        )
    }

    /** Zero-copy realtime path: decode detections straight from the output buffer handle.
     * Returns null when the native handle path is unavailable so the caller can fall back to the read
     * + array path. */
    fun postProcessFloatFromHandle(
        frame: ImageFrame,
        tensorBufferHandle: Long,
        rawElementCount: Int,
    ): DetectionPostProcessOutput? {
        if (detectionLayout != DetectionLayout.RAW_TRANSPOSED) return null
        val detections = nativePostProcessor.postProcessFloatFromHandle(
            tensorBufferHandle = tensorBufferHandle,
            rawElementCount = rawElementCount,
            frame = frame,
            attributesPerDetection = rawAttributesPerDetection,
        ) ?: return null
        return DetectionPostProcessOutput(detections, "native_bbox_nms_float_handle")
    }

    fun postProcessInt8FromHandle(
        frame: ImageFrame,
        tensorBufferHandle: Long,
        rawElementCount: Int,
        quantization: TensorQuantization?,
    ): DetectionPostProcessOutput? {
        if (detectionLayout != DetectionLayout.RAW_TRANSPOSED) return null
        val detections = nativePostProcessor.postProcessInt8FromHandle(
            tensorBufferHandle = tensorBufferHandle,
            rawElementCount = rawElementCount,
            frame = frame,
            attributesPerDetection = rawAttributesPerDetection,
            quantization = quantization,
        ) ?: return null
        return DetectionPostProcessOutput(detections, "native_bbox_nms_int8_handle")
    }

    private fun isValidShape(elementCount: Int): Boolean {
        return attributesPerDetection > RAW_BOX_ATTRIBUTES &&
            elementCount > 0 &&
            elementCount % attributesPerDetection == 0
    }

    private fun postProcessRaw(
        frame: ImageFrame,
        elementCount: Int,
        scoreAt: (Int) -> Float,
    ): List<Detection> {
        val detectionCount = elementCount / attributesPerDetection
        if (detectionCount == 0 || allowedClassIds.isEmpty()) return emptyList()

        val geometry = LetterboxGeometry.from(frame, inputSize)
        val candidates = ArrayList<Candidate>(minOf(detectionCount, MAX_NMS_CANDIDATES))

        for (detectionIndex in 0 until detectionCount) {
            var bestClassId = -1
            var bestConfidence = confidenceThreshold

            for (classId in allowedClassIds) {
                val scoreIndex = (RAW_BOX_ATTRIBUTES + classId) * detectionCount + detectionIndex
                val confidence = scoreAt(scoreIndex)
                if (confidence > bestConfidence) {
                    bestConfidence = confidence
                    bestClassId = classId
                }
            }

            if (bestClassId < 0) continue

            val decodedRect = decodeRawRect(
                geometry = geometry,
                cx = scoreAt(detectionIndex),
                cy = scoreAt(detectionCount + detectionIndex),
                width = scoreAt(detectionCount * 2 + detectionIndex),
                height = scoreAt(detectionCount * 3 + detectionIndex),
            ) ?: continue

            candidates += Candidate(
                classId = bestClassId,
                confidence = bestConfidence,
                boundingBox = decodedRect.normalizedRect,
            )
        }

        if (candidates.isEmpty()) return emptyList()

        candidates.sortByDescending { it.confidence }
        val selected = applyClassAwareNms(
            candidates = candidates.take(MAX_NMS_CANDIDATES),
            maxDetections = maxDetections,
        )
        return selected.map { candidate -> candidate.toDetection() }
    }

    private fun postProcessEndToEnd(
        frame: ImageFrame,
        elementCount: Int,
        scoreAt: (Int) -> Float,
    ): List<Detection> {
        val detectionCount = elementCount / END_TO_END_FIXED_ATTRIBUTES
        if (detectionCount == 0 || allowedClassIds.isEmpty()) return emptyList()

        val geometry = LetterboxGeometry.from(frame, inputSize)
        val candidates = ArrayList<Candidate>(minOf(detectionCount, MAX_NMS_CANDIDATES))

        for (detectionIndex in 0 until detectionCount) {
            val base = detectionIndex * END_TO_END_FIXED_ATTRIBUTES
            val confidence = scoreAt(base + END_TO_END_CONFIDENCE_OFFSET)
            if (confidence <= confidenceThreshold) continue

            val classId = scoreAt(base + END_TO_END_CLASS_OFFSET).roundToInt()
            if (!isAllowedClassId(classId)) continue

            val decodedRect = decodeEndToEndRect(
                geometry = geometry,
                left = scoreAt(base),
                top = scoreAt(base + 1),
                right = scoreAt(base + 2),
                bottom = scoreAt(base + 3),
            ) ?: continue

            candidates += Candidate(
                classId = classId,
                confidence = confidence,
                boundingBox = decodedRect.normalizedRect,
            )
        }

        if (candidates.isEmpty()) return emptyList()

        candidates.sortByDescending { it.confidence }
        val selected = applyClassAwareNms(
            candidates = candidates.take(MAX_NMS_CANDIDATES),
            maxDetections = maxDetections,
        )
        return selected.map { candidate -> candidate.toDetection() }
    }

    private fun applyClassAwareNms(
        candidates: List<Candidate>,
        maxDetections: Int,
    ): List<Candidate> {
        val selected = ArrayList<Candidate>(maxDetections)
        for (candidate in candidates) {
            if (selected.size >= maxDetections) break

            var suppressed = false
            for (existing in selected) {
                if (
                    existing.classId == candidate.classId &&
                    intersectionOverUnion(existing.boundingBox, candidate.boundingBox) > NMS_IOU_THRESHOLD
                ) {
                    suppressed = true
                    break
                }
            }
            if (!suppressed) {
                selected += candidate
            }
        }
        return selected
    }

    private fun intersectionOverUnion(a: NormalizedRect, b: NormalizedRect): Float {
        val left = maxOf(a.x, b.x)
        val top = maxOf(a.y, b.y)
        val right = minOf(a.x + a.width, b.x + b.width)
        val bottom = minOf(a.y + a.height, b.y + b.height)
        val intersectionWidth = maxOf(0f, right - left)
        val intersectionHeight = maxOf(0f, bottom - top)
        val intersectionArea = intersectionWidth * intersectionHeight
        if (intersectionArea <= 0f) return 0f

        val unionArea = a.width * a.height + b.width * b.height - intersectionArea
        return if (unionArea > 0f) intersectionArea / unionArea else 0f
    }

    private fun decodeRawRect(
        geometry: LetterboxGeometry,
        cx: Float,
        cy: Float,
        width: Float,
        height: Float,
    ): DecodedRect? {
        val modelCx = toModelPixels(cx)
        val modelCy = toModelPixels(cy)
        val modelWidth = toModelPixels(width)
        val modelHeight = toModelPixels(height)

        return decodeModelRect(
            geometry = geometry,
            modelLeft = modelCx - modelWidth / 2f,
            modelTop = modelCy - modelHeight / 2f,
            modelRight = modelCx + modelWidth / 2f,
            modelBottom = modelCy + modelHeight / 2f,
        )
    }

    private fun decodeEndToEndRect(
        geometry: LetterboxGeometry,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): DecodedRect? {
        val decodedXyxy = decodeModelRect(
            geometry = geometry,
            modelLeft = toModelPixels(left),
            modelTop = toModelPixels(top),
            modelRight = toModelPixels(right),
            modelBottom = toModelPixels(bottom),
        )
        if (decodedXyxy != null) return decodedXyxy

        return decodeRawRect(
            geometry = geometry,
            cx = left,
            cy = top,
            width = right,
            height = bottom,
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
        )
    }

    private fun toModelPixels(value: Float): Float {
        return if (value <= 2f) value * inputSize else value
    }

    private fun Candidate.toDetection(): Detection {
        return Detection(
            classId = classId,
            label = taxonomy.label(classId),
            confidence = confidence,
            boundingBox = boundingBox,
        )
    }

    private fun isAllowedClassId(classId: Int): Boolean {
        return classId in allowedClassLookup.indices && allowedClassLookup[classId]
    }

    private data class DecodedRect(
        val normalizedRect: NormalizedRect,
    )

    private class Candidate(
        val classId: Int,
        val confidence: Float,
        val boundingBox: NormalizedRect,
    )

    private companion object {
        const val RAW_BOX_ATTRIBUTES = 4
        const val END_TO_END_FIXED_ATTRIBUTES = 6
        const val END_TO_END_CONFIDENCE_OFFSET = 4
        const val END_TO_END_CLASS_OFFSET = 5
        const val NMS_IOU_THRESHOLD = 0.45f
        const val MAX_NMS_CANDIDATES = 300
    }
}
