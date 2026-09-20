package com.sailens.shell.ext

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

// 用外部浏览器打开链接；没有可用浏览器时静默失败（对用户没有可操作的补救）
fun Context.openUrl(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
    }
}

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