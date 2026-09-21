package com.sailens.guidance.usecase.trace

import com.sailens.guidance.config.PipelinePerformanceBudget
import com.sailens.guidance.model.trace.TraceReplayReport

data class TraceReplayBudgetEvaluation(
    val isWithinBudget: Boolean,
    val warnings: List<String>,
)

class EvaluateTraceReplayBudgetUseCase(
    private val budget: PipelinePerformanceBudget = PipelinePerformanceBudget(),
) {
    companion object {
        const val DEFAULT_TARGET_P95_TOTAL_PIPELINE_MS =
            PipelinePerformanceBudget.DEFAULT_TARGET_P95_TOTAL_PIPELINE_MS
        const val DEFAULT_MAX_DROPPED_FRAME_RATE =
            PipelinePerformanceBudget.DEFAULT_MAX_DROPPED_FRAME_RATE
    }

    operator fun invoke(report: TraceReplayReport): TraceReplayBudgetEvaluation {
        val warnings = buildList {
            if (report.p95TotalPipelineMs > budget.targetP95TotalPipelineMs) {
                add(
                    "p95 total pipeline ${report.p95TotalPipelineMs}ms exceeds ${budget.targetP95TotalPipelineMs}ms"
                )
            }

            if (report.droppedFrameRate > budget.maxDroppedFrameRate) {
                add(
                    "dropped frame rate ${(report.droppedFrameRate * 100).toInt()}% exceeds ${(budget.maxDroppedFrameRate * 100).toInt()}%"
                )
            }
        }

        return TraceReplayBudgetEvaluation(
            isWithinBudget = warnings.isEmpty(),
            warnings = warnings,
        )
    }
}
