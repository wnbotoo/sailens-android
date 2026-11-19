package com.sailens.guidance.sim

import com.sailens.camera.AnalysisBindingArbiter
import com.sailens.core.frame.ImageFrame
import com.sailens.guidance.geometry.CameraFacts
import com.sailens.guidance.geometry.Vec3
import com.sailens.guidance.model.scene.SceneResult
import com.sailens.guidance.safety.CapabilityStatus
import com.sailens.guidance.world.DepthRejection
import com.sailens.guidance.world.AnalysisActivity
import com.sailens.guidance.world.AnalysisGrant
import com.sailens.guidance.world.CameraVector
import com.sailens.guidance.world.LocalWorldOwner
import com.sailens.guidance.world.WorldAnalysisSource
import com.sailens.guidance.world.WorldFrameAttestation
import com.sailens.guidance.world.WorldImageProof
import com.sailens.runtime.InputImageMapping
import com.sailens.vision.depth.MeasurementKey
import com.sailens.vision.semantic.SemanticContentRegion
import com.sailens.vision.semantic.SemanticImageMapping
import java.util.concurrent.ConcurrentHashMap

/** Source for the upright pinhole images the simulator actually renders. */
class SimulatedWorldAnalysis internal constructor(
    private val camera: SyntheticCamera,
    private val cameraFacts: CameraFacts,
    private val now: () -> Long,
) {
    private val deliveries = ConcurrentHashMap<Long, WorldFrameAttestation>()
    private val bindingGrants = ConcurrentHashMap<AnalysisBindingArbiter.Grant, AnalysisGrant>()
    private val origin = WorldImageProof.OpticalCentre(Any(), CameraVector(0.0, 0.0, 0.0))
    private val axes = listOf(Vec3(1f, 0f, 0f), Vec3(0f, -1f, 0f), Vec3(0f, 0f, -1f))
    private val width = camera.bufferHeight
    private val height = camera.bufferWidth
    private val k = WorldImageProof.Pinhole(
        width, height, camera.focal * width / camera.mapWidth, camera.focal * height / camera.mapHeight,
        camera.cx * width / camera.mapWidth, camera.cy * height / camera.mapHeight, 0f,
    )
    @Volatile private var activity: AnalysisActivity? = null
    @Volatile private var callbackGrant: AnalysisGrant? = null
    @Volatile private var hostStarted: Boolean = false

    fun openOwner(): LocalWorldOwner {
        val ownedActivity = AnalysisActivity(now) { hostStarted }
        activity = ownedActivity
        hostStarted = true
        callbackGrant = ownedActivity.grantAfterActivation()
        return owner { ownedActivity.currentGrant().takeIf { activity === ownedActivity } }
    }

    fun openOwner(request: AnalysisBindingArbiter.Request): LocalWorldOwner = owner {
        request.currentGrant()?.let(::bindGrant)
    }

    fun deliver(frame: ImageFrame, captureGrant: AnalysisBindingArbiter.Grant?) {
        deliver(frame, captureGrant?.let(::bindGrant))
    }

    private fun bindGrant(key: AnalysisBindingArbiter.Grant): AnalysisGrant =
        bindingGrants.computeIfAbsent(key) { AnalysisGrant(it.openedAtNanos, it.generation) }

    private fun owner(grant: () -> AnalysisGrant?): LocalWorldOwner {
        val source = object : WorldAnalysisSource {
            override fun currentGrant() = grant()
            override fun evidenceFor(frame: ImageFrame) = deliveries[frame.sequenceNumber]
        }
        return LocalWorldOwner(source, now)
    }

    fun stopAnalysis(invalidate: () -> Unit = {}) {
        val owned = activity
        val grant = callbackGrant
        if (owned != null && grant != null) {
            owned.revokeBeforeInvalidating(grant) {
                hostStarted = false
                callbackGrant = null
                invalidate()
            }
        }
    }

    fun reopenAnalysis() {
        hostStarted = true
        callbackGrant = activity?.grantAfterActivation()
    }

    fun rejectedAvailableFit(result: SceneResult): MeasurementKey? {
        if (result.depthReading.status != CapabilityStatus.Available || result.depthReading.observation?.isValid != true) {
            return null
        }
        return result.worldPublication?.built?.depth?.takeIf { DepthRejection.SUPPORT_UNPROVEN in it.reasons }?.key
    }

    internal fun deliver(frame: ImageFrame) = deliver(frame, callbackGrant)

    private fun deliver(frame: ImageFrame, grant: AnalysisGrant?) {
        if (frame.rotationDegrees != 90 || camera.sensorOrientation != 90 ||
            frame.width != height || frame.height != width
        ) return
        deliveries[frame.sequenceNumber] = WorldFrameAttestation(
            frame, grant, WorldImageProof(frame, cameraFacts, true, k, true, axes, origin, origin),
        )
    }

    internal fun detectionMapping(frame: ImageFrame): InputImageMapping = InputImageMapping(
        width, height, width, height, InputImageMapping.Axis(1.0, 0.0, 0, width),
        InputImageMapping.Axis(1.0, 0.0, 0, height), frame.rotationDegrees,
    )

    internal fun semanticMapping(frame: ImageFrame): SemanticImageMapping = SemanticImageMapping(
        detectionMapping(frame), camera.mapWidth, camera.mapHeight,
        SemanticContentRegion(0, 0, camera.mapWidth, camera.mapHeight),
    )
}
