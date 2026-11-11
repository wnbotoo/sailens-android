package com.sailens.guidance.trace

import com.sailens.guidance.model.trace.DepthRunTrace
import com.sailens.guidance.model.trace.FrameTrace
import com.sailens.guidance.model.trace.GuidanceAlertTrace
import com.sailens.guidance.model.trace.PromptOutcomeTrace
import com.sailens.guidance.model.trace.SafetyStateTrace
import com.sailens.guidance.model.trace.SessionTraceMetadata
import com.sailens.guidance.model.trace.SessionTraceSummary
import org.json.JSONArray
import org.json.JSONObject

internal object TraceJsonEncoder {
    fun encodeSessionStart(metadata: SessionTraceMetadata): JSONObject = JSONObject().apply {
        put("type", "session_start")
        put("sessionId", metadata.sessionId)
        put("startedAt", metadata.startedAt)
        put("pipelineMode", metadata.pipelineMode)
        put("targetHardwareProfile", metadata.targetHardwareProfile)
        metadata.openedElapsedMs?.let { put("openedElapsedMs", it) }
        if (metadata.safetyTrace) put("safetyTrace", true)
        if (metadata.holdTrace) put("holdTrace", true)
        if (metadata.depthTrace) put("depthTrace", true)
    }

    fun encodeDepthRun(record: DepthRunTrace): JSONObject = JSONObject().apply {
        put("type", "depth_run")
        put("sessionId", record.sessionId)
        put("seq", record.seq)
        put("generation", record.generation)
        put("runId", record.runId)
        put("outcome", record.outcome)
        put("atElapsedMs", record.atElapsedMs)
        record.frameSequence?.let { put("frameSequence", it) }
        record.frameTimeNanos?.let { put("frameTimeNanos", it) }
        record.timeBasis?.let { put("timeBasis", it) }
        record.geometryQuality?.let { put("geometryQuality", it) }
        record.validity?.let { put("validity", it) }
        record.inlierRatio?.let { put("inlierRatio", it.toDouble()) }
        record.confidence?.let { put("confidence", it.toDouble()) }
        record.planeAgreesWithGravity?.let { put("planeAgreesWithGravity", it) }
        record.groundRatio?.let { put("groundRatio", it.toDouble()) }
        record.aboveGroundRatio?.let { put("aboveGroundRatio", it.toDouble()) }
        record.belowGroundRatio?.let { put("belowGroundRatio", it.toDouble()) }
        record.disparityScale?.let { put("disparityScale", it.toDouble()) }
        record.disparityShift?.let { put("disparityShift", it.toDouble()) }
        record.detail?.let { put("detail", it.toDouble()) }
        record.prepareMs?.let { put("prepareMs", it) }
        record.admitWaitMs?.let { put("admitWaitMs", it) }
        record.inferMs?.let { put("inferMs", it) }
        record.outputReadMs?.let { put("outputReadMs", it) }
        record.fitMs?.let { put("fitMs", it) }
        record.accelerator?.let { put("accelerator", it) }
        record.acceleratorSelection?.let { put("acceleratorSelection", it) }
        record.preprocessBackend?.let { put("preprocessBackend", it) }
        record.postprocessBackend?.let { put("postprocessBackend", it) }
        record.modelShape?.let { put("modelShape", it) }
        if (record.skipsSinceLast.isNotEmpty()) {
            put("skipsSinceLast", JSONObject().apply { record.skipsSinceLast.forEach { (k, v) -> put(k, v) } })
        }
        record.cancelledRunId?.let { put("cancelledRunId", it) }
        record.cancelledStage?.let { put("cancelledStage", it) }
        record.failuresTotal?.let { put("failuresTotal", it) }
        record.finalState?.let { put("finalState", it) }
    }

    fun encodeSafetyState(state: SafetyStateTrace): JSONObject = JSONObject().apply {
        put("type", "safety_state")
        put("sessionId", state.sessionId)
        put("seq", state.seq)
        put("at", state.at)
        put("elapsedMs", state.elapsedMs)
        put("stateSinceElapsedMs", state.stateSinceElapsedMs)
        put("state", state.state)
        put("reasons", JSONArray(state.reasons))
        put("phase", state.phase)
        put("interrupted", state.interrupted)
        put("stall", state.stall)
        state.judgement?.let {
            put("judgement", it)
            put("judgementReasons", JSONArray(state.judgementReasons))
        }
        put("outputFaults", JSONArray(state.outputFaults))
        put("expectedNavigationChannel", state.expectedNavigationChannel)
        put("cause", state.cause)
        state.hold?.let { hold ->
            put(
                "hold",
                JSONObject().apply {
                    put("cause", hold.cause)
                    put("sinceElapsedMs", hold.sinceElapsedMs)
                    put("stopping", hold.stopping)
                    put("freshResults", hold.freshResults)
                    put("freshSpanMs", hold.freshSpanMs)
                    hold.firstFreshReceivedElapsedMs?.let { put("firstFreshReceivedElapsedMs", it) }
                    hold.lastFreshReceivedElapsedMs?.let { put("lastFreshReceivedElapsedMs", it) }
                    put("receiptFallbacks", hold.receiptFallbacks)
                    hold.lastFreshSequenceNumber?.let { put("lastFreshSequenceNumber", it) }
                },
            )
        }
        state.holdReleased?.let { release ->
            put(
                "holdReleased",
                JSONObject().apply {
                    put("cause", release.cause)
                    put("sinceElapsedMs", release.sinceElapsedMs)
                    put("freshResults", release.freshResults)
                    put("firstFreshReceivedElapsedMs", release.firstFreshReceivedElapsedMs)
                    put("lastFreshReceivedElapsedMs", release.lastFreshReceivedElapsedMs)
                    put("freshSpanMs", release.freshSpanMs)
                    put("receiptFallbacks", release.receiptFallbacks)
                    put("sourceSequenceNumber", release.sourceSequenceNumber)
                },
            )
        }
    }

    fun encodeFrame(frameTrace: FrameTrace): JSONObject = JSONObject().apply {
        put("type", "frame")
        put("sessionId", frameTrace.sessionId)
        put("sequenceNumber", frameTrace.sequenceNumber)
        put("frameTimestamp", frameTrace.frameTimestamp)
        put("frameWidth", frameTrace.frameWidth)
        put("frameHeight", frameTrace.frameHeight)
        put("droppedFramesSinceLast", frameTrace.droppedFramesSinceLast)
        put("processFrameMs", frameTrace.processFrameMs)
        put("inferenceMs", frameTrace.inferenceMs)
        put("analyzeSceneMs", frameTrace.analyzeSceneMs)
        put("decideEventsMs", frameTrace.decideEventsMs)
        put("totalPipelineMs", frameTrace.totalPipelineMs)
        put("pipelineStartedAt", frameTrace.pipelineStartedAt)
        put("pipelineCompletedAt", frameTrace.pipelineCompletedAt)
        put("cameraFrameIntervalMs", frameTrace.cameraFrameIntervalMs)
        put("pipelineOutputIntervalMs", frameTrace.pipelineOutputIntervalMs)
        put("obstacleCount", frameTrace.obstacleCount)
        put("eventCount", frameTrace.eventCount)
        put("isBlocked", frameTrace.isBlocked)
        put("isNarrowing", frameTrace.isNarrowing)
        put("isRoadDangerous", frameTrace.isRoadDangerous)
        put("navigationPassableRatio", frameTrace.navigationPassableRatio)
        put("navigationPassableDelta", frameTrace.navigationPassableDelta)
        put("roadRatio", frameTrace.roadRatio)
        put("roadRatioDelta", frameTrace.roadRatioDelta)
        put("blockageConfidence", frameTrace.blockageConfidence)
        put("blockageConfidenceDelta", frameTrace.blockageConfidenceDelta)
        put("blockageReason", frameTrace.blockageReason)
        put("verticalReachRatio", frameTrace.verticalReachRatio)
        put("verticalReachDelta", frameTrace.verticalReachDelta)
        put("floodReachRatio", frameTrace.floodReachRatio)
        put("floodReachDelta", frameTrace.floodReachDelta)
        put("widthRetentionP25", frameTrace.widthRetentionP25)
        put("widthRetentionP25Delta", frameTrace.widthRetentionP25Delta)
        put("occludedPassableRatio", frameTrace.occludedPassableRatio)
        put("occludedPassableDelta", frameTrace.occludedPassableDelta)
        put("groundRecognition", frameTrace.groundRecognition)
        put("unrecognizedGroundRatio", frameTrace.unrecognizedGroundRatio)
        put("rawObstacleDetectionCount", frameTrace.rawObstacleDetectionCount)
        put("obstacleCountDelta", frameTrace.obstacleCountDelta)
        put("rawObstacleDetectionCountDelta", frameTrace.rawObstacleDetectionCountDelta)
        put("rawObstacleClassNames", JSONArray(frameTrace.rawObstacleClassNames))
        put("trackedObstacleCategories", JSONArray(frameTrace.trackedObstacleCategories))
        put("roadVehicleSource", frameTrace.roadVehicleSource)
        put("roadVehicleConfidence", frameTrace.roadVehicleConfidence)
        put("roadVehicleReason", frameTrace.roadVehicleReason)
        put("roadVehicleBottomY", frameTrace.roadVehicleBottomY)
        put("roadVehicleCenterBandOverlap", frameTrace.roadVehicleCenterBandOverlap)
        put("roadVehicleAreaRatio", frameTrace.roadVehicleAreaRatio)
        put("dominantClasses", JSONArray(frameTrace.dominantClasses))
        put("dominantClassPercentages", JSONArray(frameTrace.dominantClassPercentages))
        put("messageKeys", JSONArray(frameTrace.messageKeys))
        put("semanticPreprocessMs", frameTrace.semanticPreprocessMs)
        put("semanticInferenceMs", frameTrace.semanticInferenceMs)
        put("semanticOutputReadMs", frameTrace.semanticOutputReadMs)
        put("semanticPostprocessMs", frameTrace.semanticPostprocessMs)
        put("semanticAccelerator", frameTrace.semanticAccelerator)
        put("semanticAcceleratorSelection", frameTrace.semanticAcceleratorSelection)
        put("semanticPreprocessBackend", frameTrace.semanticPreprocessBackend)
        put("semanticPostprocessBackend", frameTrace.semanticPostprocessBackend)
        put("obstaclePreprocessMs", frameTrace.obstaclePreprocessMs)
        put("obstacleInferenceMs", frameTrace.obstacleInferenceMs)
        put("obstacleOutputReadMs", frameTrace.obstacleOutputReadMs)
        put("obstaclePostprocessMs", frameTrace.obstaclePostprocessMs)
        put("obstacleRunKind", frameTrace.obstacleRunKind)
        put("obstacleAccelerator", frameTrace.obstacleAccelerator)
        put("obstacleAcceleratorSelection", frameTrace.obstacleAcceleratorSelection)
        put("obstaclePreprocessBackend", frameTrace.obstaclePreprocessBackend)
        put("obstaclePostprocessBackend", frameTrace.obstaclePostprocessBackend)
        put("geometryQuality", frameTrace.geometryQuality)
        put("geometryIssues", JSONArray(frameTrace.geometryIssues))
        put("geometryTimeBasis", frameTrace.geometryTimeBasis)
        frameTrace.gravityAgeMs?.let { put("gravityAgeMs", it) }
        frameTrace.pitchDownDeg?.let { put("pitchDownDeg", it) }
        frameTrace.rollDeg?.let { put("rollDeg", it) }
        put("intrinsicsSource", frameTrace.intrinsicsSource)
        frameTrace.focalPx?.let { put("focalPx", it) }
        frameTrace.headingDeg?.let { put("headingDeg", it) }
        frameTrace.yawRateDegPerS?.let { put("yawRateDegPerS", it) }
        put("orientationQuality", frameTrace.orientationQuality)
        frameTrace.phoneHeightM?.let { put("phoneHeightM", it) }
        put("heightSource", frameTrace.heightSource)
        put("groundContactEnabled", frameTrace.groundContactEnabled)
        put(
            "obstacleDistances",
            JSONArray(
                frameTrace.obstacleDistances.map { distance ->
                    JSONObject().apply {
                        put("category", distance.category)
                        put("zone", distance.zone)
                        put("contactValidity", distance.contactValidity)
                        distance.lowerBoundM?.let { put("lowerBoundM", it) }
                        distance.pointM?.let { put("pointM", it) }
                        distance.upperBoundM?.let { put("upperBoundM", it) }
                        put("levelToday", distance.levelToday)
                        distance.levelNew?.let { put("levelNew", it) }
                        put("levelUsed", distance.levelUsed)
                    }
                },
            ),
        )
        frameTrace.frameQuality?.let { put("frameQuality", it) }
        frameTrace.frameQualityClassified?.let { put("frameQualityClassified", it) }
        frameTrace.qualification?.let {
            put("qualification", it)
            put("qualificationReasons", JSONArray(frameTrace.qualificationReasons))
        }
        if (frameTrace.capabilities.isNotEmpty()) {
            put(
                "capabilities",
                JSONObject().apply {
                    frameTrace.capabilities.forEach { (name, capability) ->
                        put(
                            name,
                            JSONObject().apply {
                                put("status", capability.status)
                                put("issues", JSONArray(capability.issues))
                            },
                        )
                    }
                },
            )
        }
        frameTrace.receiptToDecisionMs?.let { put("receiptToDecisionMs", it) }
        frameTrace.receivedElapsedMs?.let { put("receivedElapsedMs", it) }
        frameTrace.depthRunId?.let { put("depthRunId", it) }
        frameTrace.depthAgeMs?.let { put("depthAgeMs", it) }
    }

    fun encodeOverlayRender(
        sessionId: String,
        renderedAt: Long,
        renderMs: Long,
        overlayMode: String,
        bitmapRendered: Boolean,
        sourceSequenceNumber: Long,
        sourcePipelineCompletedAt: Long,
        sourceAgeMs: Long,
    ): JSONObject = JSONObject().apply {
        put("type", "overlay_render")
        put("sessionId", sessionId)
        put("renderedAt", renderedAt)
        put("renderMs", renderMs)
        put("overlayMode", overlayMode)
        put("bitmapRendered", bitmapRendered)
        put("sourceSequenceNumber", sourceSequenceNumber)
        put("sourcePipelineCompletedAt", sourcePipelineCompletedAt)
        put("sourceAgeMs", sourceAgeMs)
    }

    fun encodePromptOutcome(outcome: PromptOutcomeTrace): JSONObject = JSONObject().apply {
        put("type", "prompt_outcome")
        put("sessionId", outcome.sessionId)
        put("eventId", outcome.eventId)
        put("sourceSequenceNumber", outcome.sourceSequenceNumber)
        put("messageKey", outcome.messageKey)
        put("category", outcome.category)
        put("priority", outcome.priority)
        outcome.deliveredAt?.let { put("deliveredAt", it) }
        outcome.revokedAt?.let { put("revokedAt", it) }
        outcome.revokeReason?.let { put("revokeReason", it) }
        put("deliveredVia", JSONArray(outcome.deliveredVia))
        put("speechEnabled", outcome.speechEnabled)
        put("screenReaderActive", outcome.screenReaderActive)
        put("hapticsEnabled", outcome.hapticsEnabled)
    }

    fun encodeGuidanceAlert(alert: GuidanceAlertTrace): JSONObject = JSONObject().apply {
        put("type", "guidance_alert")
        put("sessionId", alert.sessionId)
        put("seq", alert.seq)
        put("at", alert.at)
        put("elapsedMs", alert.elapsedMs)
        put("source", alert.source)
        put("kind", alert.kind)
        alert.messageKey?.let { put("messageKey", it) }
        alert.haptic?.let { put("haptic", it) }
        put("priority", alert.priority)
        put("channel", alert.channel)
        put("result", alert.result)
    }

    fun encodeSessionSummary(summary: SessionTraceSummary): JSONObject = JSONObject().apply {
        put("type", "session_summary")
        put("sessionId", summary.sessionId)
        put("startedAt", summary.startedAt)
        put("completedAt", summary.completedAt)
        put("totalFrames", summary.totalFrames)
        put("droppedFrames", summary.droppedFrames)
        put("totalEvents", summary.totalEvents)
        put("blockedFrames", summary.blockedFrames)
        put("dangerousFrames", summary.dangerousFrames)
        put("avgProcessFrameMs", summary.avgProcessFrameMs)
        put("avgTotalPipelineMs", summary.avgTotalPipelineMs)
        put("avgInferenceMs", summary.avgInferenceMs)
        put("p95TotalPipelineMs", summary.p95TotalPipelineMs)
        put("maxTotalPipelineMs", summary.maxTotalPipelineMs)
        summary.closedElapsedMs?.let { put("closedElapsedMs", it) }
        summary.guidanceAlertCount?.let { put("guidanceAlertCount", it) }
        summary.safetyStateCount?.let { put("safetyStateCount", it) }
        summary.depthRunCount?.let { put("depthRunCount", it) }
    }

    fun encodeError(sessionId: String, stage: String, throwable: Throwable): JSONObject = JSONObject().apply {
        put("type", "error")
        put("sessionId", sessionId)
        put("stage", stage)
        put("exception", throwable.javaClass.simpleName)
        put("message", throwable.message ?: "")
        put("stackTrace", throwable.stackTraceToString().take(1000))
    }
}
