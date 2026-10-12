package com.sailens.guidance.geometry

import kotlin.math.abs

/** Where the focal length came from; a consumer that needs metres treats an estimate with care. */
enum class IntrinsicsSource {
    /** `LENS_INTRINSIC_CALIBRATION`. */
    CALIBRATION,

    /** `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` over `SENSOR_INFO_PHYSICAL_SIZE`, centre assumed. */
    FOCAL_LENGTH_ESTIMATE,
}

/**
 * Pinhole intrinsics in the upright analysis image -- the frame buffer rotated by its
 * `rotationDegrees`, in which the semantic mask and the detection boxes share one coordinate space
 * (§2). Continuous pixel coordinates: pixel i spans [i, i+1), so the centre of a W-wide image is W/2.
 *
 * @param bufferMappingAssumed true when the frame did not report its `sensorToBufferTransform` and
 *   the buffer was assumed to be a centred, aspect-preserving crop of the active array.
 */
data class CameraIntrinsics(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val width: Int,
    val height: Int,
    val source: IntrinsicsSource,
    val bufferMappingAssumed: Boolean = false,
) {
    /** The same camera in an image of [newWidth] × [newHeight] covering the same view (a class map). */
    fun scaledTo(newWidth: Int, newHeight: Int): CameraIntrinsics {
        val sx = newWidth.toFloat() / width
        val sy = newHeight.toFloat() / height
        return copy(fx = fx * sx, fy = fy * sy, cx = cx * sx, cy = cy * sy, width = newWidth, height = newHeight)
    }
}

/**
 * Resolves [CameraIntrinsics] from the raw facts (§7): calibration when reported, else an estimate
 * from focal length and sensor size; mapped from the active array into the buffer through the
 * frame's `sensorToBufferTransform`, then rotated upright. Null when neither source is usable.
 */
object CameraIntrinsicsResolver {

    fun resolve(camera: CameraFacts, frame: FrameFacts): CameraIntrinsics? {
        val active = camera.activeArray ?: return null
        val sensor = calibration(camera, active) ?: estimate(camera, active) ?: return null
        val (transform, assumed) = frame.sensorToBufferTransform?.let { Affine.of(it) to false }
            ?: (Affine.centredFill(active, frame.width, frame.height) to true)
        transform ?: return null
        val buffer = transform.apply(sensor) ?: return null
        return rotateUpright(buffer, frame.width, frame.height, frame.rotationDegrees)
            ?.copy(source = sensor.source, bufferMappingAssumed = assumed)
    }

    /** Intrinsics in some pixel frame, before the rotation upright. */
    private data class Pinhole(val fx: Double, val fy: Double, val cx: Double, val cy: Double, val source: IntrinsicsSource)

    private fun calibration(camera: CameraFacts, active: SensorRect): Pinhole? {
        val k = camera.lensIntrinsicCalibration?.takeIf { it.size >= 4 } ?: return null
        if (k[0] <= 0f || k[1] <= 0f) return null
        // Calibration is in pre-correction active array pixels; the transform starts from the
        // active array, whose origin may sit inside it.
        val pre = camera.preCorrectionActiveArray ?: active
        val dx = active.left - pre.left
        val dy = active.top - pre.top
        return Pinhole(k[0].toDouble(), k[1].toDouble(), (k[2] - dx).toDouble(), (k[3] - dy).toDouble(), IntrinsicsSource.CALIBRATION)
    }

    private fun estimate(camera: CameraFacts, active: SensorRect): Pinhole? {
        val focalMm = camera.availableFocalLengthsMm.firstOrNull()?.takeIf { it > 0f } ?: return null
        val widthMm = camera.physicalSizeWidthMm?.takeIf { it > 0f } ?: return null
        val heightMm = camera.physicalSizeHeightMm?.takeIf { it > 0f } ?: return null
        // Physical size covers the full pixel array; fall back to the active array if it is missing.
        val pixelsWide = (camera.pixelArrayWidth ?: active.width).toDouble()
        val pixelsHigh = (camera.pixelArrayHeight ?: active.height).toDouble()
        return Pinhole(
            fx = focalMm / widthMm * pixelsWide,
            fy = focalMm / heightMm * pixelsHigh,
            cx = active.width / 2.0,
            cy = active.height / 2.0,
            source = IntrinsicsSource.FOCAL_LENGTH_ESTIMATE,
        )
    }

    /**
     * An affine map that keeps the axes aligned: a scale and offset per axis, possibly swapping or
     * flipping them (some devices report a rotated or mirrored transform). Anything else -- a
     * perspective term, a shear -- is not a camera crop, and is refused.
     */
    private class Affine(private val m: List<Double>) {
        fun apply(p: Pinhole): Pinhole? {
            val a = m[0]; val b = m[1]; val c = m[3]; val d = m[4]
            val cx = a * p.cx + b * p.cy + m[2]
            val cy = c * p.cx + d * p.cy + m[5]
            return when {
                abs(b) < EPS && abs(c) < EPS -> Pinhole(abs(a) * p.fx, abs(d) * p.fy, cx, cy, p.source)
                abs(a) < EPS && abs(d) < EPS -> Pinhole(abs(b) * p.fy, abs(c) * p.fx, cx, cy, p.source)
                else -> null
            }
        }

        companion object {
            private const val EPS = 1e-6

            fun of(values: List<Float>): Affine? {
                if (values.size != 9) return null
                val m = values.map(Float::toDouble)
                if (abs(m[6]) > EPS || abs(m[7]) > EPS || abs(m[8] - 1.0) > EPS) return null
                return Affine(m)
            }

            /** CameraX's default for analysis: scale the active array to cover the buffer, centred. */
            fun centredFill(active: SensorRect, width: Int, height: Int): Affine? {
                if (active.width <= 0 || active.height <= 0 || width <= 0 || height <= 0) return null
                val scale = maxOf(width.toDouble() / active.width, height.toDouble() / active.height)
                val ox = (width - active.width * scale) / 2
                val oy = (height - active.height * scale) / 2
                return Affine(listOf(scale, 0.0, ox, 0.0, scale, oy, 0.0, 0.0, 1.0))
            }
        }
    }

    /** Rotates buffer intrinsics clockwise by [rotation] degrees, as the pipeline rotates the frame upright. */
    private fun rotateUpright(p: Pinhole, width: Int, height: Int, rotation: Int): CameraIntrinsics? {
        val (fx, fy, cx, cy, w, h) = when (((rotation % 360) + 360) % 360) {
            0 -> Rotated(p.fx, p.fy, p.cx, p.cy, width, height)
            90 -> Rotated(p.fy, p.fx, height - p.cy, p.cx, height, width)
            180 -> Rotated(p.fx, p.fy, width - p.cx, height - p.cy, width, height)
            270 -> Rotated(p.fy, p.fx, p.cy, width - p.cx, height, width)
            else -> return null
        }
        return CameraIntrinsics(fx.toFloat(), fy.toFloat(), cx.toFloat(), cy.toFloat(), w, h, p.source)
    }

    private data class Rotated(val fx: Double, val fy: Double, val cx: Double, val cy: Double, val width: Int, val height: Int)
}
