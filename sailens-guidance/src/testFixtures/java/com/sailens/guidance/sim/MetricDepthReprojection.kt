package com.sailens.guidance.sim

import com.sailens.vision.depth.DepthTimes
import com.sailens.vision.depth.MetricDepthMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Reprojects only the cached measurement: adjacent known samples form small surface triangles, projected with a
 * z-buffer and perspective-correct depth. No triangle crosses a hole, low confidence or a depth jump. Newly visible
 * regions have no measured surface to project and stay unknown; the current world's geometry is never read.
 */
internal fun reprojectMetricDepth(camera: SyntheticCamera, measured: MetricDepthMap, from: Pose, to: Pose, times: DepthTimes): MetricDepthMap {
    if (from == to) {
        return if (times == measured.times) measured else MetricDepthMap(measured.width, measured.height, measured.depthM, measured.confidence, measured.minConfidence, measured.transform, times)
    }

    val w = measured.width
    val h = measured.height
    val t = measured.transform
    val scale = t.analysisWidth.toDouble() / camera.mapWidth
    val focal = camera.focal * scale
    val cx = camera.cx * scale
    val cy = camera.cy * scale
    val vertices = arrayOfNulls<DepthVertex>(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        val u = t.toAnalysisX(x + 0.5f)
        val v = t.toAnalysisY(y + 0.5f)
        if (!measured.isKnownAt(u, v)) continue
        val i = y * w + x
        val z = measured.depthM[i].toDouble()
        val ray = from.forward + from.right * ((u - cx) / focal) + from.down * ((v - cy) / focal)
        val relative = from.position + ray * z - to.position
        val newZ = relative dot to.forward
        if (newZ <= 0 || !newZ.isFinite()) continue
        val newU = cx + focal * (relative dot to.right) / newZ
        val newV = cy + focal * (relative dot to.down) / newZ
        // Keep subpixel precision until rasterization: these are projected points, not rounded pixel addresses.
        val px = (newU - t.sourceLeft) * t.scaleX + t.padLeft
        val py = (newV - t.sourceTop) * t.scaleY + t.padTop
        vertices[i] = DepthVertex(px, py, newZ, measured.confidence?.get(i) ?: 1f)
    }

    val depth = FloatArray(w * h) { Float.NaN }
    val confidence = FloatArray(w * h)
    fun triangle(a: DepthVertex, b: DepthVertex, c: DepthVertex) {
        val area = edge(a.x, a.y, b.x, b.y, c.x, c.y)
        if (abs(area) < 1e-12) return
        val left = ceil(minOf(a.x, b.x, c.x) - 0.5).coerceIn(0.0, w.toDouble()).toInt()
        val right = floor(maxOf(a.x, b.x, c.x) - 0.5).coerceIn(-1.0, (w - 1).toDouble()).toInt()
        val top = ceil(minOf(a.y, b.y, c.y) - 0.5).coerceIn(0.0, h.toDouble()).toInt()
        val bottom = floor(maxOf(a.y, b.y, c.y) - 0.5).coerceIn(-1.0, (h - 1).toDouble()).toInt()
        for (y in top..bottom) for (x in left..right) {
            val px = x + 0.5
            val py = y + 0.5
            val wa = edge(b.x, b.y, c.x, c.y, px, py) / area
            val wb = edge(c.x, c.y, a.x, a.y, px, py) / area
            val wc = 1.0 - wa - wb
            if (wa < -1e-9 || wb < -1e-9 || wc < -1e-9) continue
            val z = (1.0 / (wa / a.z + wb / b.z + wc / c.z)).toFloat()
            val i = y * w + x
            if (z.isFinite() && z > 0 && (depth[i].isNaN() || z < depth[i])) {
                depth[i] = z
                confidence[i] = minOf(a.confidence, b.confidence, c.confidence)
            }
        }
    }
    for (y in 0 until h - 1) for (x in 0 until w - 1) {
        val i = y * w + x
        val a = vertices[i] ?: continue
        val b = vertices[i + 1] ?: continue
        val c = vertices[i + w] ?: continue
        val d = vertices[i + w + 1] ?: continue
        // Connect only one locally continuous measured surface, never foreground to background across an edge.
        val minZ = minOf(measured.depthM[i], measured.depthM[i + 1], measured.depthM[i + w], measured.depthM[i + w + 1])
        val maxZ = maxOf(measured.depthM[i], measured.depthM[i + 1], measured.depthM[i + w], measured.depthM[i + w + 1])
        if (maxZ / minZ > MetricDepthMap.MIXED_EDGE_RATIO) continue
        triangle(a, b, d)
        triangle(a, d, c)
    }
    return MetricDepthMap(w, h, depth, confidence, measured.minConfidence, t, times)
}

private class DepthVertex(val x: Double, val y: Double, val z: Double, val confidence: Float)

private fun edge(ax: Double, ay: Double, bx: Double, by: Double, px: Double, py: Double): Double =
    (bx - ax) * (py - ay) - (by - ay) * (px - ax)
