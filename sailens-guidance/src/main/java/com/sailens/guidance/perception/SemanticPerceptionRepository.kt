package com.sailens.guidance.perception

import android.content.Context
import com.sailens.core.frame.ImageFrame
import com.sailens.core.log.LogService
import com.sailens.guidance.kernel.NavigationScorePostprocessor
import com.sailens.guidance.kernel.NavigationSemanticResult
import com.sailens.guidance.model.perception.SegmentationOutput
import com.sailens.guidance.repository.PerceptionRepository
import com.sailens.runtime.CatalogModelSourceResolver
import com.sailens.runtime.InputPreprocessCache
import com.sailens.runtime.ModelSourceResolver
import com.sailens.vision.semantic.LiteRtSemanticSegmenter
import com.sailens.vision.semantic.SemanticModelConfig

/**
 * The navigation side of semantic segmentation.
 *
 * sailens-vision runs the model and owns the tensors; this decides what the classes mean by
 * handing the runner Guidance's fused [NavigationScorePostprocessor], and maps the generic run
 * result onto the [SegmentationOutput] the navigation pipeline consumes.
 */
class SemanticPerceptionRepository(
    context: Context,
    private val scorePostprocessor: NavigationScorePostprocessor,
    modelConfig: SemanticModelConfig = SemanticModelConfig(),
    modelSourceResolver: ModelSourceResolver = CatalogModelSourceResolver,
    preprocessCache: InputPreprocessCache? = null,
    logService: LogService,
) : PerceptionRepository {

    private val segmenter = LiteRtSemanticSegmenter<NavigationSemanticResult>(
        context = context,
        postprocessor = scorePostprocessor,
        modelConfig = modelConfig,
        modelSourceResolver = modelSourceResolver,
        preprocessCache = preprocessCache,
        logService = logService,
    )

    override val isInitialized: Boolean
        get() = segmenter.isInitialized

    override suspend fun initialize() {
        segmenter.initialize()
    }

    override suspend fun segment(frame: ImageFrame): Result<SegmentationOutput> =
        segmenter.segment(frame).map { run ->
            SegmentationOutput(
                mask = run.value.mask,
                preprocessTimeMs = run.preprocessTimeMs,
                // inferenceTimeMs stays "model + reading the model's output back", as it always
                // has; modelTimeMs and outputReadTimeMs break that down for §12.3.
                inferenceTimeMs = run.modelTimeMs + run.outputReadTimeMs,
                postprocessTimeMs = run.postprocessTimeMs,
                modelTimeMs = run.modelTimeMs,
                outputReadTimeMs = run.outputReadTimeMs,
                analysisStats = run.value.stats,
                runtimeInfo = run.runtimeInfo,
                maskLease = run.value.maskLease,
            )
        }

    override suspend fun release() {
        segmenter.release()
        scorePostprocessor.releaseBuffers()
    }
}
