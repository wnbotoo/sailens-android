package com.sailens.guidance.depth.geometry

import com.sailens.core.frame.ImageFrame
import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.GroundObservation
import com.sailens.vision.depth.DepthRun

/**
 * Observes the depth lane for the debug capture's depth evidence stream (docs/field-capture.md §11): for each
 * run, what a PC needs to redo it -- the frame's planes, the geometry the fit used, the model's output and the
 * fit's result. The debug shell implements it; everywhere else it is [None].
 *
 * Observation only. A call is cheap on the lane's thread (one copy of the planes into a pooled buffer), never
 * blocks and never throws into the lane: what the recorder cannot keep it counts as dropped. The lane guards
 * every call as well, so a failing recorder changes nothing the lane does or records.
 */
interface DepthEvidenceRecorder {
    /**
     * While the lane still holds [frame], before it gives the frame back: a copy of its planes for run [runId]
     * of depth session [generation] in the trace session [traceSessionId]. Null when nothing is recorded for
     * that trace session, or when no buffer is free (the recorder then counts the run as dropped).
     */
    fun copy(traceSessionId: String, generation: Long, runId: Long, frame: ImageFrame): DepthEvidenceSlot?

    companion object {
        val None: DepthEvidenceRecorder = object : DepthEvidenceRecorder {
            override fun copy(traceSessionId: String, generation: Long, runId: Long, frame: ImageFrame): DepthEvidenceSlot? = null
        }
    }
}

/** One run's copy of its frame. The lane calls exactly one of [submit] and [discard]. */
interface DepthEvidenceSlot {
    /** The run produced an output: the record is written with it. */
    fun submit(run: DepthEvidenceRun)

    /** The run ended without an output (no input, no admission, a failure): nothing is written. */
    fun discard()
}

/** What a run's record holds besides the frame's planes. */
class DepthEvidenceRun(
    /** The run's `depth_run` outcome (`DepthRunTrace.PUBLISHED`, `DepthRunTrace.NON_FINITE_OUTPUT`). */
    val outcome: String,
    /**
     * Which call of the session's fitter this run made, counted from 0 at the session's start, when the fitter
     * was called. A replay can redo the fitter's history only over an unbroken run of these.
     */
    val fitOrdinal: Long?,
    /** The geometry the run used, as resolved for its frame. */
    val geometry: CameraGeometry,
    val gridWidth: Int,
    val gridHeight: Int,
    /** The model's output; its values array is the runner's own copy and is not written again. */
    val run: DepthRun,
    val observation: GroundObservation?,
)
