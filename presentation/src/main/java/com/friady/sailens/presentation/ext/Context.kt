package com.friady.sailens.presentation.ext

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

// 扩展属性：获取默认 Vibrator
private val Context.vibrator: Vibrator
    get() {
        val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        return manager.defaultVibrator
    }

// 单次震动
fun Context.vibrate(milliseconds: Long = 300) {
    val effect = VibrationEffect.createOneShot(
        milliseconds,
        VibrationEffect.DEFAULT_AMPLITUDE
    )
    vibrator.vibrate(effect)
}

// 自定义震动效果
fun Context.vibrate(effect: VibrationEffect) {
    vibrator.vibrate(effect)
}

// 停止震动
fun Context.stopVibrate() {
    vibrator.cancel()
}