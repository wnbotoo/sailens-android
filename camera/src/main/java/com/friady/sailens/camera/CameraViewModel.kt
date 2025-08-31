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
    private val executor = Executors.newSingleThreadExecutor()

    private val _surfaceRequest = MutableStateFlow<SurfaceRequest?>(null)
    val surfaceRequest = _surfaceRequest.asStateFlow()

    // 不主动设置 resolutionStrategy 时，默认的输出大小640*480
    var preferSize: Size = Size(1920, 1080)

    suspend fun bindToCamera(
        appContext: Context,
        lifecycleOwner: LifecycleOwner,
    ) {
        camera.bind(appContext, lifecycleOwner, listOf(previewUseCase, imageAnalysis))
    }

    private val previewUseCase = Preview.Builder()
        .setResolutionSelector(getResolutionSelector()).build().apply {
            setSurfaceProvider { newSurfaceRequest ->
                _surfaceRequest.update { newSurfaceRequest }
            }
        }

    private val imageAnalysis =
        ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setResolutionSelector(getResolutionSelector()).build().apply {
                setAnalyzer(executor, imageFrameAnalyzer)
            }

    private fun getResolutionSelector(): ResolutionSelector {
        val resolutionStrategy = ResolutionStrategy(
            preferSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
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