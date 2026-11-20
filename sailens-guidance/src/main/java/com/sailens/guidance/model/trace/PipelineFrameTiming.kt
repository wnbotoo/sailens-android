package com.sailens.guidance.model.trace

import android.os.SystemClock
import com.sailens.core.frame.ImageFrame
import com.sailens.guidance.geometry.CameraClock
import com.sailens.vision.ModelRunTiming

/** Timings for one dequeued Guidance frame, including frames that fail before a decision. */
class PipelineFrameTiming internal constructor(
    val sessionId: String,
    frame: ImageFrame,
    val dequeuedNanos: Long,
) {
    val frameId: Long = frame.sequenceNumber
    val cameraTimestampNanos: Long = frame.timestamp
    val width: Int = frame.width
    val height: Int = frame.height
    val rotationDegrees: Int = frame.rotationDegrees
    val receivedNanos: Long? = frame.receivedElapsedRealtimeNanos.takeIf { it > 0L }
    val ingress = frame.ingressTiming

    internal var cameraClock: CameraClock? = null
    internal var perceptionStartedNanos: Long? = null
    internal var perceptionFinishedNanos: Long? = null
    internal var decisionStartedNanos: Long? = null
    internal var decidedNanos: Long? = null
    internal var releasedNanos: Long? = null
    internal var semanticRun: ModelRunTiming? = null
    internal var detectionRun: ModelRunTiming? = null
    internal var semanticSource: PipelineSourceFrame? = null
    internal var detectionSource: PipelineSourceFrame? = null
    internal var preferredPreprocessReserved: Boolean = false

    internal fun source(): PipelineSourceFrame = PipelineSourceFrame(
        frameId, cameraTimestampNanos, receivedNanos, cameraClock,
    )

    internal fun now(): Long = SystemClock.elapsedRealtimeNanos()
}

/** Source of the current semantic analysis or the last adopted detection measurement. */
internal data class PipelineSourceFrame(
    val frameId: Long,
    val cameraTimestampNanos: Long,
    val receivedNanos: Long?,
    val cameraClock: CameraClock?,
)
