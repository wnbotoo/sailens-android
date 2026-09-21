package com.sailens.guidance.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 用加速度计判断用户是不是基本站着没动。
 *
 * 选加速度计而不是计步器（`TYPE_STEP_DETECTOR`）是因为后者在 API 29+ 需要
 * `ACTIVITY_RECOGNITION` 运行时权限。为了一个"少打扰"的优化，去让盲人用户在初次使用时
 * 多过一道权限对话框，不划算——加速度计免权限，精度也够用。
 *
 * 判据是重力归一化后的加速度幅值波动：走路时每一步都会产生明显的周期性起伏，站着时只剩
 * 手抖级别的噪声。用波动而不是绝对值，是因为绝对值里恒定的重力分量与姿态有关，静止时也不为零。
 */
class DeviceMotionDataSource(
    context: Context,
    private val stationaryMagnitudeThreshold: Float = 0.6f,
    private val sampleWindow: Int = 25,
    private val enterStationaryStreak: Int = 2,
    private val exitStationaryStreak: Int = 1,
) {
    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val _isStationary = MutableStateFlow(false)
    val isStationary: StateFlow<Boolean> = _isStationary.asStateFlow()

    private val recentDeviations = ArrayDeque<Float>()
    private var stationaryStreak = 0
    private var movingStreak = 0

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
            val values = event.values ?: return
            if (values.size < 3) return
            record(magnitude(values[0], values[1], values[2]))
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun start() {
        val manager = sensorManager ?: return
        val sensor = accelerometer ?: return
        reset()
        // UI 级采样率（~60ms）足够分辨步频，比 GAME/FASTEST 省电得多。
        manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        sensorManager?.unregisterListener(listener)
        reset()
    }

    private fun reset() {
        recentDeviations.clear()
        stationaryStreak = 0
        movingStreak = 0
        _isStationary.value = false
    }

    private fun magnitude(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)

    private fun record(magnitude: Float) {
        // 减掉重力再取绝对值：剩下的就是运动带来的净波动，与手机举持姿态无关。
        recentDeviations.addLast(abs(magnitude - SensorManager.GRAVITY_EARTH))
        while (recentDeviations.size > sampleWindow) {
            recentDeviations.removeFirst()
        }
        if (recentDeviations.size < sampleWindow) return

        val averageDeviation = recentDeviations.sum() / recentDeviations.size
        val looksStationary = averageDeviation < stationaryMagnitudeThreshold

        // 进入静止要比退出静止更慢：误判成"静止"会压掉真实提示，误判成"移动"只是少省一点打扰。
        // 代价不对称，判据也就不该对称。
        if (looksStationary) {
            stationaryStreak++
            movingStreak = 0
            if (stationaryStreak >= enterStationaryStreak) _isStationary.value = true
        } else {
            movingStreak++
            stationaryStreak = 0
            if (movingStreak >= exitStationaryStreak) _isStationary.value = false
        }
    }
}
