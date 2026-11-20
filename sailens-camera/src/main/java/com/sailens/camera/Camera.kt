package com.sailens.camera

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

private const val TAG = "Camera"

public class Camera(private val measureFrameTiming: Boolean = false) : CameraCharacteristicsProvider {
    private var cameraControl: CameraControl? = null
    private var cameraInfo: CameraInfo? = null

    @Volatile
    private var characteristics: CameraCharacteristicsSnapshot? = null

    override fun currentSnapshot(): CameraCharacteristicsSnapshot? = characteristics

    // ProcessCameraProvider 有很多设置项目，比如是否支持某个 CameraSelector，查询 CameraSelector 的信息等。
    // 如果要切换摄像头，需要重新调用 provider.bindToLifecycle
    public suspend fun bind(
        appContext: Context,
        lifecycleOwner: LifecycleOwner,
        useCases: List<UseCase>,
    ): Unit = withContext(Dispatchers.Main.immediate) {
        val cameraProvider = ProcessCameraProvider.awaitInstance(appContext)
        val cameraSelector = DEFAULT_BACK_CAMERA
        if (!cameraProvider.hasCamera(cameraSelector)) {
            return@withContext
        }
        cameraInfo = cameraProvider.getCameraInfo(cameraSelector)

        val useCaseGroup = UseCaseGroup.Builder().apply {
            useCases.forEach {
                addUseCase(it)
            }
        }.build()

        val camera = cameraProvider.bindToLifecycle(
            lifecycleOwner, cameraSelector, useCaseGroup
        )
        cameraControl = camera.cameraControl
        // Read per binding, never cached across bindings. A failure to read is not a failure to
        // bind: the camera works without it, only geometry and capture lose their metadata.
        characteristics = runCatching {
            readCharacteristicsSnapshot(camera.cameraInfo, SystemClock.elapsedRealtimeNanos())
        }.onFailure { Log.w(TAG, "Could not read camera characteristics", it) }.getOrNull()

        try {
            if (measureFrameTiming) {
                for (useCase in useCases) {
                    val (name, size) = when (useCase) {
                        is ImageAnalysis -> "analysis" to useCase.resolutionInfo?.resolution
                        is Preview -> "preview" to useCase.resolutionInfo?.resolution
                        else -> continue
                    }
                    Log.i(TAG, "CameraX negotiated $name=${size ?: "unknown"}")
                }
            }
            awaitCancellation()
        } finally {
            cameraProvider.unbindAll()
            cameraControl = null
            cameraInfo = null
            characteristics = null
        }
    }
}
