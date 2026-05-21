package com.friady.sailens.presentation.device

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import com.friady.sailens.domain.model.common.EventPriority
import com.friady.sailens.domain.model.scene.SceneEvent

/**
 * Android 触觉反馈服务实现
 */
class HapticManager(context: Context) {
    private val vibrator =
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator

    fun trigger(event: SceneEvent) {
        if (vibrator?.hasVibrator() != true) return

        val pattern = when (event.priority) {
            EventPriority.CRITICAL -> longArrayOf(0, 100, 50, 100, 50, 100)
            EventPriority.HIGH -> longArrayOf(0, 100, 50, 100)
            EventPriority.MEDIUM -> longArrayOf(0, 100)
            EventPriority.LOW -> longArrayOf(0, 50)
        }
        vibrate(pattern)
    }

    fun vibrate(pattern: LongArray) {
        if (vibrator?.hasVibrator() != true) return

        val effect = VibrationEffect.createWaveform(pattern, -1)
        vibrator.vibrate(effect)
    }

    fun cancel() {
        vibrator?.cancel()
    }
}