package com.sailens.guidance.depth.geometry

import com.sailens.guidance.geometry.GroundObservation
import com.sailens.vision.depth.DepthMap
import com.sailens.vision.depth.DisparityMap
import com.sailens.vision.depth.MetricDepthMap

/** The unit of a reading's lengths. */
enum class DepthUnit {
    /** Multiples of the phone height the settings assume: a relative source's only scale. Times that height is metres. */
    PHONE_HEIGHTS,

    /** Metres, as the source measured them. */
    METERS,
}

/**
 * Each label cell's own depth, row-major on the observation's grid, whatever the source: with the view's ray through
 * the cell centre ([DepthViewGeometry.cellRay]) a cell's position in the camera frame is [opticalDepth] times that ray,
 * and its height above the fitted ground is [heightAboveGround]. `NaN` is unknown: a cell the source did not see or
 * measure, or whose depth comes out at or beyond infinity.
 *
 * Each value is the one at the cell's centre, the point the fit labels the cell by: a point observation, not proof that
 * the cell is clear. A thin post inside a cell and off its centre is not in its value. [coverage] says how much of the
 * cell was seen at all, not how much of it is free.
 *
 * Shared: every main frame that reads the same run reads this one object, so it hands out values and copies, never
 * its arrays.
 */
class DepthCells internal constructor(
    val unit: DepthUnit,
    val width: Int,
    val height: Int,
    private val opticalDepth: FloatArray,
    private val heightAboveGround: FloatArray?,
    private val coverage: FloatArray,
) {
    /** Z along the optical axis at the cell centre: relative `A / (d − t)`, which is Z over the phone height; metric, Z as measured. */
    fun opticalDepth(x: Int, y: Int): Float = opticalDepth[y * width + x]

    /** Whether the fit was valid, so that the cells have heights above its ground. */
    val hasHeights: Boolean get() = heightAboveGround != null

    /**
     * The cell centre's height above the fitted ground, in [unit]: relative `1 − (Z/h)·q`; metric `h − Z·q` with the
     * measured Z and the measured height `h = 1/A`, so no setting is read. `NaN` without heights.
     */
    fun heightAboveGround(x: Int, y: Int): Float = heightAboveGround?.get(y * width + x) ?: Float.NaN

    /** The share of the cell's sample points that are known (`DepthMap.isKnownAt`), 0 to 1. */
    fun coverage(x: Int, y: Int): Float = coverage[y * width + x]

    fun copyOpticalDepth(): FloatArray = opticalDepth.copyOf()
    fun copyHeightAboveGround(): FloatArray? = heightAboveGround?.copyOf()
    fun copyCoverage(): FloatArray = coverage.copyOf()

    companion object {
        /** Each cell's coverage counts this many points a side, evenly inside it. */
        const val COVERAGE_POINTS: Int = 3

        /**
         * The cells of [observation], fitted on [map] in [view]. Null when there is nothing to say: a relative map's
         * depths need the fit's A and t, which mean nothing when the fit is not valid. A metric map's depths are its
         * own, so it has cells whatever the fit; heights only on a valid one.
         */
        fun of(map: DepthMap, observation: GroundObservation, view: DepthViewGeometry): DepthCells? = when (map) {
            is DisparityMap -> if (observation.isValid) relative(map, observation, view) else null
            is MetricDepthMap -> metric(map, observation, view)
        }

        private fun metric(map: MetricDepthMap, observation: GroundObservation, view: DepthViewGeometry): DepthCells {
            val w = view.gridWidth
            val h = view.gridHeight
            val depth = FloatArray(w * h)
            for (y in 0 until h) {
                val v = view.cellCenterV(y)
                for (x in 0 until w) depth[y * w + x] = map.depthAt(view.cellCenterU(x), v) ?: Float.NaN
            }
            val heights = observation.measuredPhoneHeightM?.takeIf { observation.isValid }?.let { measured ->
                FloatArray(w * h) { i ->
                    val z = depth[i]
                    if (z.isNaN()) Float.NaN else measured - z * view.q(view.cellCenterU(i % w), view.cellCenterV(i / w))
                }
            }
            return DepthCells(DepthUnit.METERS, w, h, depth, heights, coverage(map, view))
        }

        private fun relative(map: DisparityMap, observation: GroundObservation, view: DepthViewGeometry): DepthCells {
            val w = view.gridWidth
            val h = view.gridHeight
            val depth = FloatArray(w * h)
            val heights = FloatArray(w * h)
            val a = observation.disparityScale
            val t = observation.disparityShift
            for (y in 0 until h) {
                val v = view.cellCenterV(y)
                for (x in 0 until w) {
                    val u = view.cellCenterU(x)
                    val i = y * w + x
                    // The value the fit labels the cell with: Z/h = A / (d − t).
                    val inverse = map.sampleOrNaN(u, v) - t
                    val z = if (inverse > 0f) a / inverse else Float.NaN
                    if (!z.isFinite() || z <= 0f) {
                        depth[i] = Float.NaN
                        heights[i] = Float.NaN
                        continue
                    }
                    depth[i] = z
                    heights[i] = 1f - z * view.q(u, v)
                }
            }
            return DepthCells(DepthUnit.PHONE_HEIGHTS, w, h, depth, heights, coverage(map, view))
        }

        private fun coverage(map: DepthMap, view: DepthViewGeometry): FloatArray {
            val w = view.gridWidth
            val h = view.gridHeight
            val cellW = view.analysisWidth.toFloat() / w
            val cellH = view.analysisHeight.toFloat() / h
            val n = COVERAGE_POINTS
            val total = (n * n).toFloat()
            return FloatArray(w * h) { i ->
                val left = (i % w) * cellW
                val top = (i / w) * cellH
                var known = 0
                for (sy in 0 until n) {
                    val v = top + (sy + 0.5f) * cellH / n
                    for (sx in 0 until n) if (map.isKnownAt(left + (sx + 0.5f) * cellW / n, v)) known++
                }
                known / total
            }
        }
    }
}
