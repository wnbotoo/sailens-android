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
import com.sailens.runtime.InputPreprocessCache
import com.sailens.runtime.ModelSourceResolver
import com.sailens.vision.detection.DetectionModelConfig
import com.sailens.vision.detection.LiteRtDetectionRunner
import com.sailens.vision.ModelRunTiming

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
    modelSourceResolver: ModelSourceResolver,
    preprocessCache: InputPreprocessCache? = null,
    logService: LogService,
    private val navigationSemantics: NavigationSemantics = CocoNavigationSemantics,
    directFloatNchw: Boolean = false,
    precomputeNearestIndices: Boolean = false,
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
        directFloatNchw = directFloatNchw,
        precomputeNearestIndices = precomputeNearestIndices,
    )

    override val isInitialized: Boolean
        get() = runner.isInitialized

    override suspend fun initialize() {
        runner.initialize()
    }

    override suspend fun detect(frame: ImageFrame): ObstacleModelOutput = detect(frame, null)

    override fun sharedFloatInput(frame: ImageFrame): InputPreprocessCache.SharedFloatInput? =
        runner.sharedFloatInput(frame)

    override suspend fun detect(frame: ImageFrame, timing: ModelRunTiming?): ObstacleModelOutput {
        val output = runner.detect(frame, timing)
        return ObstacleModelOutput(
            detections = output.detections.toObstacleDetections(navigationSemantics),
            preprocessTimeMs = output.preprocessTimeMs,
            inferenceTimeMs = output.inferenceTimeMs,
            outputReadTimeMs = output.outputReadTimeMs,
            postprocessTimeMs = output.postprocessTimeMs,
            runtimeInfo = output.runtimeInfo,
            imageMapping = output.imageMapping,
        )
    }

    override fun release() {
        runner.release()
    }
}
