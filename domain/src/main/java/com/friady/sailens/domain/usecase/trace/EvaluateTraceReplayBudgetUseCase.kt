package com.friady.sailens.domain.usecase.trace

import com.friady.sailens.domain.model.trace.TraceReplayReport
import com.friady.sailens.domain.config.PipelineBudget

data class TraceReplayBudgetEvaluation(
    val isWithinBudget: Boolean,
    val warnings: List<String>,
)

class EvaluateTraceReplayBudgetUseCase {
    companion object {
        const val TARGET_P95_TOTAL_PIPELINE_MS = PipelineBudget.TARGET_P95_TOTAL_PIPELINE_MS
        const val MAX_DROPPED_FRAME_RATE = PipelineBudget.MAX_DROPPED_FRAME_RATE
    }

    operator fun invoke(report: TraceReplayReport): TraceReplayBudgetEvaluation {
        val warnings = buildList {
            if (report.p95TotalPipelineMs > TARGET_P95_TOTAL_PIPELINE_MS) {
                add(
                    "p95 total pipeline ${report.p95TotalPipelineMs}ms exceeds ${TARGET_P95_TOTAL_PIPELINE_MS}ms"
                )
            }

            if (report.droppedFrameRate > MAX_DROPPED_FRAME_RATE) {
                add(
                    "dropped frame rate ${(report.droppedFrameRate * 100).toInt()}% exceeds ${(MAX_DROPPED_FRAME_RATE * 100).toInt()}%"
                )
            }
        }

        return TraceReplayBudgetEvaluation(
            isWithinBudget = warnings.isEmpty(),
            warnings = warnings,
        )
    }
}
