package com.sailens.guidance.world

import com.sailens.core.frame.ImageFrame
import com.sailens.guidance.geometry.CameraFacts
import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.FrameFacts
import com.sailens.guidance.geometry.GravityInCamera
import com.sailens.guidance.geometry.Vec3
import java.lang.ref.WeakReference

/** Issued by the producer of the final analysis buffer. Its coordinates are in the upright image. */
internal class WorldImageProof(
    frame: ImageFrame,
    val camera: CameraFacts,
    val isBackCamera: Boolean,
    val model: Pinhole,
    val coversWholeImage: Boolean,
    deviceAxes: List<Vec3>?,
    val sourceCentre: OpticalCentre?,
    val captureCentre: OpticalCentre?,
) {
    private val frame = WeakReference(frame)
    private val calibration = camera.lensIntrinsicCalibration?.toList()
    val deviceAxes = deviceAxes?.toList()

    fun belongsTo(frame: ImageFrame, usedCamera: CameraFacts?): Boolean =
        this.frame.get() === frame && usedCamera === camera && usedCamera.lensIntrinsicCalibration == calibration

    data class Pinhole(
        val width: Int,
        val height: Int,
        val fx: Float,
        val fy: Float,
        val cx: Float,
        val cy: Float,
        val skew: Float,
    )

    /** Position in metres in the same calibrated device reference frame. */
    class OpticalCentre(val reference: Any, val metres: CameraVector)
}

internal enum class SpatialRejection {
    NOMINAL_GEOMETRY,
    AXIS_UNPROVEN,
    CALIBRATION_UNPROVEN,
    PIXEL_DOMAIN_UNPROVEN,
    RAY_AXES_UNPROVEN,
    OPTICAL_CENTRE_UNPROVEN,
    OPTICAL_CENTRE_MISMATCH,
}

internal fun spatialRejection(
    frame: ImageFrame,
    facts: FrameFacts,
    camera: CameraFacts?,
    geometry: CameraGeometry,
    proof: WorldImageProof?,
): SpatialRejection? {
    if (proof == null || !proof.belongsTo(frame, camera)) return SpatialRejection.PIXEL_DOMAIN_UNPROVEN
    val sensorRotation = camera?.sensorOrientationDegrees
    if (!proof.isBackCamera || sensorRotation == null || !sensorRotation.isQuadrant() || !facts.rotationDegrees.isQuadrant()) {
        return SpatialRejection.AXIS_UNPROVEN
    }
    val transform = facts.sensorToBufferTransform
    if (transform == null || transform.size != 9 || !transform.all(Float::isFinite) ||
        transform[0] <= 0f || transform[4] <= 0f || transform[1] != 0f || transform[3] != 0f ||
        transform[6] != 0f || transform[7] != 0f || transform[8] != 1f
    ) return SpatialRejection.AXIS_UNPROVEN

    val calibration = camera.lensIntrinsicCalibration
    if (calibration == null || calibration.size != 5 || !calibration.all(Float::isFinite) ||
        calibration[0] <= 0f || calibration[1] <= 0f || calibration[4] != 0f ||
        proof.model.skew != 0f
    ) return SpatialRejection.CALIBRATION_UNPROVEN

    val k = geometry.intrinsics ?: return SpatialRejection.NOMINAL_GEOMETRY
    val model = proof.model
    val sideways = facts.rotationDegrees == 90 || facts.rotationDegrees == 270
    if (!proof.coversWholeImage || k.width != (if (sideways) facts.height else facts.width) ||
        k.height != (if (sideways) facts.width else facts.height) || model.width != k.width || model.height != k.height ||
        model.fx != k.fx || model.fy != k.fy || model.cx != k.cx || model.cy != k.cy
    ) return SpatialRejection.PIXEL_DOMAIN_UNPROVEN

    val axes = proof.deviceAxes
    if (axes == null || axes.size != 3) return SpatialRejection.RAY_AXES_UNPROVEN
    for (axis in axes.indices) {
        val expected = GravityInCamera.resolve(negativeDeviceUnits[axis], sensorRotation, facts.rotationDegrees)
        val actual = axes[axis]
        if (expected == null || actual.x != expected.x || actual.y != expected.y || actual.z != expected.z) {
            return SpatialRejection.RAY_AXES_UNPROVEN
        }
    }

    val source = proof.sourceCentre ?: return SpatialRejection.OPTICAL_CENTRE_UNPROVEN
    val capture = proof.captureCentre ?: return SpatialRejection.OPTICAL_CENTRE_UNPROVEN
    if (source.reference !== capture.reference || !source.metres.isFinite() || !capture.metres.isFinite()) {
        return SpatialRejection.OPTICAL_CENTRE_UNPROVEN
    }
    if (source.metres.x != capture.metres.x || source.metres.y != capture.metres.y || source.metres.z != capture.metres.z) {
        return SpatialRejection.OPTICAL_CENTRE_MISMATCH
    }
    return null
}

private fun Int.isQuadrant() = this == 0 || this == 90 || this == 180 || this == 270

private fun CameraVector.isFinite() = x.isFinite() && y.isFinite() && z.isFinite()

// GravityInCamera takes the reaction vector. Negating each input samples its map on the device down basis.
private val negativeDeviceUnits = listOf(Vec3(-1f, 0f, 0f), Vec3(0f, -1f, 0f), Vec3(0f, 0f, -1f))
