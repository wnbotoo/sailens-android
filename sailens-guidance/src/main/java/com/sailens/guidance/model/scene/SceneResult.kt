package com.sailens.guidance.model.scene

import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.model.analysis.VisionFaults
import com.sailens.guidance.motion.MotionState
import com.sailens.guidance.safety.GuidanceQualification
import com.sailens.core.mask.BinaryMask
import com.sailens.guidance.model.perception.ObstacleDetection
import com.sailens.guidance.model.perception.DetectedObstacle
import com.sailens.guidance.model.perception.SegmentationMask

data class SceneResult(
    val sequenceNumber: Long = 0L,
    val pipelineCompletedAt: Long = 0L,
    val frameDisplayWidth: Int? = null,
    val frameDisplayHeight: Int? = null,
    val passableMask: BinaryMask?,
    val segmentationMask: SegmentationMask? = null,
    val obstacles: List<DetectedObstacle> = emptyList(),
    val obstacleDetections: List<ObstacleDetection> = emptyList(),
    val debugInfo: SceneDebugInfo? = null,
    val events: List<SceneEvent>,
    /** The camera's geometry and the phone's rotation for this frame. Nothing decides on them yet. */
    val cameraGeometry: CameraGeometry? = null,
    val motion: MotionState? = null,
    /** What Guidance cannot judge in this frame; the shell tells the user. */
    val visionFaults: VisionFaults = VisionFaults.NONE,
    /** Whether this frame let Guidance judge the way ahead, and how the optional inputs stood. Observed, not acted on. */
    val qualification: GuidanceQualification = GuidanceQualification.QUALIFIED,
    /**
     * When the frame source received the frame, elapsed-realtime milliseconds; null when it did not record it. The
     * safety state judges by it whether a result is fresh after a stop; the frame trace records the same value.
     */
    val frameReceivedAtMs: Long? = null,
)
