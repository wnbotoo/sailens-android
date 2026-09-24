package com.sailens.vision.detection

/**
 * The unit a detection head reports its boxes in. Part of the model contract, like the class order:
 * it is declared, never inferred from the output. A tiny box in the top-left corner of a pixel-space
 * head (x1 = 0.5, y2 = 1.5) is numerically the same as a normalised box, so no rule over the values
 * can tell the two apart — and guessing wrong moves every box the user is told about.
 */
enum class DetectionCoordinateSpace {
    /** Boxes in [0, 1] of the model input (Ultralytics TFLite exports, the bundled detector). */
    NORMALIZED,

    /** Boxes in model-input pixels, e.g. 0..640 for a 640x640 input. */
    MODEL_PIXELS,
    ;

    /** The factor that takes a box value to model-input pixels. */
    fun modelPixelScale(inputSize: Int): Float = when (this) {
        NORMALIZED -> inputSize.toFloat()
        MODEL_PIXELS -> 1f
    }
}
