package com.sailens.vision.semantic

import com.sailens.runtime.ImageTensorLayout

/**
 * Where one frame's semantic scores can be read from.
 *
 * The four variants are the four shapes a postprocessor can actually be handed: the output tensor
 * still sitting in LiteRT memory (float or int8), or a copy that has already been pulled into a
 * JVM array. The handle variants exist so a fused postprocessor can avoid the copy entirely
 * (architecture.md §6.5).
 */
sealed interface SemanticScores {
    /** Raw `LiteRtTensorBuffer*` for a FLOAT32 output tensor. Never zero. */
    @JvmInline
    value class FloatHandle(val tensorBufferHandle: Long) : SemanticScores

    /** Raw `LiteRtTensorBuffer*` for an INT8 output tensor. Never zero. */
    @JvmInline
    value class Int8Handle(val tensorBufferHandle: Long) : SemanticScores

    /** A FLOAT32 tensor already copied out of LiteRT. */
    @JvmInline
    value class FloatValues(val values: FloatArray) : SemanticScores

    /** An INT8 tensor already copied out of LiteRT, still quantized. */
    @JvmInline
    value class Int8Values(val values: ByteArray) : SemanticScores
}

/**
 * Geometry of the score tensor a [SemanticPostprocessor] is being asked to read.
 *
 * [width] and [height] are the whole score grid. [content] is the part of it that shows the camera
 * frame: the model is fed a letterboxed square, so a portrait frame leaves padding columns either
 * side and a landscape one padding rows above and below. Class maps a postprocessor produces cover
 * [content] only, which is what makes a class-map pixel and a detection box -- decoded back into
 * the frame -- refer to the same place.
 */
data class SemanticScoreSpec(
    val width: Int,
    val height: Int,
    val channels: Int,
    val layout: ImageTensorLayout,
    val content: SemanticContentRegion = SemanticContentRegion(0, 0, width, height),
)

/** A rectangle of the score grid, in grid pixels. */
data class SemanticContentRegion(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    val pixelCount: Int get() = width * height

    companion object {
        /**
         * The grid region the camera frame lands in after the letterbox preprocessing (rotate,
         * scale to fit, centre, pad). Mirrors the preprocessing kernel's active range and scales it
         * from the model input to the output grid, which may be smaller.
         */
        fun forLetterbox(
            frameWidth: Int,
            frameHeight: Int,
            rotationDegrees: Int,
            inputWidth: Int,
            inputHeight: Int,
            outputWidth: Int,
            outputHeight: Int,
        ): SemanticContentRegion {
            val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
            val rotated = normalizedRotation == 90 || normalizedRotation == 270
            val rotatedWidth = if (rotated) frameHeight else frameWidth
            val rotatedHeight = if (rotated) frameWidth else frameHeight
            if (rotatedWidth <= 0 || rotatedHeight <= 0 || inputWidth <= 0 || inputHeight <= 0) {
                return SemanticContentRegion(0, 0, outputWidth, outputHeight)
            }
            val scale = minOf(inputWidth.toFloat() / rotatedWidth, inputHeight.toFloat() / rotatedHeight)
            val padX = (inputWidth - rotatedWidth * scale) / 2f
            val padY = (inputHeight - rotatedHeight * scale) / 2f
            // Same active range as the native preprocess (ceil of the pad and of pad + extent).
            val startX = kotlin.math.ceil(padX).toInt().coerceIn(0, inputWidth)
            val endX = kotlin.math.ceil(padX + rotatedWidth * scale).toInt().coerceIn(startX, inputWidth)
            val startY = kotlin.math.ceil(padY).toInt().coerceIn(0, inputHeight)
            val endY = kotlin.math.ceil(padY + rotatedHeight * scale).toInt().coerceIn(startY, inputHeight)

            val sx = outputWidth.toFloat() / inputWidth
            val sy = outputHeight.toFloat() / inputHeight
            val x0 = kotlin.math.round(startX * sx).toInt().coerceIn(0, outputWidth - 1)
            val x1 = kotlin.math.round(endX * sx).toInt().coerceIn(x0 + 1, outputWidth)
            val y0 = kotlin.math.round(startY * sy).toInt().coerceIn(0, outputHeight - 1)
            val y1 = kotlin.math.round(endY * sy).toInt().coerceIn(y0 + 1, outputHeight)
            return SemanticContentRegion(x0, y0, x1 - x0, y1 - y0)
        }
    }
}

/** Copies [region] out of a grid-sized class map ([gridWidth] wide) into [out], row by row. */
fun cropClassMap(grid: IntArray, gridWidth: Int, region: SemanticContentRegion, out: IntArray) {
    require(out.size >= region.pixelCount) { "crop target too small: ${out.size} < ${region.pixelCount}" }
    for (row in 0 until region.height) {
        val from = (region.y + row) * gridWidth + region.x
        grid.copyInto(out, destinationOffset = row * region.width, startIndex = from, endIndex = from + region.width)
    }
}

/**
 * A postprocessed frame plus the name recorded in traces for the path that produced it.
 *
 * The backend name is part of the performance contract, not decoration: §12.3 reads it back to
 * check that the fused native pass is still the one running.
 */
data class SemanticPostprocessOutcome<out R>(
    val value: R,
    val backend: String,
)

/**
 * Turns semantic scores into a caller-defined result.
 *
 * Two entry points, deliberately:
 *
 * - [postprocessScores] is the fused path. An implementation that can compute everything it needs
 *   while it is already scanning the score tensor does so here and returns it, so the tensor is
 *   scanned once. Returning null declines; [SegmentationRunner] then falls back to the generic
 *   argmax and calls [fromClassMap].
 * - [fromClassMap] is the terminal path: the runner has computed the argmax class map itself and
 *   the implementation only has to wrap it.
 *
 * This is the seam that keeps sailens-vision generic. The runner knows there is *a* postprocessor;
 * it does not know that Guidance's implementation also computes navigation statistics
 * (architecture.md §6.5).
 */
interface SemanticPostprocessor<R> {
    /**
     * @param reusableClassMap the runner's per-frame class-map buffer, sized to
     *   `spec.content`, offered as scratch. The runner does not read it after a successful call, so
     *   an implementation may write its class map there or into storage it owns instead. Whatever
     *   it keeps must not be this array: the runner reuses it on the next frame.
     * @return null to decline this frame and let the runner fall back to generic argmax.
     */
    fun postprocessScores(
        scores: SemanticScores,
        spec: SemanticScoreSpec,
        reusableClassMap: IntArray,
    ): SemanticPostprocessOutcome<R>?

    /**
     * @param classMap the runner's buffer, already filled with argmax class ids of `spec.content`
     *   (padding cropped away). Reused on the next frame, so an implementation that keeps it must
     *   copy it.
     */
    fun fromClassMap(classMap: IntArray, spec: SemanticScoreSpec): R
}

/**
 * A class-id map for one frame: `classIds[y * width + x]` is the winning class. Covers the camera
 * frame only; letterbox padding is not part of it.
 */
class SemanticClassMap(
    val width: Int,
    val height: Int,
    val classIds: IntArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SemanticClassMap) return false
        return width == other.width && height == other.height && classIds.contentEquals(other.classIds)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + classIds.contentHashCode()
        return result
    }
}

/**
 * The default postprocessor: argmax and nothing else.
 *
 * It declines every fused path, so the runner always falls through to its generic argmax. A
 * product that only wants "which class is at this pixel" uses this and takes no dependency on any
 * navigation logic.
 *
 * It copies the class map into a fresh array on purpose. A [SemanticClassMap] is a plain value
 * handed to callers this library knows nothing about, so it must stay valid however long they keep
 * it; lending out a reused buffer would make every one of them responsible for not holding on. A
 * caller that needs to avoid the allocation owns its storage the way Guidance's postprocessor does.
 */
object ClassMapPostprocessor : SemanticPostprocessor<SemanticClassMap> {
    override fun postprocessScores(
        scores: SemanticScores,
        spec: SemanticScoreSpec,
        reusableClassMap: IntArray,
    ): SemanticPostprocessOutcome<SemanticClassMap>? = null

    override fun fromClassMap(classMap: IntArray, spec: SemanticScoreSpec): SemanticClassMap =
        SemanticClassMap(spec.content.width, spec.content.height, classMap.copyOf(spec.content.pixelCount))
}
