package com.sailens.shell.device

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.sailens.guidance.model.scene.SceneEvent

/**
 * Android 触觉反馈服务实现。
 *
 * 震动是唯一一条不依赖 TTS 引擎、也不与用户正在听的音频争抢的通道，因此它承担两件事：
 * 语音关闭时的完整导航信息（见 [GuidanceHaptic] 的词汇表），以及语音本身失效时的兜底告警。
 */
class HapticManager(context: Context) {
    private val vibrator: Vibrator? =
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator

    fun trigger(event: SceneEvent) {
        play(
            haptic = GuidanceHaptic.forEvent(event),
            amplitude = GuidanceHaptic.amplitudeFor(event.priority),
        )
    }

    /** 播放词汇表里的某个符号。设置页的"试触感"和中断告警都走这里。 */
    fun play(haptic: GuidanceHaptic, amplitude: Int = DEFAULT_AMPLITUDE) {
        val device = vibrator ?: return
        if (!device.hasVibrator()) return

        val effect = if (device.hasAmplitudeControl()) {
            // timings 是 关/开 交替的，index 0 是起始延迟，所以偶数位不振动。
            val amplitudes = IntArray(haptic.timings.size) { index ->
                if (index % 2 == 0) 0 else amplitude
            }
            VibrationEffect.createWaveform(haptic.timings, amplitudes, NO_REPEAT)
        } else {
            // 没有幅度控制的马达上强度分档无意义，退回纯节奏——节奏才是信息载体。
            VibrationEffect.createWaveform(haptic.timings, NO_REPEAT)
        }

        vibrate(device, effect)
    }

    fun cancel() {
        vibrator?.cancel()
    }

    /**
     * 用无障碍用途发起震动，这样在勿扰/静音模式下依然会触发。
     *
     * 导航提示不是通知，用户在会议或夜间静音时恰恰**更**依赖震动通道。
     */
    private fun vibrate(device: Vibrator, effect: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            device.vibrate(
                effect,
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ACCESSIBILITY),
            )
        } else {
            @Suppress("DEPRECATION")
            device.vibrate(
                effect,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
    }

    private companion object {
        const val NO_REPEAT = -1
        const val DEFAULT_AMPLITUDE = 205
    }
}
