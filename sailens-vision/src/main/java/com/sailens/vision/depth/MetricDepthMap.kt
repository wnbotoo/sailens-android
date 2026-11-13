package com.sailens.vision.depth

/**
 * Depth in metres along the optical axis (Z, not the length along the ray), upright, row-major [height] × [width], as
 * a metric source measured it: an AR depth API, a time-of-flight sensor. Its adapter turns the source's own format into
 * this -- units, the ray length into Z, the turn to upright -- and builds [transform] from the mapping the source reports
 * (`DepthSourceMapping`).
 *
 * A pixel is unknown when its depth is not finite or not positive, or its [confidence] is below [minConfidence]: the
 * source did not measure it, as padding is unseen. A pixel that lies between two jumps in depth of more than
 * [MIXED_EDGE_RATIO], one on each side along its row or its column, is unknown too: a sensor's mixed pixel at an
 * object's edge, the mean of the surfaces either side, a depth nothing is at. Unknown is not failure. Sampling never
 * interpolates across it: a point whose interpolation would read an unknown pixel is unknown itself, so no depth is
 * made up at the edge of a hole.
 *
 * @param confidence 0 to 1 per pixel; null when the source gives none.
 * @param minConfidence below this a pixel is unknown; the host's run choice.
 */
class MetricDepthMap(
    override val width: Int,
    override val height: Int,
    val depthM: FloatArray,
    val confidence: FloatArray?,
    val minConfidence: Float,
    override val transform: DepthInputTransform,
    override val times: DepthTimes,
) : DepthMap {
    init {
        require(depthM.size == width * height) { "expected ${width * height} depths, got ${depthM.size}" }
        require(confidence == null || confidence.size == depthM.size) { "confidence and depth disagree on the size" }
        require(width == transform.modelWidth && height == transform.modelHeight) { "map and transform disagree on the size" }
    }

    override val kind: DepthKind get() = DepthKind.METRIC_DEPTH

    private val measured = BooleanArray(depthM.size) { i ->
        val z = depthM[i]
        z.isFinite() && z > 0f && (confidence == null || confidence[i] >= minConfidence)
    }

    private val known = BooleanArray(depthM.size) { i -> measured[i] && !mixedEdge(i) }

    /** Between a jump of more than [MIXED_EDGE_RATIO] to the pixel before it and one to the pixel after, in a row or a column. */
    private fun mixedEdge(i: Int): Boolean {
        val x = i % width
        val y = i / width
        fun between(before: Int, after: Int): Boolean {
            if (!measured[before] || !measured[after]) return false
            val z = depthM[i]
            val a = depthM[before]
            val b = depthM[after]
            if (z <= minOf(a, b) || z >= maxOf(a, b)) return false
            return maxOf(z, a) / minOf(z, a) > MIXED_EDGE_RATIO && maxOf(z, b) / minOf(z, b) > MIXED_EDGE_RATIO
        }
        return (x in 1 until width - 1 && between(i - 1, i + 1)) || (y in 1 until height - 1 && between(i - width, i + width))
    }

    /** The share of the map's pixels that are unknown. */
    val unknownFraction: Float = if (known.isEmpty()) 1f else known.count { !it }.toFloat() / known.size

    /** `1/Z` at (u, v), bilinear between the pixel centres of four known pixels; null where any of them is unknown. */
    override fun sampleAt(u: Float, v: Float): Float? = sampleOrNaN(u, v).takeIf { !it.isNaN() }

    override fun sampleOrNaN(u: Float, v: Float): Float = interpolate(u, v, inverse = true)

    /** Z, metres, at (u, v), by the same rule as [sampleAt]: bilinear between four known pixels, else null. */
    fun depthAt(u: Float, v: Float): Float? = interpolate(u, v, inverse = false).takeIf { !it.isNaN() }

    override fun isKnownAt(u: Float, v: Float): Boolean {
        if (!transform.covers(u, v)) return false
        val c = corners(u, v)
        return known[c.i00] && known[c.i01] && known[c.i10] && known[c.i11]
    }

    private class Corners(val i00: Int, val i01: Int, val i10: Int, val i11: Int, val fx: Float, val fy: Float)

    private fun corners(u: Float, v: Float): Corners {
        val cx = (transform.toTensorX(u) - 0.5f).coerceIn(0f, (width - 1).toFloat())
        val cy = (transform.toTensorY(v) - 0.5f).coerceIn(0f, (height - 1).toFloat())
        val x0 = cx.toInt()
        val y0 = cy.toInt()
        val x1 = minOf(x0 + 1, width - 1)
        val y1 = minOf(y0 + 1, height - 1)
        return Corners(y0 * width + x0, y0 * width + x1, y1 * width + x0, y1 * width + x1, cx - x0, cy - y0)
    }

    private fun interpolate(u: Float, v: Float, inverse: Boolean): Float {
        if (!transform.covers(u, v)) return Float.NaN
        val c = corners(u, v)
        if (!(known[c.i00] && known[c.i01] && known[c.i10] && known[c.i11])) return Float.NaN
        fun value(i: Int) = if (inverse) 1f / depthM[i] else depthM[i]
        val top = value(c.i00) * (1 - c.fx) + value(c.i01) * c.fx
        val bottom = value(c.i10) * (1 - c.fx) + value(c.i11) * c.fx
        return top * (1 - c.fy) + bottom * c.fy
    }

    companion object {
        /**
         * A jump in depth of more than this ratio (10 %) is an edge between surfaces. Neighbouring pixels on ground
         * differ by less out to a few tens of metres at the depth images' resolutions here.
         */
        const val MIXED_EDGE_RATIO: Float = 1.1f
    }
}

/**
 * Where a source's depth image lies on the upright analysis image, from the mapping the source itself reports -- its
 * depth image and the camera image need not share an aspect, and the depth image can be a crop of the camera's. The
 * source reports, in its own buffer orientation, which camera-buffer point each depth-buffer point is: an affine map of
 * continuous pixel coordinates (pixel i spans [i, i + 1)). Turned upright with the frame, that must be a scale per axis
 * and an offset; anything else (a rotation between the two images, a shear, a mirror) is refused, not approximated.
 */
object DepthSourceMapping {

    sealed interface Result {
        /** The depth image turned upright ([uprightWidth] × [uprightHeight]) and its transform onto the analysis image. */
        data class Mapped(val uprightWidth: Int, val uprightHeight: Int, val transform: DepthInputTransform) : Result

        /** The mapping is not a scale per axis and an offset once upright, or maps nothing onto the image. */
        data class Refused(val reason: String) : Result
    }

    /**
     * @param depthWidth the depth image in the source's buffer orientation.
     * @param bufferWidth the camera buffer, in the same orientation.
     * @param rotationDegrees the frame's turn to upright (0, 90, 180, 270), applied to both images.
     * @param toBuffer `[a, b, c, d, e, f]`: camera-buffer `X = a·x + b·y + c`, `Y = d·x + e·y + f` for depth-buffer (x, y).
     */
    fun map(
        depthWidth: Int,
        depthHeight: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        rotationDegrees: Int,
        toBuffer: FloatArray,
        tolerance: Float = 1e-4f,
    ): Result {
        require(toBuffer.size == 6) { "an affine map has six coefficients" }
        val turn = rotationDegrees.mod(360)
        if (turn % 90 != 0) return Result.Refused("rotation $rotationDegrees is not a quarter turn")
        val turned = turn == 90 || turn == 270
        val uprightW = if (turned) depthHeight else depthWidth
        val uprightH = if (turned) depthWidth else depthHeight
        val analysisW = if (turned) bufferHeight else bufferWidth
        val analysisH = if (turned) bufferWidth else bufferHeight
        // Upright depth point (x', y') -> depth buffer (x, y) -> camera buffer (X, Y) -> upright analysis (u, v).
        val toDepthBuffer = upright(turn, depthWidth.toDouble(), depthHeight.toDouble()).inverse()
        val toAnalysis = upright(turn, bufferWidth.toDouble(), bufferHeight.toDouble())
        val source = Affine(toBuffer[0].toDouble(), toBuffer[1].toDouble(), toBuffer[2].toDouble(), toBuffer[3].toDouble(), toBuffer[4].toDouble(), toBuffer[5].toDouble())
        val m = toAnalysis.after(source.after(toDepthBuffer))
        val scale = maxOf(kotlin.math.abs(m.a), kotlin.math.abs(m.e), 1e-12)
        if (kotlin.math.abs(m.b) > tolerance * scale || kotlin.math.abs(m.d) > tolerance * scale) {
            return Result.Refused("mapping not a scale per axis and an offset once upright")
        }
        if (m.a <= 0 || m.e <= 0) return Result.Refused("mapping mirrors or collapses an axis")
        // u = a·x' + c, v = e·y' + f: the depth image spans [c, c + a·W'] across the analysis image.
        val left = m.c
        val top = m.f
        val right = m.c + m.a * uprightW
        val bottom = m.f + m.e * uprightH
        val sourceLeft = maxOf(0.0, left)
        val sourceTop = maxOf(0.0, top)
        val sourceRight = minOf(analysisW.toDouble(), right)
        val sourceBottom = minOf(analysisH.toDouble(), bottom)
        if (sourceRight <= sourceLeft || sourceBottom <= sourceTop) return Result.Refused("the depth image does not overlap the camera image")
        // Analysis -> depth pixels: x' = (u − sourceLeft)·scaleX + padLeft, padLeft the depth pixels left of the image.
        val scaleX = 1.0 / m.a
        val scaleY = 1.0 / m.e
        return Result.Mapped(
            uprightW,
            uprightH,
            DepthInputTransform(
                analysisWidth = analysisW,
                analysisHeight = analysisH,
                modelWidth = uprightW,
                modelHeight = uprightH,
                policy = ResizePolicy.SOURCE_MAPPED,
                sourceLeft = sourceLeft.toFloat(),
                sourceTop = sourceTop.toFloat(),
                sourceWidth = (sourceRight - sourceLeft).toFloat(),
                sourceHeight = (sourceBottom - sourceTop).toFloat(),
                scaleX = scaleX.toFloat(),
                scaleY = scaleY.toFloat(),
                padLeft = ((sourceLeft - left) * scaleX).toFloat(),
                padTop = ((sourceTop - top) * scaleY).toFloat(),
            ),
        )
    }

    /**
     * [values] (row-major [width] × [height], in buffer orientation) turned upright by [rotationDegrees], the way the
     * frame is: 90 turns the buffer clockwise.
     */
    fun upright(values: FloatArray, width: Int, height: Int, rotationDegrees: Int): FloatArray {
        val turn = rotationDegrees.mod(360)
        if (turn == 0) return values.copyOf()
        val turned = turn == 90 || turn == 270
        val w = if (turned) height else width
        val out = FloatArray(values.size)
        for (y in 0 until height) for (x in 0 until width) {
            val (ux, uy) = when (turn) {
                90 -> (height - 1 - y) to x
                180 -> (width - 1 - x) to (height - 1 - y)
                else -> y to (width - 1 - x)
            }
            out[uy * w + ux] = values[y * width + x]
        }
        return out
    }

    /** `X = a·x + b·y + c`, `Y = d·x + e·y + f`. */
    private data class Affine(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
        /** This applied after [first]: `this ∘ first`. */
        fun after(first: Affine): Affine {
            return Affine(
                a * first.a + b * first.d, a * first.b + b * first.e, a * first.c + b * first.f + c,
                d * first.a + e * first.d, d * first.b + e * first.e, d * first.c + e * first.f + f,
            )
        }

        fun inverse(): Affine {
            val det = a * e - b * d
            return Affine(e / det, -b / det, (b * f - e * c) / det, -d / det, a / det, (d * c - a * f) / det)
        }
    }

    /** Buffer point -> upright point for an image [w] × [h] turned by [turn], continuous coordinates. */
    private fun upright(turn: Int, w: Double, h: Double): Affine = when (turn) {
        0 -> Affine(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)
        90 -> Affine(0.0, -1.0, h, 1.0, 0.0, 0.0)
        180 -> Affine(-1.0, 0.0, w, 0.0, -1.0, h)
        else -> Affine(0.0, 1.0, 0.0, -1.0, 0.0, w)
    }
}
