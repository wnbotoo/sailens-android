package com.sailens.guidance.geometry

import com.sailens.core.mask.BinaryMask
import com.sailens.vision.depth.DisparityMap
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt
import kotlin.random.Random

/** Why a ground fit is or is not usable. */
enum class GroundFitValidity {
    VALID,

    /** Too little of the image below the horizon was seen to fit anything. */
    TOO_FEW_SAMPLES,

    /** The samples cover too narrow a band of the ground to pin a plane (camera pointing straight down). */
    DEGENERATE_VIEW,

    /** No line explains enough of the samples: no visible ground. */
    NO_GROUND,

    /**
     * Disparity falls toward the bottom of the image: the model outputs depth, not disparity (§9).
     * Static preflight cannot see this; the output is refused rather than read upside down.
     */
    INVERSE_OUTPUT,
}

/**
 * The ground seen in one depth frame (§3, M3b), in analysis-image coordinates on a grid of the
 * caller's choosing (the class map's, so it composes with sem). Cells the depth model did not see
 * are in no mask: unknown, never ground.
 *
 * Fitted model: `disparity = A·q + t`, with `q = g·r` for the pixel's ray r (z = 1) and g the unit
 * down vector. On ground at camera height h, `A = a/h` and `t = b` for a model whose disparity is
 * `a/Z + b`, which is what relative-depth models output (scale and shift unknown, per frame).
 */
data class GroundObservation(
    val frameTimeNanos: Long?,
    val validity: GroundFitValidity,
    val groundMask: BinaryMask,
    val aboveGroundMask: BinaryMask,
    val belowGroundMask: BinaryMask,
    val disparityScale: Float,
    val disparityShift: Float,
    /** Fraction of below-horizon samples on the fitted ground. */
    val inlierRatio: Float,
    /** A free plane fit on the inliers leans the way gravity says the ground does. */
    val planeAgreesWithGravity: Boolean,
    val confidence: Float,
) {
    val isValid: Boolean get() = validity == GroundFitValidity.VALID

    /**
     * Height above the fitted ground of a point seen at [disparity] along a ray with [q] = g·r, for
     * a camera [phoneHeightM] above the ground: `h − Z·q` with `Z = A·h / (d − t)`. Negative is
     * below ground (a step down, a drop). Null when the fit is not valid or the point is at or
     * beyond infinity.
     */
    fun heightAboveGroundM(disparity: Float, q: Float, phoneHeightM: Float): Float? {
        if (!isValid || disparity <= disparityShift) return null
        val z = disparityScale * phoneHeightM / (disparity - disparityShift)
        return phoneHeightM - z * q
    }
}

/**
 * Gravity-constrained ground fit on a disparity map (§9): sample below the horizon, RANSAC the
 * line `d = A·q + t` with a relative inlier threshold, refit on the inliers, then label a grid by
 * signed relative residual. Deterministic (seeded). Not thread-safe: one per pipeline; it keeps the
 * last few results to rate stability. [reset] between sessions.
 *
 * @param inlierThreshold relative residual for ground (4 %).
 * @param structureFactor residual beyond this many thresholds is above / below ground (3×).
 * @param minQ samples closer to the horizon than this (radians, roughly) are not used: their
 *   ground prediction is too flat to tell ground from far structure.
 * @param minInlierRatio below this, there is no ground in view.
 * @param sampleFromRow the fit samples only the image below this fraction of its height (§9: "the
 *   lower part"). Near ground dominates there; far ground is where a small step or a gentle slope
 *   differs from the plane by less than the threshold, and would bend the fit if it were sampled.
 *   The labels still cover the whole image.
 */
class GroundFitter(
    private val inlierThreshold: Float = 0.04f,
    private val structureFactor: Float = 3f,
    private val minQ: Float = 0.03f,
    private val minInlierRatio: Float = 0.2f,
    private val sampleFromRow: Float = 0.4f,
    private val sampleColumns: Int = 64,
    private val sampleRows: Int = 96,
    private val iterations: Int = 200,
    private val maxGravityAngleDeg: Float = 10f,
    private val stabilityFrames: Int = 3,
) {
    private val recentCoverage = ArrayDeque<Float>()

    fun reset() = recentCoverage.clear()

    /**
     * @param intrinsics of the analysis image the depth input was made from (same size as
     *   [disparity]'s transform's analysis size).
     * @param gravityDown unit down vector in the camera frame.
     */
    fun fit(
        disparity: DisparityMap,
        intrinsics: CameraIntrinsics,
        gravityDown: Vec3,
        gridWidth: Int,
        gridHeight: Int,
        frameTimeNanos: Long? = null,
    ): GroundObservation {
        val transform = disparity.transform
        require(intrinsics.width == transform.analysisWidth && intrinsics.height == transform.analysisHeight) {
            "intrinsics are for ${intrinsics.width}x${intrinsics.height}, depth input was made from ${transform.analysisWidth}x${transform.analysisHeight}"
        }
        val g = gravityDown.normalized()
        fun q(u: Float, v: Float) = g.x * (u - intrinsics.cx) / intrinsics.fx + g.y * (v - intrinsics.cy) / intrinsics.fy + g.z

        // 1. Samples below the horizon, on a coarse grid of the lower part of the analysis image.
        val qs = FloatArray(sampleColumns * sampleRows)
        val ds = FloatArray(sampleColumns * sampleRows)
        val xs = FloatArray(sampleColumns * sampleRows)
        val ys = FloatArray(sampleColumns * sampleRows)
        var n = 0
        val top = sampleFromRow * transform.analysisHeight
        for (row in 0 until sampleRows) {
            val v = top + (row + 0.5f) * (transform.analysisHeight - top) / sampleRows
            for (col in 0 until sampleColumns) {
                val u = (col + 0.5f) * transform.analysisWidth / sampleColumns
                val qv = q(u, v)
                if (qv < minQ) continue
                val d = disparity.sampleAt(u, v) ?: continue
                if (!d.isFinite()) continue
                qs[n] = qv; ds[n] = d
                xs[n] = (u - intrinsics.cx) / intrinsics.fx; ys[n] = (v - intrinsics.cy) / intrinsics.fy
                n++
            }
        }
        val empty = { validity: GroundFitValidity -> invalid(validity, gridWidth, gridHeight, frameTimeNanos) }
        if (n < MIN_SAMPLES) return empty(GroundFitValidity.TOO_FEW_SAMPLES)
        var qMin = Float.MAX_VALUE
        var qMax = -Float.MAX_VALUE
        for (i in 0 until n) { qMin = minOf(qMin, qs[i]); qMax = maxOf(qMax, qs[i]) }
        if (qMax - qMin < MIN_Q_SPAN) return empty(GroundFitValidity.DEGENERATE_VIEW)

        // 2. RANSAC over both slopes: the better negative one means inverse output.
        val random = Random(SEED)
        var bestPositive = Line(0f, 0f, 0)
        var bestNegative = Line(0f, 0f, 0)
        repeat(iterations) {
            val i = random.nextInt(n)
            val j = random.nextInt(n)
            val dq = qs[j] - qs[i]
            if (abs(dq) < MIN_PAIR_Q_SPAN) return@repeat
            val a = (ds[j] - ds[i]) / dq
            val t = ds[i] - a * qs[i]
            val count = countInliers(a, t, qs, ds, n)
            if (a > 0 && count > bestPositive.inliers) bestPositive = Line(a, t, count)
            if (a < 0 && count > bestNegative.inliers) bestNegative = Line(a, t, count)
        }
        if (bestNegative.inliers > bestPositive.inliers && bestNegative.inliers >= minInlierRatio * n) {
            return empty(GroundFitValidity.INVERSE_OUTPUT)
        }
        if (bestPositive.inliers < minInlierRatio * n) return empty(GroundFitValidity.NO_GROUND)

        // 3. Least squares on the inliers, then the inliers of the refit.
        val refit = refit(bestPositive, qs, ds, n) ?: return empty(GroundFitValidity.NO_GROUND)
        val inlier = BooleanArray(n) { isInlier(refit.a, refit.t, qs[it], ds[it]) }
        val inlierCount = inlier.count { it }
        val inlierRatio = inlierCount.toFloat() / n
        if (refit.a <= 0f || inlierRatio < minInlierRatio) return empty(GroundFitValidity.NO_GROUND)
        // Ground recedes: across the inliers, the near end must be clearly nearer than the far end.
        // A surface at one distance -- a wall filling the view -- fits a flat line just as well.
        var inQMin = Float.MAX_VALUE
        var inQMax = -Float.MAX_VALUE
        for (i in 0 until n) if (inlier[i]) { inQMin = minOf(inQMin, qs[i]); inQMax = maxOf(inQMax, qs[i]) }
        val far = refit.a * inQMin + refit.t
        if (far <= 0f || (refit.a * inQMax + refit.t) / far < 1f + MIN_RELATIVE_RISE) return empty(GroundFitValidity.NO_GROUND)

        // 6a. Agreement: a free plane d = k1·x + k2·y + k0 on the inliers should lean as gravity says.
        val agrees = freePlaneAgrees(refit.a, g, xs, ys, ds, inlier, n)

        // 3–4. Label the grid.
        val ground = BinaryMask(gridWidth, gridHeight)
        val above = BinaryMask(gridWidth, gridHeight)
        val below = BinaryMask(gridWidth, gridHeight)
        for (gy in 0 until gridHeight) {
            val v = (gy + 0.5f) * transform.analysisHeight / gridHeight
            for (gx in 0 until gridWidth) {
                val u = (gx + 0.5f) * transform.analysisWidth / gridWidth
                val d = disparity.sampleAt(u, v) ?: continue
                val qv = q(u, v)
                val predicted = refit.a * qv + refit.t
                if (predicted <= 0f) continue
                val residual = (d - predicted) / predicted
                when {
                    qv >= minQ && abs(residual) <= inlierThreshold -> ground.set(gx, gy, true)
                    residual > inlierThreshold * structureFactor -> above.set(gx, gy, true)
                    qv >= minQ && residual < -inlierThreshold * structureFactor -> below.set(gx, gy, true)
                }
            }
        }

        // 6b. Confidence: enough ground, a plane that agrees with gravity, and a steady result.
        val coverage = ground.coverage()
        recentCoverage.addLast(coverage)
        while (recentCoverage.size > stabilityFrames) recentCoverage.removeFirst()
        val confidence = ((inlierRatio - minInlierRatio) / (GOOD_INLIER_RATIO - minInlierRatio)).coerceIn(0f, 1f) *
            (if (agrees) 1f else 0.5f) * stability()

        return GroundObservation(
            frameTimeNanos, GroundFitValidity.VALID, ground, above, below,
            refit.a, refit.t, inlierRatio, agrees, confidence,
        )
    }

    private data class Line(val a: Float, val t: Float, val inliers: Int)

    private fun isInlier(a: Float, t: Float, q: Float, d: Float): Boolean {
        val predicted = a * q + t
        return predicted > 0f && abs(d - predicted) <= inlierThreshold * predicted
    }

    private fun countInliers(a: Float, t: Float, qs: FloatArray, ds: FloatArray, n: Int): Int {
        var count = 0
        for (i in 0 until n) if (isInlier(a, t, qs[i], ds[i])) count++
        return count
    }

    private fun refit(line: Line, qs: FloatArray, ds: FloatArray, n: Int): Line? {
        var sq = 0.0; var sd = 0.0; var sqq = 0.0; var sqd = 0.0; var m = 0
        for (i in 0 until n) {
            if (!isInlier(line.a, line.t, qs[i], ds[i])) continue
            sq += qs[i]; sd += ds[i]; sqq += qs[i].toDouble() * qs[i]; sqd += qs[i].toDouble() * ds[i]; m++
        }
        val det = m * sqq - sq * sq
        if (m < 2 || abs(det) < 1e-12) return null
        val a = (m * sqd - sq * sd) / det
        val t = (sd - a * sq) / m
        return Line(a.toFloat(), t.toFloat(), m)
    }

    private fun freePlaneAgrees(a: Float, g: Vec3, xs: FloatArray, ys: FloatArray, ds: FloatArray, inlier: BooleanArray, n: Int): Boolean {
        // Normal equations for d = k1·x + k2·y + k0.
        val m = Array(3) { DoubleArray(3) }
        val b = DoubleArray(3)
        for (i in 0 until n) {
            if (!inlier[i]) continue
            val row = doubleArrayOf(xs[i].toDouble(), ys[i].toDouble(), 1.0)
            for (r in 0..2) {
                b[r] += row[r] * ds[i]
                for (c in 0..2) m[r][c] += row[r] * row[c]
            }
        }
        val k = solve3(m, b) ?: return false
        val expectedX = a * g.x
        val expectedY = a * g.y
        val expectedNorm = sqrt(expectedX * expectedX + expectedY * expectedY)
        val freeNorm = sqrt(k[0] * k[0] + k[1] * k[1]).toFloat()
        if (expectedNorm < 1e-6f || freeNorm < 1e-6f) return false
        val cos = ((k[0] * expectedX + k[1] * expectedY) / (freeNorm * expectedNorm)).toFloat().coerceIn(-1f, 1f)
        val angle = Math.toDegrees(acos(cos).toDouble())
        val ratio = freeNorm / expectedNorm
        return angle <= maxGravityAngleDeg && ratio in 0.75f..1.33f
    }

    private fun stability(): Float {
        if (recentCoverage.size < 2) return 1f
        val mean = recentCoverage.average().toFloat()
        val spread = recentCoverage.maxOf { abs(it - mean) }
        return (1f - spread / STABILITY_SPREAD).coerceIn(0.5f, 1f)
    }

    private fun invalid(validity: GroundFitValidity, w: Int, h: Int, time: Long?) = GroundObservation(
        time, validity, BinaryMask(w, h), BinaryMask(w, h), BinaryMask(w, h), 0f, 0f, 0f, false, 0f,
    )

    private companion object {
        const val SEED = 0x5A11E25L
        const val MIN_SAMPLES = 50
        const val MIN_Q_SPAN = 0.05f
        const val MIN_PAIR_Q_SPAN = 0.02f
        const val GOOD_INLIER_RATIO = 0.6f
        const val MIN_RELATIVE_RISE = 0.15f
        const val STABILITY_SPREAD = 0.2f

        /** Gaussian elimination with partial pivoting; null when singular. */
        fun solve3(m: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
            val a = Array(3) { r -> doubleArrayOf(m[r][0], m[r][1], m[r][2], b[r]) }
            for (col in 0..2) {
                val pivot = (col..2).maxBy { abs(a[it][col]) }
                if (abs(a[pivot][col]) < 1e-12) return null
                val tmp = a[col]; a[col] = a[pivot]; a[pivot] = tmp
                for (r in 0..2) {
                    if (r == col) continue
                    val f = a[r][col] / a[col][col]
                    for (c in col..3) a[r][c] -= f * a[col][c]
                }
            }
            return DoubleArray(3) { a[it][3] / a[it][it] }
        }
    }
}
