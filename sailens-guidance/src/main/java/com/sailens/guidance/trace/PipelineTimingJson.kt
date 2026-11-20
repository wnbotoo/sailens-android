package com.sailens.guidance.trace

import com.sailens.guidance.model.trace.PipelineFrameTiming
import com.sailens.guidance.model.trace.PipelineSourceFrame
import com.sailens.vision.ModelRunTiming
import org.json.JSONObject

internal fun encodePipelineTiming(timing: PipelineFrameTiming, seq: Long): JSONObject = JSONObject().apply {
    put("type", "pipeline_timing")
    put("sessionId", timing.sessionId)
    put("seq", seq)
    put("frameId", timing.frameId)
    put("cameraTimestampNanos", timing.cameraTimestampNanos)
    put("width", timing.width)
    put("height", timing.height)
    put("rotationDegrees", timing.rotationDegrees)
    put("cameraClock", timing.cameraClock?.name)
    put("receivedNanos", timing.receivedNanos)
    timing.ingress?.let {
        put("copyStartedNanos", it.copyStartedNanos)
        put("copyFinishedNanos", it.copyFinishedNanos)
        put("offeredNanos", it.offeredNanos)
    }
    put("dequeuedNanos", timing.dequeuedNanos)
    put("perceptionStartedNanos", timing.perceptionStartedNanos)
    put("perceptionFinishedNanos", timing.perceptionFinishedNanos)
    put("decisionStartedNanos", timing.decisionStartedNanos)
    put("decidedNanos", timing.decidedNanos)
    put("releasedNanos", timing.releasedNanos)
    put("sem", timing.semanticRun?.toJson())
    put("det", timing.detectionRun?.toJson())
    put("semSource", timing.semanticSource?.toJson())
    put("detSource", timing.detectionSource?.toJson())
    if (timing.preferredPreprocessReserved) put("preferredPreprocessReserved", true)
}

private fun PipelineSourceFrame.toJson(): JSONObject = JSONObject().apply {
    put("frameId", frameId)
    put("cameraTimestampNanos", cameraTimestampNanos)
    put("receivedNanos", receivedNanos)
    put("cameraClock", cameraClock?.name)
}

private fun ModelRunTiming.toJson(): JSONObject = JSONObject().apply {
    put("queuedNanos", queuedNanos)
    put("runnerStartedNanos", runnerStartedNanos)
    put("preprocessStartedNanos", preprocessStartedNanos)
    put("preprocessFinishedNanos", preprocessFinishedNanos)
    put("preprocessBackend", preprocessBackend)
    put("cacheWaitNanos", input.cacheWaitNanos)
    put("cacheAwaitNanos", input.cacheAwaitNanos)
    put("cacheCopyNanos", input.cacheCopyNanos)
    put("jniArrayGetNanos", input.jniArrayGetNanos)
    put("jniArrayReleaseNanos", input.jniArrayReleaseNanos)
    put("nativeYuvBodyNanos", input.nativeYuvBodyNanos)
    put("nativeResizeFilter", input.nativeResizeFilter?.name)
    put("kotlinHwcToNchwNanos", input.kotlinHwcToNchwNanos)
    input.jniArrayCopyMask?.let { mask ->
        put("jniArrayIsCopy", JSONObject().apply {
            put("y", (mask and 1) != 0)
            put("u", (mask and 2) != 0)
            put("v", (mask and 4) != 0)
            put("output", (mask and 8) != 0)
        })
    }
    put("inferenceStartedNanos", inferenceStartedNanos)
    put("inferenceReturnedNanos", inferenceReturnedNanos)
    put("outputCopyNanos", outputCopyNanos)
    put("outputLockNanos", outputLockNanos)
    put("outputUnlockNanos", outputUnlockNanos)
    put("postprocessFinishedNanos", postprocessFinishedNanos)
    put("postprocessBackend", postprocessBackend)
    semanticScoreTiming?.let { timing ->
        timing.fusedKernelNanos?.let { kernel ->
            put("semanticScorePhases", JSONObject().apply {
                put("arraysGetNanos", timing.arraysGetNanos)
                put("fusedKernelNanos", kernel)
                put("arraysReleaseNanos", timing.arraysReleaseNanos)
                put("kotlinPrepareNanos", timing.kotlinPrepareNanos)
                put("kotlinAssembleNanos", timing.kotlinAssembleNanos)
                if (timing.sampledPixels > 0) {
                    put("sampledPixels", timing.sampledPixels)
                    put("sampledArgmaxNanos", timing.sampledArgmaxNanos)
                    put("sampledMaskWritesNanos", timing.sampledMaskWritesNanos)
                    put("sampledStatisticsNanos", timing.sampledStatisticsNanos)
                    put("sampledClockFloorNanos", timing.sampledClockFloorNanos)
                }
            })
        }
    }
}
