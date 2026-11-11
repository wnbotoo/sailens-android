package com.sailens.vision.depth

import com.sailens.runtime.ImageTensorLayout
import com.sailens.runtime.ImageTensorSpec
import com.sailens.runtime.TfliteModelMetadata
import com.sailens.runtime.TfliteTensorElementType
import com.sailens.runtime.TfliteTensorMetadata

/** The tensors a conforming depth model has, read from its metadata. */
data class DepthTensorSpec(
    val inputWidth: Int,
    val inputHeight: Int,
    val inputLayout: ImageTensorLayout,
    val outputWidth: Int,
    val outputHeight: Int,
    /** Input and output shapes, for traces: e.g. `1x3x392x224>1x392x224`. */
    val shapeLabel: String,
)

/**
 * The `depth` contract's tensor rules (docs/models.md), shared by the static preflight and the
 * runner so the two cannot disagree:
 * - exactly one input: float32, `[1,3,H,W]` or `[1,H,W,3]`, every dimension positive;
 * - exactly one output: float32 relative disparity, `[1,H,W]`, `[1,1,H,W]` or `[1,H,W,1]`;
 * - the output's spatial size equals the input's, so output pixel (x, y) is input pixel (x, y) and the
 *   input's transform maps it. Recomputing a letterbox for a different output size would misplace
 *   every value; supporting one needs its own coverage rule first.
 * Where a shape reads more than one way, the reading is the one under which input and output have the same size; an
 * input whose two layouts both fit is refused, since its layout cannot be told.
 */
object DepthTensorContract {

    sealed interface Resolution {
        data class Compatible(val spec: DepthTensorSpec) : Resolution
        data class InputUnreadable(val detail: String) : Resolution
        data class OutputUnreadable(val detail: String) : Resolution
        data class OutputSizeMismatch(val detail: String) : Resolution
    }

    fun resolve(metadata: TfliteModelMetadata): Resolution {
        val input = metadata.inputs.singleOrNull()
            ?: return Resolution.InputUnreadable("expected one input tensor, got ${metadata.inputs.describe()}")
        if (input.elementType != TfliteTensorElementType.FLOAT32) {
            return Resolution.InputUnreadable("expected a float32 input, got ${input.elementType} ${input.shape}")
        }
        val inputReadings = inputReadings(input.shape)
        if (inputReadings.isEmpty()) return Resolution.InputUnreadable("expected [1,3,H,W] or [1,H,W,3], got ${input.shape}")

        val output = metadata.outputs.singleOrNull()
            ?: return Resolution.OutputUnreadable("expected one output tensor, got ${metadata.outputs.describe()}")
        if (output.elementType != TfliteTensorElementType.FLOAT32) {
            return Resolution.OutputUnreadable("expected a float32 output, got ${output.elementType} ${output.shape}")
        }
        val outputSizes = singleChannelSizes(output.shape)
        if (outputSizes.isEmpty()) return Resolution.OutputUnreadable("expected [1,H,W], [1,1,H,W] or [1,H,W,1], got ${output.shape}")

        // A shape can read two ways ([1,3,4,3] is NCHW 4x3 or NHWC 3x4; [1,1,4,1] is 4x1 or 1x4): the reading is the one
        // whose input and output have the same spatial size. Two input layouts that both fit cannot be told apart, and
        // writing the input the wrong way would feed the model garbage, so that model is refused.
        val fitting = inputReadings.filter { spec -> (spec.height to spec.width) in outputSizes }
        val inputSpec = when (fitting.size) {
            1 -> fitting.single()
            0 -> return Resolution.OutputSizeMismatch(
                "output ${output.shape} (${outputSizes.describeSizes()}) differs from input ${input.shape} (${inputReadings.describeSpecs()})",
            )
            else -> return Resolution.InputUnreadable(
                "input ${input.shape} reads as both [1,3,H,W] and [1,H,W,3] at the output's size ${output.shape}; its layout cannot be told",
            )
        }
        return Resolution.Compatible(
            DepthTensorSpec(
                inputWidth = inputSpec.width,
                inputHeight = inputSpec.height,
                inputLayout = inputSpec.layout,
                outputWidth = inputSpec.width,
                outputHeight = inputSpec.height,
                shapeLabel = "${input.shape.joinToString("x")}>${output.shape.joinToString("x")}",
            ),
        )
    }

    /** Every way [shape] reads as a positive-sized three-channel image: `[1,H,W,3]`, `[1,3,H,W]`, or both. */
    internal fun inputReadings(shape: List<Int>): List<ImageTensorSpec> {
        if (shape.size != 4 || shape[0] != 1 || shape.any { it <= 0 }) return emptyList()
        return listOfNotNull(
            if (shape[3] == 3) ImageTensorSpec(width = shape[2], height = shape[1], channels = 3, layout = ImageTensorLayout.NHWC) else null,
            if (shape[1] == 3) ImageTensorSpec(width = shape[3], height = shape[2], channels = 3, layout = ImageTensorLayout.NCHW) else null,
        )
    }

    /**
     * Every (height, width) [shape] reads as for a single-channel map: `[1,H,W]`, `[1,1,H,W]`, `[1,H,W,1]`. With one
     * channel the two four-dimensional layouts hold the same values in the same order, so only the size matters.
     */
    internal fun singleChannelSizes(shape: List<Int>): List<Pair<Int, Int>> {
        if (shape.firstOrNull() != 1 || shape.any { it <= 0 }) return emptyList()
        return when (shape.size) {
            3 -> listOf(shape[1] to shape[2])
            4 -> listOfNotNull(
                if (shape[1] == 1) shape[2] to shape[3] else null,
                if (shape[3] == 1) shape[1] to shape[2] else null,
            ).distinct()
            else -> emptyList()
        }
    }

    private fun List<Pair<Int, Int>>.describeSizes(): String = joinToString(" or ") { (h, w) -> "${w}x$h" }

    private fun List<ImageTensorSpec>.describeSpecs(): String = joinToString(" or ") { "${it.layout} ${it.width}x${it.height}" }

    private fun List<TfliteTensorMetadata>.describe(): String = map { it.name to it.shape }.toString()
}
