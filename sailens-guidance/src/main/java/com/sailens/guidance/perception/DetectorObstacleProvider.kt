package com.sailens.guidance.perception

import android.content.Context
import com.sailens.core.frame.ImageFrame
import com.sailens.core.log.LogService
import com.sailens.guidance.config.PerceptionConfig
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.perception.ObstacleModelOutput
import com.sailens.guidance.repository.ObstacleProvider
import com.sailens.guidance.semantics.CocoNavigationSemantics
import com.sailens.guidance.semantics.NavigationSemantics
import com.sailens.runtime.CatalogModelSourceResolver
import com.sailens.runtime.InputPreprocessCache
import com.sailens.runtime.ModelSourceResolver
import com.sailens.vision.detection.DetectionModelConfig
import com.sailens.vision.detection.LiteRtDetectionRunner

/**
 * The navigation side of object detection.
 *
 * sailens-vision reports what the model saw; this turns it into what it means for someone walking.
 * The class-to-[ObstacleCategory] mapping is Guidance's, and it is applied twice on purpose: once
 * up front as [LiteRtDetectionRunner]'s allow-list so irrelevant classes never reach NMS, and once
 * on the way out to attach the category (architecture.md §6.3).
 */
class DetectorObstacleProvider(
    context: Context,
    perceptionConfig: PerceptionConfig,
    modelConfig: DetectionModelConfig = DetectionModelConfig(),
    modelSourceResolver: ModelSourceResolver = CatalogModelSourceResolver,
    preprocessCache: InputPreprocessCache? = null,
    logService: LogService,
    private val navigationSemantics: NavigationSemantics = CocoNavigationSemantics,
) : ObstacleProvider {

    private val runner = LiteRtDetectionRunner(
        context = context,
        allowedClassIds = navigationSemantics.allowedObstacleClassIds(modelConfig.classCount),
        confidenceThreshold = perceptionConfig.minObstacleConfidence,
        maxDetections = perceptionConfig.maxObstacles,
        modelConfig = modelConfig,
        modelSourceResolver = modelSourceResolver,
        preprocessCache = preprocessCache,
        logService = logService,
    )

    override val isInitialized: Boolean
        get() = runner.isInitialized

    override suspend fun initialize() {
        runner.initialize()
    }

    override suspend fun detect(frame: ImageFrame): ObstacleModelOutput {
        val output = runner.detect(frame)
        return ObstacleModelOutput(
            detections = output.detections.toObstacleDetections(navigationSemantics),
            preprocessTimeMs = output.preprocessTimeMs,
            inferenceTimeMs = output.inferenceTimeMs,
            outputReadTimeMs = output.outputReadTimeMs,
            postprocessTimeMs = output.postprocessTimeMs,
            runtimeInfo = output.runtimeInfo,
        )
    }

    override fun release() {
        runner.release()
    }
}
