package com.friady.sailens.domain.usecase.trace

import com.friady.sailens.domain.model.trace.SessionTraceAccumulator
import com.friady.sailens.domain.model.trace.SessionTraceSummary
import com.friady.sailens.domain.model.trace.TraceReplayParser
import com.friady.sailens.domain.model.trace.TraceReplayReport
import com.friady.sailens.domain.model.trace.TraceReplaySession

class BuildTraceReplayReportUseCase {
    operator fun invoke(lines: List<String>): TraceReplayReport = invoke(TraceReplayParser.parse(lines))

    operator fun invoke(session: TraceReplaySession): TraceReplayReport {
        val metadata = session.metadata
        val summary = session.summary ?: buildSummaryFromFrames(session)
        val totalFrames = summary.totalFrames
        val totalObservedFrames = totalFrames + summary.droppedFrames
        val droppedFrameRate = if (totalObservedFrames > 0) {
            summary.droppedFrames.toDouble() / totalObservedFrames
        } else {
            0.0
        }
        val frames = session.frames
        val avgNavigationPassableRatio = frames.averageOrZero { it.navigationPassableRatio }
        val avgBlockageConfidence = frames.averageOrZero { it.blockageConfidence }
        val avgVerticalReachRatio = frames.averageOrZero { it.verticalReachRatio }
        val avgFloodReachRatio = frames.averageOrZero { it.floodReachRatio }
        val avgWidthRetentionP25 = frames.averageOrZero { it.widthRetentionP25 }

        return TraceReplayReport(
            sessionId = metadata?.sessionId ?: summary.sessionId,
            pipelineMode = metadata?.pipelineMode,
            targetHardwareProfile = metadata?.targetHardwareProfile,
            totalFrames = totalFrames,
            droppedFrames = summary.droppedFrames,
            totalObservedFrames = totalObservedFrames,
            droppedFrameRate = droppedFrameRate,
            totalEvents = summary.totalEvents,
            blockedFrames = summary.blockedFrames,
            dangerousFrames = summary.dangerousFrames,
            blockedFrameRate = if (totalFrames > 0) summary.blockedFrames.toDouble() / totalFrames else 0.0,
            dangerousFrameRate = if (totalFrames > 0) summary.dangerousFrames.toDouble() / totalFrames else 0.0,
            avgProcessFrameMs = summary.avgProcessFrameMs,
            avgInferenceMs = summary.avgInferenceMs,
            avgSemanticPreprocessMs = frames.averageOrZero { it.semanticPreprocessMs.toDouble() },
            avgSemanticInferenceMs = frames.averageOrZero { it.semanticInferenceMs.toDouble() },
            avgSemanticOutputReadMs = frames.averageOrZero { it.semanticOutputReadMs.toDouble() },
            avgSemanticPostprocessMs = frames.averageOrZero { it.semanticPostprocessMs.toDouble() },
            avgInstancePreprocessMs = frames.averageOrZero { it.instancePreprocessMs.toDouble() },
            avgInstanceInferenceMs = frames.averageOrZero { it.instanceInferenceMs.toDouble() },
            avgInstanceOutputReadMs = frames.averageOrZero { it.instanceOutputReadMs.toDouble() },
            avgInstancePostprocessMs = frames.averageOrZero { it.instancePostprocessMs.toDouble() },
            avgTotalPipelineMs = summary.avgTotalPipelineMs,
            p95TotalPipelineMs = summary.p95TotalPipelineMs,
            maxTotalPipelineMs = summary.maxTotalPipelineMs,
            avgNavigationPassableRatio = avgNavigationPassableRatio,
            avgBlockageConfidence = avgBlockageConfidence,
            avgVerticalReachRatio = avgVerticalReachRatio,
            avgFloodReachRatio = avgFloodReachRatio,
            avgWidthRetentionP25 = avgWidthRetentionP25,
            maxDroppedFramesSinceLast = session.frames.maxOfOrNull { it.droppedFramesSinceLast } ?: 0,
            errorCount = session.errors.size,
            uniqueMessageKeys = session.frames
                .flatMap { it.messageKeys }
                .distinct()
                .sorted(),
        )
    }

    private fun buildSummaryFromFrames(session: TraceReplaySession): SessionTraceSummary {
        val startedAt = session.metadata?.startedAt ?: session.frames.firstOrNull()?.frameTimestamp ?: 0L
        val sessionId = session.metadata?.sessionId ?: session.frames.firstOrNull()?.sessionId ?: "unknown-session"
        val completedAt = session.frames.lastOrNull()?.frameTimestamp ?: startedAt

        return SessionTraceAccumulator(
            sessionId = sessionId,
            startedAt = startedAt,
        ).also { accumulator ->
            session.frames.forEach(accumulator::record)
        }.build(completedAt = completedAt)
    }

    private inline fun <T> List<T>.averageOrZero(selector: (T) -> Double): Double {
        if (isEmpty()) return 0.0
        return sumOf(selector) / size
    }
}

