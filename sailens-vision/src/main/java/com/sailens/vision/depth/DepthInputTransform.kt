package com.sailens.vision.depth

import kotlin.math.ceil

/**
 * How a depth model's input was made from the upright analysis image (docs/geometry.md §6). It
 * cannot be read from the TFLite metadata. The runtime always uses [PAD_KEEP_ASPECT], the only
 * resize the native preprocessing does (docs/models.md, "The `depth` contract"); the other two
 * describe inputs made elsewhere, such as offline experiments.
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
 * the transform that produced its input and the frame's [times] (none known when not given).
 */
class DisparityMap(
    override val width: Int,
    override val height: Int,
    val values: FloatArray,
    override val transform: DepthInputTransform,
    override val times: DepthTimes = DepthTimes.UNKNOWN,
) : DepthMap {
    override val kind: DepthKind get() = DepthKind.RELATIVE_DISPARITY

    init {
        require(values.size == width * height) { "expected ${width * height} values, got ${values.size}" }
        require(width == transform.modelWidth && height == transform.modelHeight) { "map and transform disagree on the tensor size" }
    }

    // The content spans [pad, end) in tensor pixels. A pixel is content only when both hold:
    //  - the native letterbox wrote image data there: it writes columns [ceil(pad), ceil(end)), so a
    //    pixel whose left edge is in the padding is padding even when its centre is not
    //    (half-pixel padding: 640x1280 into 518x518 pads 129.5, and column 129 is written as padding);
    //  - its centre lies strictly inside the span: the last column the kernel writes can have its
    //    centre at or past `end`, and holds the image edge clamped, which the model never saw.
    // Rows likewise. With no padding (crop, stretch) this is every pixel.
    private val firstContentX = ceil(transform.padLeft).toInt().coerceIn(0, width - 1)
    private val lastContentX = (ceil(contentEnd(transform.padLeft, transform.sourceWidth, transform.scaleX) - 0.5f).toInt() - 1)
        .coerceIn(firstContentX, width - 1)
    private val firstContentY = ceil(transform.padTop).toInt().coerceIn(0, height - 1)
    private val lastContentY = (ceil(contentEnd(transform.padTop, transform.sourceHeight, transform.scaleY) - 0.5f).toInt() - 1)
        .coerceIn(firstContentY, height - 1)

    private fun contentEnd(pad: Float, sourceExtent: Float, scale: Float): Float = pad + sourceExtent * scale

    /** The tensor columns and rows holding what the model saw (the rule above). */
    val contentColumns: IntRange get() = firstContentX..lastContentX
    val contentRows: IntRange get() = firstContentY..lastContentY

    /**
     * The disparity seen at analysis point (u, v), bilinear between tensor pixel centres; null when
     * the model did not see the point (§9: unknown, never extrapolated) or it falls in padding.
     */
    override fun sampleAt(u: Float, v: Float): Float? = if (transform.covers(u, v)) interpolate(u, v) else null

    /**
     * [sampleAt] without the box: `NaN` where the model did not see the point. A value the model wrote as `NaN` reads
     * the same, so a caller that tells the two apart uses [sampleAt]. For loops that sample thousands of points.
     */
    override fun sampleOrNaN(u: Float, v: Float): Float = if (transform.covers(u, v)) interpolate(u, v) else Float.NaN

    /** Seen by the model, and the four values [sampleAt] would interpolate there are finite. */
    override fun isKnownAt(u: Float, v: Float): Boolean {
        if (!transform.covers(u, v)) return false
        val cx = (transform.toTensorX(u) - 0.5f).coerceIn(firstContentX.toFloat(), lastContentX.toFloat())
        val cy = (transform.toTensorY(v) - 0.5f).coerceIn(firstContentY.toFloat(), lastContentY.toFloat())
        val x0 = cx.toInt()
        val y0 = cy.toInt()
        val x1 = minOf(x0 + 1, lastContentX)
        val y1 = minOf(y0 + 1, lastContentY)
        return values[y0 * width + x0].isFinite() && values[y0 * width + x1].isFinite() &&
            values[y1 * width + x0].isFinite() && values[y1 * width + x1].isFinite()
    }

    private fun interpolate(u: Float, v: Float): Float {
        // Pixel-centre coordinates, kept between the first and last pixels whose centres lie in the
        // content, so both interpolation neighbours are content even when the padding is fractional.
        val cx = (transform.toTensorX(u) - 0.5f).coerceIn(firstContentX.toFloat(), lastContentX.toFloat())
        val cy = (transform.toTensorY(v) - 0.5f).coerceIn(firstContentY.toFloat(), lastContentY.toFloat())
        val x0 = cx.toInt()
        val y0 = cy.toInt()
        val x1 = minOf(x0 + 1, lastContentX)
        val y1 = minOf(y0 + 1, lastContentY)
        val fx = cx - x0
        val fy = cy - y0
        val top = values[y0 * width + x0] * (1 - fx) + values[y0 * width + x1] * fx
        val bottom = values[y1 * width + x0] * (1 - fx) + values[y1 * width + x1] * fx
        return top * (1 - fy) + bottom * fy
    }
}
