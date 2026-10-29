package com.sailens.guidance.sim

import com.sailens.core.geometry.NormalizedRect
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.perception.ObstacleDetection
import com.sailens.guidance.trace.capture.CaptureCameraRecord
import com.sailens.guidance.trace.capture.CaptureSensor
import com.sailens.guidance.trace.capture.SensorRecord
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/*
 * The physical truth of a simulator scenario (docs/geometry.md §7): a pinhole camera on a
 * phone held at a height and pitch, looking into a world of horizontal ground patches, vertical
 * boxes and a far background. From it the simulator renders what the models would report -- a
 * semantic class map and detection boxes in the same upright frame -- and the raw facts a
 * capture would hold (camera characteristics, sensor-to-buffer transform, gravity samples), so
 * the camera geometry resolves them exactly as it resolves a recording, checked against this truth.
 *
 * World axes: X right, Y up, Z forward along the ground; metres. Camera axes: x right, y down,
 * z along the optical axis, in the upright (rotated) analysis image.
 */

/** Cityscapes train ids used by the scenes. */
object SceneClasses {
    const val ROAD = 0
    const val SIDEWALK = 1
    const val BUILDING = 2
    const val WALL = 3
    const val POLE = 5
    const val VEGETATION = 8
    const val TERRAIN = 9
    const val SKY = 10
    const val PERSON = 11
    const val CAR = 13
    const val BICYCLE = 18
}

/**
 * The camera, as the SM8850 reports it: a 4096x3072 active array with focal length 2877.6 px,
 * analysis frames 1280x720 cropped from it (scale 0.3125, 120 px off the top) and rotated 90
 * degrees to portrait, class maps at half the upright frame: 360x640.
 */
data class SyntheticCamera(
    val activeWidth: Int = 4096,
    val activeHeight: Int = 3072,
    val focalActive: Float = 2877.6f,
    val bufferWidth: Int = 1280,
    val bufferHeight: Int = 720,
    val sensorOrientation: Int = 90,
    val mapWidth: Int = 360,
    val mapHeight: Int = 640,
) {
    private val scale = bufferWidth.toFloat() / activeWidth
    private val offsetY = -(activeHeight * scale - bufferHeight) / 2f
    private val mapScale = mapWidth.toFloat() / bufferHeight

    /**
     * Focal length and principal point in class-map pixels (what the geometry must resolve to: 449.625, 180,
     * 320). Continuous pixel coordinates -- pixel i spans [i, i+1) -- so the 90-degree rotation maps
     * buffer y to upright x as `bufferHeight - y`.
     */
    val focal: Float = focalActive * scale * mapScale
    val cx: Float = (bufferHeight - (activeHeight / 2f * scale + offsetY)) * mapScale
    val cy: Float = (activeWidth / 2f * scale) * mapScale

    /** The camera record a capture of this camera would hold. */
    fun cameraRecord(): CaptureCameraRecord = CaptureCameraRecord(
        cameraId = "0",
        capturedAtElapsedRealtimeNanos = 0,
        sensorOrientationDegrees = sensorOrientation,
        timestampSource = "realtime",
        lensIntrinsicCalibration = listOf(focalActive, focalActive, activeWidth / 2f, activeHeight / 2f, 0f),
        lensDistortion = listOf(0f, 0f, 0f, 0f, 0f),
        activeArray = listOf(0, 0, activeWidth, activeHeight),
        pixelArrayWidth = activeWidth,
        pixelArrayHeight = activeHeight,
        availableFocalLengthsMm = listOf(5.59f),
    )

    /** CameraX's sensor-to-buffer transform for this crop (row-major 3x3). */
    fun sensorToBufferTransform(): List<Float> = listOf(scale, 0f, 0f, 0f, scale, offsetY, 0f, 0f, 1f)
}

/**
 * Where the phone is: height of the camera above the ground at Y = 0, pitch below horizontal, and
 * how far along Z the user has walked.
 */
data class Pose(val heightM: Float, val pitchDownDeg: Float, val walkedM: Float = 0f) {
    private val t = Math.toRadians(pitchDownDeg.toDouble())
    val forward = Vec(0.0, -sin(t), cos(t))
    val down = Vec(0.0, -cos(t), -sin(t))
    val right = Vec(1.0, 0.0, 0.0)
    val position = Vec(0.0, heightM.toDouble(), walkedM.toDouble())

    /**
     * Android TYPE_GRAVITY in device coordinates (portrait, back camera): the reaction to gravity,
     * pointing up, so camera-down is (-gx, gy, gz) normalised.
     */
    fun gravity(): List<Float> = listOf(0f, (9.81 * cos(t)).toFloat(), (9.81 * sin(t)).toFloat())
}

data class Vec(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec) = Vec(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec) = Vec(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Double) = Vec(x * s, y * s, z * s)
    infix fun dot(o: Vec) = x * o.x + y * o.y + z * o.z
}

/** A horizontal patch of ground at height [y], spanning [x0, x1] x [z0, z1]. */
data class GroundPatch(val classId: Int, val x0: Double, val x1: Double, val z0: Double, val z1: Double, val y: Double = 0.0)

/**
 * An upright box (an obstacle, a wall). [detection] is how the detector would report it, or null when
 * the detector does not see it (a wall, a parked bicycle the scene leaves to sem).
 */
data class Box(
    val classId: Int,
    val x0: Double,
    val x1: Double,
    val z0: Double,
    val z1: Double,
    val height: Double,
    val baseY: Double = 0.0,
    val detection: DetectedAs? = null,
)

/**
 * How the detector reports a box. [visibleAboveM]: the detector sees only the part above this
 * height (a person behind a low wall), so the reported box ends where the occluder does, not at
 * the ground contact.
 */
data class DetectedAs(
    val cocoId: Int,
    val label: String,
    val category: ObstacleCategory,
    val confidence: Float = 0.85f,
    val visibleAboveM: Double? = null,
)

/** Everything in front of the camera; [backgroundZ] is a row of buildings, sky above them. */
data class World(
    val ground: List<GroundPatch>,
    val boxes: List<Box> = emptyList(),
    val backgroundZ: Double = 40.0,
    val backgroundHeight: Double = 18.0,
)

/** What the models would report for one frame. */
class RenderedFrame(val classMap: IntArray, val detections: List<ObstacleDetection>)

/** Renders [world] seen from [pose] by [camera]. */
fun render(camera: SyntheticCamera, pose: Pose, world: World): RenderedFrame {
    val w = camera.mapWidth
    val h = camera.mapHeight
    val ids = IntArray(w * h)
    val o = pose.position
    val f = pose.forward
    val r = pose.right
    val d = pose.down
    val hit = Hit()
    for (v in 0 until h) {
        val yn = (v + 0.5 - camera.cy) / camera.focal
        for (u in 0 until w) {
            val xn = (u + 0.5 - camera.cx) / camera.focal
            firstHit(o.x, o.y, o.z, f.x + r.x * xn + d.x * yn, f.y + r.y * xn + d.y * yn, f.z + r.z * xn + d.z * yn, world, hit)
            ids[v * w + u] = hit.cls
        }
    }
    val detections = world.boxes.mapNotNull { box -> box.detection?.let { project(camera, pose, box, it) } }
    return RenderedFrame(ids, detections)
}

/** The first surface along a ray: its distance (camera z-depth, since rays have unit forward) and class. */
private class Hit {
    var t = Double.POSITIVE_INFINITY
    var cls = SceneClasses.SKY
}

/** The first surface along the ray; primitives only, it runs for every pixel. */
private fun firstHit(ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double, world: World, hit: Hit) {
    var best = Double.MAX_VALUE
    var cls = -1
    if (abs(dy) > 1e-9) {
        for (patch in world.ground) {
            val t = (patch.y - oy) / dy
            if (t <= 0 || t >= best) continue
            val x = ox + dx * t
            val z = oz + dz * t
            if (x >= patch.x0 && x <= patch.x1 && z >= patch.z0 && z <= patch.z1) {
                best = t
                cls = patch.classId
            }
        }
    }
    for (box in world.boxes) {
        val t = rayBox(ox, oy, oz, dx, dy, dz, box)
        if (t < best) {
            best = t
            cls = box.classId
        }
    }
    if (cls >= 0) {
        hit.t = best
        hit.cls = cls
        return
    }
    // Background: a row of buildings at backgroundZ, sky above them.
    if (dz > 1e-9) {
        val t = (world.backgroundZ - oz) / dz
        if (oy + dy * t < world.backgroundHeight) {
            hit.t = t
            hit.cls = SceneClasses.BUILDING
            return
        }
    }
    hit.t = Double.POSITIVE_INFINITY
    hit.cls = SceneClasses.SKY
}

/**
 * What a relative-depth model would output for [world] seen from [pose]: disparity `a/Z + b` (Z the
 * camera z-depth; sky gives b) on the model's [width] × [height] tensor, whose pixels map to the
 * upright analysis image ([analysisWidth] × [analysisHeight], the class map scaled up) through
 * [toAnalysis]. Padding (outside the analysis image) is 0. [noise] multiplies each value by
 * 1 ± noise, seeded.
 */
fun renderDisparity(
    camera: SyntheticCamera,
    pose: Pose,
    world: World,
    width: Int,
    height: Int,
    analysisWidth: Int,
    analysisHeight: Int,
    toAnalysis: (x: Float, y: Float) -> Pair<Float, Float>,
    a: Double = 10.0,
    b: Double = 0.2,
    noise: Double = 0.0,
    seed: Int = 7,
): FloatArray {
    val scale = analysisWidth.toDouble() / camera.mapWidth
    val focal = camera.focal * scale
    val cx = camera.cx * scale
    val cy = camera.cy * scale
    val o = pose.position
    val f = pose.forward
    val r = pose.right
    val d = pose.down
    val random = kotlin.random.Random(seed)
    val hit = Hit()
    return FloatArray(width * height) { index ->
        val (u, v) = toAnalysis(index % width + 0.5f, index / width + 0.5f)
        if (u < 0 || v < 0 || u >= analysisWidth || v >= analysisHeight) return@FloatArray 0f
        val xn = (u - cx) / focal
        val yn = (v - cy) / focal
        firstHit(o.x, o.y, o.z, f.x + r.x * xn + d.x * yn, f.y + r.y * xn + d.y * yn, f.z + r.z * xn + d.z * yn, world, hit)
        val clean = a / hit.t + b
        (clean * (1 + noise * (2 * random.nextDouble() - 1))).toFloat()
    }
}

/** Slab intersection with an axis-aligned box: the entry distance, or +infinity for a miss. */
private fun rayBox(ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double, b: Box): Double {
    var tMin = 1e-6
    var tMax = Double.MAX_VALUE
    fun slab(o: Double, d: Double, lo: Double, hi: Double): Boolean {
        if (abs(d) < 1e-12) return o in lo..hi
        var t1 = (lo - o) / d
        var t2 = (hi - o) / d
        if (t1 > t2) t1 = t2.also { t2 = t1 }
        if (t1 > tMin) tMin = t1
        if (t2 < tMax) tMax = t2
        return tMin <= tMax
    }
    if (!slab(ox, dx, b.x0, b.x1)) return Double.POSITIVE_INFINITY
    if (!slab(oy, dy, b.baseY, b.baseY + b.height)) return Double.POSITIVE_INFINITY
    if (!slab(oz, dz, b.z0, b.z1)) return Double.POSITIVE_INFINITY
    return tMin
}

/** The detector's box: the projection of the box's corners, clipped to the frame. */
private fun project(camera: SyntheticCamera, pose: Pose, box: Box, det: DetectedAs): ObstacleDetection? {
    val us = mutableListOf<Double>()
    val vs = mutableListOf<Double>()
    val visibleBase = maxOf(box.baseY, det.visibleAboveM ?: box.baseY)
    for (x in listOf(box.x0, box.x1)) for (y in listOf(visibleBase, box.baseY + box.height)) for (z in listOf(box.z0, box.z1)) {
        val rel = Vec(x, y, z) - pose.position
        val zc = rel dot pose.forward
        if (zc <= 0.05) return null
        us += camera.cx + camera.focal * (rel dot pose.right) / zc
        vs += camera.cy + camera.focal * (rel dot pose.down) / zc
    }
    val left = (us.min() / camera.mapWidth).coerceIn(0.0, 1.0)
    val right = (us.max() / camera.mapWidth).coerceIn(0.0, 1.0)
    val top = (vs.min() / camera.mapHeight).coerceIn(0.0, 1.0)
    val bottom = (vs.max() / camera.mapHeight).coerceIn(0.0, 1.0)
    if (right - left < 1e-3 || bottom - top < 1e-3) return null
    return ObstacleDetection(
        det.cocoId,
        det.label,
        det.confidence,
        NormalizedRect(left.toFloat(), top.toFloat(), (right - left).toFloat(), (bottom - top).toFloat()),
        det.category,
    )
}

/** Gravity samples a capture of this pose would hold around [timestampNanos] (50 Hz, steady). */
fun gravitySamples(pose: Pose, timestampNanos: Long): List<SensorRecord> =
    (0 until 4).map { i -> SensorRecord(CaptureSensor.GRAVITY, timestampNanos - (3 - i) * 20_000_000L, 3, pose.gravity()) }
