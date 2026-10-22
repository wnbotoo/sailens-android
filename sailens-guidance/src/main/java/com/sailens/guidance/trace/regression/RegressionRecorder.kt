package com.sailens.guidance.trace.regression

import com.sailens.core.frame.ImageFrame
import com.sailens.core.mask.BinaryMask
import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.model.analysis.ConnectivityStats
import com.sailens.guidance.model.perception.ObstacleDetection
import com.sailens.guidance.model.perception.SegmentationOutput
import com.sailens.guidance.model.scene.SceneEvent
import com.sailens.guidance.processor.analysis.FrameQualityResult
import com.sailens.guidance.trace.capture.RegressionUtteranceRecord
import java.util.UUID

/**
 * Observes the decision path for the model-regression record (M0b). Every hook is an observation:
 * it must not change, delay or fail the path it watches. The capture side (debug only) implements
 * it; everywhere else it is [None], and callers check [isRecording] before doing any work for it.
 *
 * Calls for one frame come between [beginFrame] and [endFrame] on the pipeline's own coroutine,
 * except [onSemantic] and [onDetection], which the model dispatchers make; [onRevoke] comes from the
 * output side at any time. Implementations hold their per-frame state accordingly.
 */
interface RegressionRecorder {
    /** False when nothing is recorded; then no hook needs calling. */
    val isRecording: Boolean

    fun beginFrame(frame: ImageFrame) {}

    /** The camera geometry the frame was processed with (M3o), which the ground-contact distance reads. */
    fun onGeometry(geometry: CameraGeometry) {}

    /** A value one of the injected clocks returned ([com.sailens.guidance.trace.capture.RegressionClocks]). */
    fun onClock(component: String, value: Long) {}

    /**
     * The semantic stage of [frame]: the repository's output when the model ran ([ran]), the cached
     * analysis reused otherwise, or [failure]. The output's class map is only valid during the call.
     */
    fun onSemantic(frame: ImageFrame, ran: Boolean, output: SegmentationOutput?, failure: Throwable?) {}

    /** The detector's output on [frame] as Guidance's provider returned it, when the detector ran. */
    fun onDetection(frame: ImageFrame, detections: List<ObstacleDetection>?, failure: Throwable?) {}

    /** One call to the connectivity statistics: its input, and the native kernel's answer (null = none). */
    fun onConnectivity(passableMask: BinaryMask, stats: ConnectivityStats?) {}

    fun onFrameQuality(result: FrameQualityResult) {}

    /** The motion state the decision step read. */
    fun onStationary(stationary: Boolean) {}

    /** The decided events of [frame], highest priority first, or the [failure] that stopped it. */
    fun endFrame(frame: ImageFrame, events: List<SceneEvent>, failure: Throwable?) {}

    /** A revoked cooldown entry, applied after [decisionsBefore] decisions. */
    fun onRevoke(eventId: UUID, decisionsBefore: Long) {}

    /**
     * A speech-lifecycle event of an utterance submitted in the session [sessionId] (det-first plan
     * D1.3). Comes from any thread, the speech engine's included; must not block. Recorded only into
     * that very session: a late event of an ended session is not written into the next one.
     */
    fun onUtterance(sessionId: String?, record: RegressionUtteranceRecord) {}

    /** Records nothing. */
    object None : RegressionRecorder {
        override val isRecording: Boolean = false
    }
}
