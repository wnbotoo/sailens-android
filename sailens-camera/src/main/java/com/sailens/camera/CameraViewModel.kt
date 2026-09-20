package com.sailens.camera

import android.content.Context
import android.util.Size
import androidx.camera.core.AspectRatio
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors

class CameraViewModel(
    private val camera: Camera,
    private val imageFrameAnalyzer: ImageAnalysis.Analyzer,
    private val runtimeConfig: CameraRuntimeConfig = CameraRuntimeConfig(),
) : ViewModel() {
    private val executor = Executors.newSingleThreadExecutor()

    private val _surfaceRequest = MutableStateFlow<SurfaceRequest?>(null)
    val surfaceRequest = _surfaceRequest.asStateFlow()

    suspend fun bindToCamera(
        appContext: Context,
        lifecycleOwner: LifecycleOwner,
    ) {
        camera.bind(appContext, lifecycleOwner, listOf(previewUseCase, imageAnalysis))
    }

    private val previewUseCase = Preview.Builder()
        .setResolutionSelector(
            getResolutionSelector(Size(runtimeConfig.previewWidth, runtimeConfig.previewHeight))
        ).build().apply {
            setSurfaceProvider { newSurfaceRequest ->
                _surfaceRequest.update { newSurfaceRequest }
            }
        }

    private val imageAnalysis =
        ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setResolutionSelector(
                getResolutionSelector(Size(runtimeConfig.analysisWidth, runtimeConfig.analysisHeight))
            ).build().apply {
                setAnalyzer(executor, imageFrameAnalyzer)
            }

    private fun getResolutionSelector(preferredSize: Size): ResolutionSelector {
        val resolutionStrategy = ResolutionStrategy(
            preferredSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
        )

        val aspectRatioStrategy = AspectRatioStrategy(
            AspectRatio.RATIO_16_9, AspectRatioStrategy.FALLBACK_RULE_AUTO
        )

        return ResolutionSelector.Builder()
            .setResolutionStrategy(resolutionStrategy)
            .setAspectRatioStrategy(aspectRatioStrategy)
            .build()
    }

    override fun onCleared() {
        super.onCleared()
        executor.shutdown()
    }
}
