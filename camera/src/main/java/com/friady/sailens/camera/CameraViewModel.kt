package com.friady.sailens.camera

import android.content.Context
import android.util.Size
import android.view.Surface
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
) : ViewModel() {
    private companion object {
        val PREVIEW_SIZE = Size(1920, 1080)
        val ANALYSIS_SIZE = Size(640, 360)
    }

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
        .setResolutionSelector(getResolutionSelector(PREVIEW_SIZE)).build().apply {
            setSurfaceProvider { newSurfaceRequest ->
                _surfaceRequest.update { newSurfaceRequest }
            }
        }

    private val imageAnalysis =
        ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setResolutionSelector(getResolutionSelector(ANALYSIS_SIZE)).build().apply {
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
        executor.close()
    }
}