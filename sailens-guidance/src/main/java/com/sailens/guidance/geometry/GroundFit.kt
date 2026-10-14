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
     * Ground is visible, but none of it is the surface at the user's feet: the nearest samples are
     * an obstacle, or split between levels. The plane further out may be a level the user is not on.
     */
    NO_SUPPORT,

    /**
     * The fitted ground jumped to a level the previous fits saw below ground -- the floor of a drop
     * now filling the near view. Relative depth cannot tell "stepped down" from "looking over the
     * edge", so the fit is unknown until two levels are seen together again, or a new session.
     */
    SUPPORT_SWITCH,

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
 * Every decision uses `(d − t) / (A·q)`, which is `Z_ground / Z` -- a ratio of depths that no
 * choice of a and b changes.
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
 * Gravity-constrained ground fit on a disparity map (§9): sample the lower image below the horizon,
 * RANSAC the line `d = A·q + t`, refit on the inliers, then label a grid. Deterministic (seeded).
 * Not thread-safe: one per pipeline. It keeps a little history -- coverage for stability, the last
 * below-ground region for the support-switch guard -- so [reset] between sessions.
 *
 * Which plane is "the ground": the one under the user. Samples are taken nearest first (the bottom
 * of the view, closest to the feet), and a candidate is scored by the unbroken run of them it
 * explains from the nearest outward. A larger plane further out -- the floor of a drop being
 * approached -- has no run from the feet, however many samples it explains.
 *
 * @param inlierThreshold ground when the depth ratio `Z_ground / Z` is within this of 1 (4 %).
 * @param structureFactor beyond this many thresholds a point is above / below ground (3×).
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

    /** The below-ground cells of the last valid fit: the guard compares the next fit with them. */
    private var lastBelow: BinaryMask? = null
    private var holdingSwitch = false

    fun reset() {
        recentCoverage.clear()
        lastBelow = null
        holdingSwitch = false
    }

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
        val empty = { validity: GroundFitValidity -> invalid(validity, gridWidth, gridHeight, frameTimeNanos) }

        // 1. Samples below the horizon, on a coarse grid of the lower part of the analysis image.
        val capacity = sampleColumns * sampleRows
        val qs = FloatArray(capacity)
        val ds = FloatArray(capacity)
        val xs = FloatArray(capacity)
        val ys = FloatArray(capacity)
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
        if (n < MIN_SAMPLES) return empty(GroundFitValidity.TOO_FEW_SAMPLES)
        var qMin = Float.MAX_VALUE
        var qMax = -Float.MAX_VALUE
        for (i in 0 until n) { qMin = minOf(qMin, qs[i]); qMax = maxOf(qMax, qs[i]) }
        if (qMax - qMin < MIN_Q_SPAN) return empty(GroundFitValidity.DEGENERATE_VIEW)
        // Samples nearest first, in bins: the support level runs outward from the feet without a gap.
        val byNearness = (0 until n).sortedByDescending { qs[it] }.toIntArray()
        val binSize = maxOf(1, n / SUPPORT_BINS)

        // 2. RANSAC over both slopes. Any positive line counts toward "is there ground at all"; a
        // better negative one means inverse output. The ground is the line explaining the longest
        // unbroken run outward from the feet; on a tie, the one that matches less outside that run. Ranked by total inliers instead, a
        // line through the shrinking near ground that also clips a drop's floor beats the true
        // near ground as it runs out: a short run does not pin a line by itself.
        val random = Random(SEED)
        var bestPositive = 0
        var bestNegative = 0
        var bestSupported = Line(0f, 0f, 0)
        var bestConnected = -1
        var bestOutside = Int.MAX_VALUE
        repeat(iterations) {
            val i = random.nextInt(n)
            val j = random.nextInt(n)
            val dq = qs[j] - qs[i]
            if (abs(dq) < MIN_PAIR_Q_SPAN) return@repeat
            val a = (ds[j] - ds[i]) / dq
            if (a == 0f) return@repeat
            val t = ds[i] - a * qs[i]
            var count = 0
            for (k in 0 until n) if (isInlier(a, t, qs[k], ds[k])) count++
            if (a < 0) {
                bestNegative = maxOf(bestNegative, count)
            } else {
                bestPositive = maxOf(bestPositive, count)
                val end = runEnd(a, t, qs, ds, byNearness, binSize)
                if (end > 0) {
                    val connected = runInliers(a, t, qs, ds, byNearness, end)
                    val outside = count - connected
                    if (connected > bestConnected || (connected == bestConnected && outside < bestOutside)) {
                        bestSupported = Line(a, t, count)
                        bestConnected = connected
                        bestOutside = outside
                    }
                }
            }
        }
        if (bestNegative > bestPositive && bestNegative >= minInlierRatio * n) return empty(GroundFitValidity.INVERSE_OUTPUT)
        if (bestPositive < minInlierRatio * n) return empty(GroundFitValidity.NO_GROUND)
        if (bestSupported.inliers == 0) return empty(GroundFitValidity.NO_SUPPORT)

        // 3. Least squares on the run's inliers only, so nothing beyond the near level pulls it;
        // then the refit's own run, which must still start at the feet and span enough depth to
        // pin a line.
        val chosenEnd = runEnd(bestSupported.a, bestSupported.t, qs, ds, byNearness, binSize)
        val refit = refit(bestSupported, qs, ds, byNearness, chosenEnd) ?: return empty(GroundFitValidity.NO_SUPPORT)
        if (refit.a <= 0f) return empty(GroundFitValidity.NO_SUPPORT)
        val end = runEnd(refit.a, refit.t, qs, ds, byNearness, binSize)
        if (end == 0) return empty(GroundFitValidity.NO_SUPPORT)
        var runCount = 0
        var runQMin = Float.MAX_VALUE
        var runQMax = -Float.MAX_VALUE
        for (k in 0 until end) {
            val i = byNearness[k]
            if (!isInlier(refit.a, refit.t, qs[i], ds[i])) continue
            runCount++
            runQMin = minOf(runQMin, qs[i]); runQMax = maxOf(runQMax, qs[i])
        }
        if (runCount < minInlierRatio * n) return empty(GroundFitValidity.NO_GROUND)
        // Ground recedes: the run must span a real range of depth (Z ∝ 1/q on ground). A wall
        // filling the view has none; the last strip of ground before a drop has too little to
        // pin both A and t.
        if (runQMax / runQMin < 1f + MIN_DEPTH_SPAN) return empty(GroundFitValidity.NO_SUPPORT)
        val inlier = BooleanArray(n) { isInlier(refit.a, refit.t, qs[it], ds[it]) }
        val inlierRatio = inlier.count { it }.toFloat() / n

        // 6a. Agreement: a free plane d = k1·x + k2·y + k0 on the inliers should lean as gravity says.
        val agrees = freePlaneAgrees(refit.a, g, xs, ys, ds, inlier, n)

        // 3–4. Label the grid by depth ratio.
        val ground = BinaryMask(gridWidth, gridHeight)
        val above = BinaryMask(gridWidth, gridHeight)
        val below = BinaryMask(gridWidth, gridHeight)
        for (gy in 0 until gridHeight) {
            val v = (gy + 0.5f) * transform.analysisHeight / gridHeight
            for (gx in 0 until gridWidth) {
                val u = (gx + 0.5f) * transform.analysisWidth / gridWidth
                val d = disparity.sampleAt(u, v) ?: continue
                val qv = q(u, v)
                val inverseDepth = d - refit.t
                if (qv < minQ) {
                    // At or above the horizon nothing is ground; nearer than the ground at minQ is structure.
                    if (inverseDepth > refit.a * minQ) above.set(gx, gy, true)
                    continue
                }
                val ratio = inverseDepth / (refit.a * qv) - 1f
                when {
                    abs(ratio) <= inlierThreshold -> ground.set(gx, gy, true)
                    ratio > inlierThreshold * structureFactor -> above.set(gx, gy, true)
                    ratio < -inlierThreshold * structureFactor -> below.set(gx, gy, true)
                }
            }
        }

        // 5. The support-switch guard: the cells the last valid fit saw below ground now read as
        // ground. The floor of a drop has taken over the near view; that is not the user's level
        // until shown otherwise.
        val previousBelow = lastBelow
        if (previousBelow != null && previousBelow.width == gridWidth && previousBelow.height == gridHeight) {
            val previousCount = previousBelow.countTrue()
            if (holdingSwitch || previousCount >= MIN_BELOW_CELLS_FRACTION * gridWidth * gridHeight) {
                var flipped = 0
                if (!holdingSwitch) {
                    for (gy in 0 until gridHeight) for (gx in 0 until gridWidth) {
                        if (previousBelow.get(gx, gy) && ground.get(gx, gy)) flipped++
                    }
                }
                val switched = !holdingSwitch && flipped >= SWITCH_FLIP_FRACTION * previousCount
                // Held until a fit again sees a level below its ground: two levels together, the
                // near one under the user. Nothing else releases it -- not time: a user standing at
                // the edge gives any number of identical frames, and none of them shows a step down
                // (review finding). Rotation-only motion cannot show one either; a translation
                // estimate (M5b) could, later. A new session starts clear ([reset]).
                val twoLevelsAgain = below.countTrue() >= MIN_BELOW_CELLS_FRACTION * gridWidth * gridHeight
                if (switched || (holdingSwitch && !twoLevelsAgain)) {
                    holdingSwitch = true
                    return empty(GroundFitValidity.SUPPORT_SWITCH)
                }
                holdingSwitch = false
            }
        }

        // 6b. Confidence: enough ground, a plane that agrees with gravity, and a steady result.
        val coverage = ground.coverage()
        recentCoverage.addLast(coverage)
        while (recentCoverage.size > stabilityFrames) recentCoverage.removeFirst()
        val confidence = ((inlierRatio - minInlierRatio) / (GOOD_INLIER_RATIO - minInlierRatio)).coerceIn(0f, 1f) *
            (if (agrees) 1f else 0.5f) * stability()
        lastBelow = below

        return GroundObservation(
            frameTimeNanos, GroundFitValidity.VALID, ground, above, below,
            refit.a, refit.t, inlierRatio, agrees, confidence,
        )
    }

    private data class Line(val a: Float, val t: Float, val inliers: Int)

    /**
     * How far, in samples nearest first, the line's support runs from the feet: up to the first
     * bin it explains less than half of.
     */
    private fun runEnd(a: Float, t: Float, qs: FloatArray, ds: FloatArray, byNearness: IntArray, binSize: Int): Int {
        var start = 0
        while (start < byNearness.size) {
            val end = minOf(start + binSize, byNearness.size)
            var hits = 0
            for (k in start until end) if (isInlier(a, t, qs[byNearness[k]], ds[byNearness[k]])) hits++
            if (hits * 2 < end - start) break
            start = end
        }
        return start
    }

    private fun runInliers(a: Float, t: Float, qs: FloatArray, ds: FloatArray, byNearness: IntArray, end: Int): Int {
        var count = 0
        for (k in 0 until end) if (isInlier(a, t, qs[byNearness[k]], ds[byNearness[k]])) count++
        return count
    }

    /** Within the threshold of the line in depth ratio: `|d − (A·q + t)| ≤ thr · |A|·q`. */
    private fun isInlier(a: Float, t: Float, q: Float, d: Float): Boolean = abs(d - (a * q + t)) <= inlierThreshold * abs(a) * q

    /** Least squares on [line]'s inliers among the first [end] samples, nearest first. */
    private fun refit(line: Line, qs: FloatArray, ds: FloatArray, byNearness: IntArray, end: Int): Line? {
        var sq = 0.0; var sd = 0.0; var sqq = 0.0; var sqd = 0.0; var m = 0
        for (k in 0 until end) {
            val i = byNearness[k]
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
        const val STABILITY_SPREAD = 0.2f

        /** The support run is measured in this many bins of samples, nearest first. */
        const val SUPPORT_BINS = 20

        /** The support run must span at least this much in depth (far / near − 1) to pin a line. */
        const val MIN_DEPTH_SPAN = 0.25f

        /** A below-ground region smaller than this fraction of the grid does not arm the switch guard. */
        const val MIN_BELOW_CELLS_FRACTION = 0.02f

        /** The switch: at least this fraction of the last below-ground cells now read as ground. */
        const val SWITCH_FLIP_FRACTION = 0.5f

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
