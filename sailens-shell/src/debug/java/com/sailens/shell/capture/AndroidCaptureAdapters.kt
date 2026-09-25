package com.sailens.shell.capture

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.sailens.guidance.trace.capture.CaptureSensor
import com.sailens.guidance.trace.capture.SensorRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream

/** The debug-only "field capture" switch. Off by default; read once per session by the controller. */
internal class FieldCaptureSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _enabled.value = enabled
    }

    private companion object {
        const val PREFERENCES_NAME = "field_capture_settings"
        const val KEY_ENABLED = "enabled"
    }
}

internal object AndroidCaptureClock : CaptureClock {
    override fun wallMs(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()
}

internal object YuvImageJpegEncoder : JpegEncoder {
    override fun encode(image: Nv21Image, quality: Int): ByteArray {
        val out = ByteArrayOutputStream(image.width * image.height / 4)
        val yuv = YuvImage(image.bytes, ImageFormat.NV21, image.width, image.height, null)
        check(yuv.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)) { "JPEG encoding failed" }
        return out.toByteArray()
    }
}

/**
 * Gravity, game rotation vector and gyroscope at `SENSOR_DELAY_GAME` (a requested rate; the real
 * cadence is in the event timestamps), delivered on a thread of their own.
 */
internal class AndroidCaptureSensorSource(context: Context) : CaptureSensorSource {
    private val manager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private var thread: HandlerThread? = null
    private var listener: SensorEventListener? = null

    override fun start(onRecord: (SensorRecord) -> Unit): List<String> {
        stop()
        val sensorManager = manager ?: return emptyList()
        val handlerThread = HandlerThread("field-capture-sensors").apply { start() }
        val handler = Handler(handlerThread.looper)
        val eventListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val sensor = SENSORS[event.sensor.type] ?: return
                onRecord(SensorRecord(sensor, event.timestamp, event.accuracy, event.values.toList()))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        // Owned before registering, so a failure part-way is cleaned up by stop().
        thread = handlerThread
        listener = eventListener
        return try {
            SENSORS.mapNotNull { (type, name) ->
                val sensor = sensorManager.getDefaultSensor(type) ?: return@mapNotNull null
                if (sensorManager.registerListener(eventListener, sensor, SensorManager.SENSOR_DELAY_GAME, handler)) {
                    name.serialName
                } else {
                    null
                }
            }
        } catch (e: RuntimeException) {
            stop()
            throw e
        }
    }

    override fun stop() {
        listener?.let { manager?.unregisterListener(it) }
        listener = null
        thread?.quitSafely()
        thread = null
    }

    private companion object {
        val SENSORS = mapOf(
            Sensor.TYPE_GRAVITY to CaptureSensor.GRAVITY,
            Sensor.TYPE_GAME_ROTATION_VECTOR to CaptureSensor.GAME_ROTATION_VECTOR,
            Sensor.TYPE_GYROSCOPE to CaptureSensor.GYROSCOPE,
        )

        val CaptureSensor.serialName: String
            get() = when (this) {
                CaptureSensor.GRAVITY -> "gravity"
                CaptureSensor.GAME_ROTATION_VECTOR -> "game_rotation_vector"
                CaptureSensor.GYROSCOPE -> "gyroscope"
            }
    }
}

internal fun captureDeviceInfo(context: Context): CaptureDeviceInfo {
    val packageInfo = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()
    return CaptureDeviceInfo(
        appVersionName = packageInfo?.versionName,
        appVersionCode = packageInfo?.longVersionCode,
        gitSha = null,
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        sdkInt = Build.VERSION.SDK_INT,
    )
}
