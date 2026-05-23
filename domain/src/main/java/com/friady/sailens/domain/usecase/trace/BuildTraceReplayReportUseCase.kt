package com.friady.sailens.domain.usecase.trace

import com.friady.sailens.domain.model.trace.FrameTrace
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
        val durationMs = frames.pipelineDurationMs(summary)
        val cameraInputFps = frames.cameraInputFps()
        val pipelineOutputFps = frames.pipelineOutputFps()
        val pipelineThroughputFps = summary.avgInferenceMs.toFps()
        val semanticRunCount = frames.count { it.semanticInferenceMs > 0 }
        val semanticRunFps = semanticRunCount.toFps(durationMs)
        val semanticModelFps = frames.averagePositiveOrZero { it.semanticInferenceMs.toDouble() }.toFps()
        val instanceRunCount = frames.count { it.instanceInferenceMs > 0 }
        val instanceRunFps = instanceRunCount.toFps(durationMs)
        val instanceModelFps = frames.averagePositiveOrZero { it.instanceInferenceMs.toDouble() }.toFps()
        val maskRenders = session.overlayRenders.filter { it.bitmapRendered }

        return TraceReplayReport(
            sessionId = metadata?.sessionId ?: summary.sessionId,
            pipelineMode = metadata?.pipelineMode,
            targetHardwareProfile = metadata?.targetHardwareProfile,
            totalFrames = totalFrames,
            droppedFrames = summary.droppedFrames,
            totalObservedFrames = totalObservedFrames,
            droppedFrameRate = droppedFrameRate,
            totalEvents = summary.totalEvents,
            durationMs = durationMs,
            cameraInputFps = cameraInputFps,
            pipelineOutputFps = pipelineOutputFps,
            pipelineThroughputFps = pipelineThroughputFps,
            semanticRunCount = semanticRunCount,
            semanticRunFps = semanticRunFps,
            semanticModelFps = semanticModelFps,
            instanceRunCount = instanceRunCount,
            instanceRunFps = instanceRunFps,
            instanceModelFps = instanceModelFps,
            overlayRenderCount = session.overlayRenders.size,
            maskRenderCount = maskRenders.size,
            avgMaskRenderMs = maskRenders.averageOrZero { it.renderMs.toDouble() },
            maskRenderFps = maskRenders.size.toFps(durationMs),
            blockedFrames = summary.blockedFrames,
            dangerousFrames = summary.dangerousFrames,
            blockedFrameRate = if (totalFrames > 0) summary.blockedFrames.toDouble() / totalFrames else 0.0,
            dangerousFrameRate = if (totalFrames > 0) summary.dangerousFrames.toDouble() / totalFrames else 0.0,
            avgProcessFrameMs = summary.avgProcessFrameMs,
            avgInferenceMs = summary.avgInferenceMs,
            avgSemanticPreprocessMs = frames.averagePositiveOrZero { it.semanticPreprocessMs.toDouble() },
            avgSemanticInferenceMs = frames.averagePositiveOrZero { it.semanticInferenceMs.toDouble() },
            avgSemanticOutputReadMs = frames.averagePositiveOrZero { it.semanticOutputReadMs.toDouble() },
            avgSemanticPostprocessMs = frames.averagePositiveOrZero { it.semanticPostprocessMs.toDouble() },
            avgInstancePreprocessMs = frames.averagePositiveOrZero { it.instancePreprocessMs.toDouble() },
            avgInstanceInferenceMs = frames.averagePositiveOrZero { it.instanceInferenceMs.toDouble() },
            avgInstanceOutputReadMs = frames.averagePositiveOrZero { it.instanceOutputReadMs.toDouble() },
            avgInstancePostprocessMs = frames.averagePositiveOrZero { it.instancePostprocessMs.toDouble() },
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

    private inline fun <T> List<T>.averagePositiveOrZero(selector: (T) -> Double): Double {
        var count = 0
        var total = 0.0
        forEach { item ->
            val value = selector(item)
            if (value > 0.0) {
                total += value
                count++
            }
        }
        return if (count > 0) total / count else 0.0
    }

    private fun List<FrameTrace>.pipelineDurationMs(summary: SessionTraceSummary): Long {
        val first = firstOrNull()?.pipelineStartedAt?.takeIf { it > 0 }
        val last = lastOrNull()?.pipelineCompletedAt?.takeIf { it > 0 }
        if (first != null && last != null && last > first) {
            return last - first
        }
        return (summary.completedAt - summary.startedAt).coerceAtLeast(0)
    }

    private fun List<FrameTrace>.pipelineOutputFps(): Double {
        if (size < 2) return 0.0
        val firstCompletedAt = first().pipelineCompletedAt
        val lastCompletedAt = last().pipelineCompletedAt
        val durationMs = lastCompletedAt - firstCompletedAt
        if (firstCompletedAt <= 0 || durationMs <= 0) return 0.0
        return (size - 1).toFps(durationMs)
    }

    private fun List<FrameTrace>.cameraInputFps(): Double {
        if (size < 2) return 0.0
        val durationMs = frameTimestampDeltaMs(first().frameTimestamp, last().frameTimestamp)
        if (durationMs <= 0) return 0.0
        val observedIntervals = (size - 1) + sumOf { it.droppedFramesSinceLast }
        return observedIntervals.toFps(durationMs)
    }

    private fun Int.toFps(durationMs: Long): Double {
        if (this <= 0 || durationMs <= 0) return 0.0
        return this * 1000.0 / durationMs
    }

    private fun Double.toFps(): Double {
        if (this <= 0.0) return 0.0
        return 1000.0 / this
    }

    private fun frameTimestampDeltaMs(firstTimestamp: Long, lastTimestamp: Long): Long {
        val delta = lastTimestamp - firstTimestamp
        if (delta <= 0) return 0
        return if (delta > 1_000_000L) delta / 1_000_000L else delta
    }
}
