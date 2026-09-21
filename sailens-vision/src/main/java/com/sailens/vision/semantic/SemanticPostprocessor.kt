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

/** Geometry of the score tensor a [SemanticPostprocessor] is being asked to read. */
data class SemanticScoreSpec(
    val width: Int,
    val height: Int,
    val channels: Int,
    val layout: ImageTensorLayout,
)

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
     * @param reusableClassMap the runner's per-frame class-map buffer, sized `width * height`.
     *   An implementation that succeeds must leave the argmax class ids in it, and must copy the
     *   array if its result outlives the call — the runner reuses it on the next frame.
     * @return null to decline this frame and let the runner fall back to generic argmax.
     */
    fun postprocessScores(
        scores: SemanticScores,
        spec: SemanticScoreSpec,
        reusableClassMap: IntArray,
    ): SemanticPostprocessOutcome<R>?

    /**
     * @param classMap the runner's buffer, already filled with argmax class ids. Reused on the
     *   next frame, so an implementation that keeps it must copy it.
     */
    fun fromClassMap(classMap: IntArray, spec: SemanticScoreSpec): R
}

/** A class-id map for one frame: `classIds[y * width + x]` is the winning class. */
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
 */
object ClassMapPostprocessor : SemanticPostprocessor<SemanticClassMap> {
    override fun postprocessScores(
        scores: SemanticScores,
        spec: SemanticScoreSpec,
        reusableClassMap: IntArray,
    ): SemanticPostprocessOutcome<SemanticClassMap>? = null

    override fun fromClassMap(classMap: IntArray, spec: SemanticScoreSpec): SemanticClassMap =
        SemanticClassMap(spec.width, spec.height, classMap.clone())
}
