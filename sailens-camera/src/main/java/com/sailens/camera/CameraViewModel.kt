package com.sailens.camera

import android.content.Context
import android.util.Size
import android.view.OrientationEventListener
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors

public class CameraViewModel(
    private val camera: Camera,
    private val imageFrameAnalyzer: ImageAnalysis.Analyzer,
    private val runtimeConfig: CameraRuntimeConfig = CameraRuntimeConfig(),
) : ViewModel() {
    private val executor = Executors.newSingleThreadExecutor()

    private val _surfaceRequest = MutableStateFlow<SurfaceRequest?>(null)
    public val surfaceRequest: StateFlow<SurfaceRequest?> = _surfaceRequest.asStateFlow()

    public suspend fun bindToCamera(
        appContext: Context,
        lifecycleOwner: LifecycleOwner,
    ) {
        // Analysis frames follow how the phone is held, not the display (see AnalysisRotation).
        // The preview keeps following the display: it is drawn on the screen.
        val orientationListener = object : OrientationEventListener(appContext) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val next = AnalysisRotation.targetRotationFor(orientation, imageAnalysis.targetRotation)
                if (next != imageAnalysis.targetRotation) imageAnalysis.targetRotation = next
            }
        }
        orientationListener.enable()
        try {
            camera.bind(appContext, lifecycleOwner, listOf(previewUseCase, imageAnalysis))
        } finally {
            orientationListener.disable()
        }
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
