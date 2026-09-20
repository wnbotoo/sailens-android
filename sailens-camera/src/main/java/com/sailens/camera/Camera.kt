package com.sailens.camera

import android.content.Context
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA
import androidx.camera.core.UseCase
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.awaitCancellation

private const val TAG = "Camera"

class Camera {
    private var cameraControl: CameraControl? = null
    private var cameraInfo: CameraInfo? = null

    // ProcessCameraProvider 有很多设置项目，比如是否支持某个 CameraSelector，查询 CameraSelector 的信息等。
    // 如果要切换摄像头，需要重新调用 provider.bindToLifecycle
    suspend fun bind(
        appContext: Context,
        lifecycleOwner: LifecycleOwner,
        useCases: List<UseCase>,
    ) {
        val cameraProvider = ProcessCameraProvider.awaitInstance(appContext)
        val cameraSelector = DEFAULT_BACK_CAMERA
        if (!cameraProvider.hasCamera(cameraSelector)) {
            return
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

        try {
            awaitCancellation()
        } finally {
            cameraProvider.unbindAll()
            cameraControl = null
            cameraInfo = null
        }
    }
}