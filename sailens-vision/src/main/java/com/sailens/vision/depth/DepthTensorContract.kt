package com.sailens.vision.depth

import com.sailens.runtime.ImageTensorLayout
import com.sailens.runtime.TfliteModelMetadata
import com.sailens.runtime.TfliteTensorElementType
import com.sailens.runtime.TfliteTensorMetadata
import com.sailens.runtime.imageTensorSpecOrNull

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
        val inputSpec = imageTensorSpecOrNull(input.shape, expectedChannels = 3)
            ?.takeIf { it.width > 0 && it.height > 0 }
            ?: return Resolution.InputUnreadable("expected [1,3,H,W] or [1,H,W,3], got ${input.shape}")

        val output = metadata.outputs.singleOrNull()
            ?: return Resolution.OutputUnreadable("expected one output tensor, got ${metadata.outputs.describe()}")
        if (output.elementType != TfliteTensorElementType.FLOAT32) {
            return Resolution.OutputUnreadable("expected a float32 output, got ${output.elementType} ${output.shape}")
        }
        val (outputHeight, outputWidth) = singleChannelSize(output.shape)
            ?: return Resolution.OutputUnreadable("expected [1,H,W], [1,1,H,W] or [1,H,W,1], got ${output.shape}")
        if (outputWidth != inputSpec.width || outputHeight != inputSpec.height) {
            return Resolution.OutputSizeMismatch(
                "output ${outputWidth}x$outputHeight differs from input ${inputSpec.width}x${inputSpec.height}"
            )
        }
        return Resolution.Compatible(
            DepthTensorSpec(
                inputWidth = inputSpec.width,
                inputHeight = inputSpec.height,
                inputLayout = inputSpec.layout,
                outputWidth = outputWidth,
                outputHeight = outputHeight,
                shapeLabel = "${input.shape.joinToString("x")}>${output.shape.joinToString("x")}",
            ),
        )
    }

    /** (height, width) of a single-channel map, or null when the shape is not one. */
    internal fun singleChannelSize(shape: List<Int>): Pair<Int, Int>? {
        if (shape.firstOrNull() != 1 || shape.any { it <= 0 }) return null
        return when {
            shape.size == 3 -> shape[1] to shape[2]
            shape.size == 4 && shape[1] == 1 -> shape[2] to shape[3]
            shape.size == 4 && shape[3] == 1 -> shape[1] to shape[2]
            else -> null
        }
    }

    private fun List<TfliteTensorMetadata>.describe(): String = map { it.name to it.shape }.toString()
}
