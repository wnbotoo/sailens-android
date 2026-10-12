package com.sailens.vision.depth

/**
 * How a depth model's input was made from the upright analysis image. Part of the model's
 * configured contract (local-navigation-implementation §9): it cannot be read from the TFLite
 * metadata, and it must match how the export was validated.
 */
enum class ResizePolicy {
    /** Resize the whole image to the tensor, aspect not kept (scaleX != scaleY). */
    STRETCH,

    /** Centre-crop to the tensor's aspect, then resize. The cropped-away sides are unseen. */
    CROP_KEEP_ASPECT,

    /** Resize to fit inside the tensor, aspect kept, and pad the rest. */
    PAD_KEEP_ASPECT,
}

/**
 * The geometry between the upright analysis image and a depth model's tensor. All ground geometry
 * works in analysis-image pixels and only reads the tensor through [toTensor]; nothing works in
 * tensor pixels. Continuous pixel coordinates on both sides: pixel i spans [i, i+1).
 *
 * @param sourceLeft the region of the analysis image fed to the model (all of it unless cropped).
 * @param scaleX analysis pixels → tensor pixels, after cropping to the source region.
 * @param padLeft tensor pixels of padding before the content (PAD only).
 */
data class DepthInputTransform(
    val analysisWidth: Int,
    val analysisHeight: Int,
    val modelWidth: Int,
    val modelHeight: Int,
    val policy: ResizePolicy,
    val sourceLeft: Float,
    val sourceTop: Float,
    val sourceWidth: Float,
    val sourceHeight: Float,
    val scaleX: Float,
    val scaleY: Float,
    val padLeft: Float,
    val padTop: Float,
) {
    /** Analysis-image point → tensor point. */
    fun toTensorX(u: Float): Float = (u - sourceLeft) * scaleX + padLeft
    fun toTensorY(v: Float): Float = (v - sourceTop) * scaleY + padTop

    /** Tensor point → analysis-image point. */
    fun toAnalysisX(x: Float): Float = (x - padLeft) / scaleX + sourceLeft
    fun toAnalysisY(y: Float): Float = (y - padTop) / scaleY + sourceTop

    /**
     * Whether the model saw analysis point (u, v). False outside the image and in a cropped-away
     * part: a consumer treats such a point as unknown, never as ground or obstacle.
     */
    fun covers(u: Float, v: Float): Boolean =
        u >= sourceLeft && u < sourceLeft + sourceWidth && v >= sourceTop && v < sourceTop + sourceHeight

    companion object {
        fun create(analysisWidth: Int, analysisHeight: Int, modelWidth: Int, modelHeight: Int, policy: ResizePolicy): DepthInputTransform {
            require(analysisWidth > 0 && analysisHeight > 0 && modelWidth > 0 && modelHeight > 0) { "empty image or tensor" }
            val aw = analysisWidth.toFloat()
            val ah = analysisHeight.toFloat()
            val mw = modelWidth.toFloat()
            val mh = modelHeight.toFloat()
            return when (policy) {
                ResizePolicy.STRETCH -> DepthInputTransform(
                    analysisWidth, analysisHeight, modelWidth, modelHeight, policy,
                    0f, 0f, aw, ah, mw / aw, mh / ah, 0f, 0f,
                )
                ResizePolicy.CROP_KEEP_ASPECT -> {
                    val scale = maxOf(mw / aw, mh / ah)
                    val w = mw / scale
                    val h = mh / scale
                    DepthInputTransform(
                        analysisWidth, analysisHeight, modelWidth, modelHeight, policy,
                        (aw - w) / 2, (ah - h) / 2, w, h, scale, scale, 0f, 0f,
                    )
                }
                ResizePolicy.PAD_KEEP_ASPECT -> {
                    val scale = minOf(mw / aw, mh / ah)
                    DepthInputTransform(
                        analysisWidth, analysisHeight, modelWidth, modelHeight, policy,
                        0f, 0f, aw, ah, scale, scale, (mw - aw * scale) / 2, (mh - ah * scale) / 2,
                    )
                }
            }
        }
    }
}

/**
 * One depth model output: relative disparity (larger = nearer), row-major [height] × [width], with
 * the transform that produced its input.
 */
class DisparityMap(val width: Int, val height: Int, val values: FloatArray, val transform: DepthInputTransform) {
    init {
        require(values.size == width * height) { "expected ${width * height} values, got ${values.size}" }
        require(width == transform.modelWidth && height == transform.modelHeight) { "map and transform disagree on the tensor size" }
    }

    /**
     * The disparity seen at analysis point (u, v), bilinear between tensor pixel centres; null when
     * the model did not see the point (§9: unknown, never extrapolated) or it falls in padding.
     */
    fun sampleAt(u: Float, v: Float): Float? {
        if (!transform.covers(u, v)) return null
        // Pixel-centre coordinates, kept on the content's centres so padding never blends in.
        val cx = (transform.toTensorX(u) - 0.5f).coerceIn(maxOf(0f, transform.padLeft), minOf(width - 1f, width - 1f - transform.padLeft))
        val cy = (transform.toTensorY(v) - 0.5f).coerceIn(maxOf(0f, transform.padTop), minOf(height - 1f, height - 1f - transform.padTop))
        val x0 = cx.toInt()
        val y0 = cy.toInt()
        val x1 = minOf(x0 + 1, width - 1)
        val y1 = minOf(y0 + 1, height - 1)
        val fx = cx - x0
        val fy = cy - y0
        val top = values[y0 * width + x0] * (1 - fx) + values[y0 * width + x1] * fx
        val bottom = values[y1 * width + x0] * (1 - fx) + values[y1 * width + x1] * fx
        return top * (1 - fy) + bottom * fy
    }
}
