package com.sailens.guidance.world

import com.sailens.guidance.geometry.CameraIntrinsics
import com.sailens.vision.detection.DetectionDecoderTransform
import kotlin.math.sqrt

/** Closed intervals in Double. Every operation rounds the computed bounds outward. */
internal data class Bound(val low: Double, val high: Double = low) {
    val finite: Boolean get() = low.isFinite() && high.isFinite() && low <= high

    operator fun plus(other: Bound) = enclosing(low + other.low, high + other.high)
    operator fun minus(other: Bound) = enclosing(low - other.high, high - other.low)
    operator fun unaryMinus() = Bound(-high, -low)

    operator fun times(other: Bound): Bound {
        val a = low * other.low
        val b = low * other.high
        val c = high * other.low
        val d = high * other.high
        return enclosing(minOf(minOf(a, b), minOf(c, d)), maxOf(maxOf(a, b), maxOf(c, d)))
    }

    /** Used only with a positive denominator. */
    operator fun div(other: Bound): Bound = this * enclosing(1.0 / other.high, 1.0 / other.low)

    fun square(): Bound {
        val max = maxOf(low * low, high * high)
        val min = if (low <= 0.0 && high >= 0.0) 0.0 else minOf(low * low, high * high)
        return enclosing(min, max)
    }

    fun sqrt() = enclosing(sqrt(maxOf(0.0, low)), sqrt(maxOf(0.0, high)))

    fun union(other: Bound) = Bound(minOf(low, other.low), maxOf(high, other.high))

    companion object {
        fun enclosing(low: Double, high: Double = low) = Bound(Math.nextDown(low), Math.nextUp(high))
    }
}

internal data class ImageFootprint(val x: Bound, val y: Bound)

internal data class PixelArea(val left: Int, val top: Int, val right: Int, val bottom: Int)

private data class BoundedVector(val x: Bound, val y: Bound, val z: Bound) {
    operator fun plus(other: BoundedVector) = BoundedVector(x + other.x, y + other.y, z + other.z)
    operator fun times(scale: Bound) = BoundedVector(x * scale, y * scale, z * scale)
    fun dot(other: BoundedVector) = x * other.x + y * other.y + z * other.z
    fun norm() = (x.square() + y.square() + z.square()).sqrt()
}

internal class ProjectedGrid {
    private val coordinates = DoubleArray((LocalGroundGrid.ROWS + 1) * columns * 4)

    fun projectCorners(rows: DoubleArray, rightEdges: DoubleArray) {
        for (row in 0..LocalGroundGrid.ROWS) {
            val from = row * WORLD_ROW_WIDTH
            for (col in rightEdges.indices) {
                val right = rightEdges[col]
                val lowSlope = if (right >= 0.0) 4 else 5
                val highSlope = if (right >= 0.0) 5 else 4
                val at = (row * columns + col) * 4
                coordinates[at] = Math.nextDown(rows[from] + Math.nextDown(rows[from + lowSlope] * right))
                coordinates[at + 1] = Math.nextUp(rows[from + 1] + Math.nextUp(rows[from + highSlope] * right))
                coordinates[at + 2] = Math.nextDown(rows[from + 2] + Math.nextDown(rows[from + 2 + lowSlope] * right))
                coordinates[at + 3] = Math.nextUp(rows[from + 3] + Math.nextUp(rows[from + 2 + highSlope] * right))
            }
        }
    }

    inline fun visitFootprint(row: Int, col: Int, visit: (Double, Double, Double, Double) -> Unit) {
        val a = (row * columns + col) * 4
        val b = a + 4
        val c = a + columns * 4
        val d = c + 4
        val left = min4(a, b, c, d)
        val right = max4(a + 1, b + 1, c + 1, d + 1)
        val top = min4(a + 2, b + 2, c + 2, d + 2)
        val bottom = max4(a + 3, b + 3, c + 3, d + 3)
        if (left.isFinite() && right.isFinite() && top.isFinite() && bottom.isFinite() && left <= right && top <= bottom) {
            visit(left, right, top, bottom)
        }
    }

    private fun min4(a: Int, b: Int, c: Int, d: Int) =
        minOf(minOf(coordinates[a], coordinates[b]), minOf(coordinates[c], coordinates[d]))

    private fun max4(a: Int, b: Int, c: Int, d: Int) =
        maxOf(maxOf(coordinates[a], coordinates[b]), maxOf(coordinates[c], coordinates[d]))

    companion object {
        const val columns = LocalGroundGrid.COLUMNS + 1
    }
}

/** The conservative projection and the ray bases share the same nominal ground geometry. */
internal class WorldProjection(frame: LocalGroundFrame) {
    val intrinsics: CameraIntrinsics = checkNotNull(frame.geometry.intrinsics)
    val phone = frame.geometry.phone
    private val g = checkNotNull(frame.geometry.gravityInCamera)
    private val gx = Bound(g.x.toDouble())
    private val gy = Bound(g.y.toDouble())
    private val gz = Bound(g.z.toDouble())
    private val horizontal = (gx.square() + gy.square()).sqrt()
    private val magnitude = (gx.square() + gy.square() + gz.square()).sqrt()
    private val down = BoundedVector(gx / magnitude, gy / magnitude, gz / magnitude)
    private val origin = down * Bound(frame.geometry.phone.heightMeters.toDouble())
    private val forwardLength = horizontal / magnitude
    private val forward = BoundedVector(-down.z * down.x / forwardLength, -down.z * down.y / forwardLength, forwardLength)
    private val right = BoundedVector(gy / horizontal, -gx / horizontal, Bound(0.0))
    private val fx = Bound(intrinsics.fx.toDouble())
    private val fy = Bound(intrinsics.fy.toDouble())
    private val cx = Bound(intrinsics.cx.toDouble())
    private val cy = Bound(intrinsics.cy.toDouble())

    /** Each row stores the two bounds for x base, y base, x slope, and y slope, in that order. */
    fun projectRowsInto(rows: DoubleArray) {
        for (row in 0..LocalGroundGrid.ROWS) {
            val offset = row * WORLD_ROW_WIDTH
            val f = LocalGroundGrid.forwardEdge(row)
            // The right axis has no optical component, so the row shares one denominator.
            val minZ = rowLow(origin.z, forward.z, f)
            val maxZ = rowHigh(origin.z, forward.z, f)
            if (minZ <= 0 || !minZ.isFinite() || !maxZ.isFinite() || minZ > maxZ) {
                rows.fill(Double.NaN, offset, offset + WORLD_ROW_WIDTH)
                continue
            }
            // A row has constant optical depth. Bound its affine intercepts and slopes once.
            val minInverseZ = Math.nextDown(1.0 / maxZ)
            val maxInverseZ = Math.nextUp(1.0 / minZ)
            writeAxis(rows, offset, origin.x, forward.x, right.x, fx, cx, f, minInverseZ, maxInverseZ)
            writeAxis(rows, offset + 2, origin.y, forward.y, right.y, fy, cy, f, minInverseZ, maxInverseZ)
        }
    }

    // Forward edges are nonnegative, so the row extrema use the matching endpoints.
    private fun rowLow(origin: Bound, slope: Bound, f: Double): Double = Math.nextDown(origin.low + Math.nextDown(slope.low * f))
    private fun rowHigh(origin: Bound, slope: Bound, f: Double): Double = Math.nextUp(origin.high + Math.nextUp(slope.high * f))

    private fun writeAxis(
        rows: DoubleArray, offset: Int, origin: Bound, forward: Bound, right: Bound,
        focal: Bound, centre: Bound, f: Double, minInverseZ: Double, maxInverseZ: Double,
    ) {
        val minScale = Math.nextDown(focal.low * minInverseZ)
        val maxScale = Math.nextUp(focal.high * maxInverseZ)
        val minBase = rowLow(origin, forward, f)
        val maxBase = rowHigh(origin, forward, f)
        rows[offset] = Math.nextDown(scaleLow(minBase, minScale, maxScale) + centre.low)
        rows[offset + 1] = Math.nextUp(scaleHigh(maxBase, minScale, maxScale) + centre.high)
        rows[offset + 4] = scaleLow(right.low, minScale, maxScale)
        rows[offset + 5] = scaleHigh(right.high, minScale, maxScale)
    }

    private fun scaleLow(value: Double, min: Double, max: Double) =
        Math.nextDown(value * if (value >= 0.0) min else max)

    private fun scaleHigh(value: Double, min: Double, max: Double) =
        Math.nextUp(value * if (value >= 0.0) max else min)

    /** Closed ground envelope over the entire bottom edge and the height and gravity tolerances. */
    fun contactEnvelope(
        image: ImageFootprint,
        bottom: Bound,
        height: Double,
        heightTolerance: Double,
        gravityToleranceDegrees: Double,
        minDepressionDegrees: Double,
    ): ImageFootprint? {
        if (heightTolerance !in 0.0..<1.0 || gravityToleranceDegrees !in 0.0..90.0 ||
            minDepressionDegrees !in 0.0..90.0
        ) return null

        val y = (bottom - cy) / fy
        val a = BoundedVector((Bound(image.x.low) - cx) / fx, y, Bound(1.0))
        val b = BoundedVector((Bound(image.x.high) - cx) / fx, y, Bound(1.0))
        val q = down.dot(a).union(down.dot(b))
        val norm = a.norm().union(b.norm()).high
        val minDown = sinUpper(Math.toRadians(minDepressionDegrees))
        if (q.low <= 0.0 || (Bound(q.low) / Bound(norm)).low < minDown) return null

        val gravError = Bound(sinUpper(Math.toRadians(gravityToleranceDegrees) / 2.0)) * Bound(2.0) * Bound(norm)
        val denominator = q + Bound(-gravError.high, gravError.high)
        if (!denominator.finite || denominator.low <= 0.0) return null
        val h = Bound(height) * (Bound(1.0) + Bound(-heightTolerance, heightTolerance))
        val scale = h / denominator
        val f = forward.dot(a).union(forward.dot(b)) * scale
        val r = right.dot(a).union(right.dot(b)) * scale
        return if (f.finite && r.finite && f.low >= 0.0) ImageFootprint(f, r) else null
    }

    // Allow for rounding of the degree conversion and the one-ulp bound of sin.
    private fun sinUpper(radians: Double): Double = Math.nextUp(Math.nextUp(StrictMath.sin(Math.nextUp(radians))))
}

internal const val BOX_ROUNDING = DetectionDecoderTransform.MAX_NORMALIZED_EDGE_ERROR
internal const val WORLD_ROW_WIDTH = 8
