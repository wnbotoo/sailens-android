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
import com.sailens.guidance.trace.capture.CaptureModes
import com.sailens.shell.app.BuildIdentity
import com.sailens.guidance.trace.capture.CaptureSensor
import com.sailens.guidance.trace.capture.SensorRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.ByteArrayOutputStream

/**
 * The debug-only "field capture" switch (off by default) and the one-shot timing-sync burst. Read
 * once per session by the controller, through [takeSessionMode].
 */
internal class FieldCaptureSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    // In memory on purpose: an armed burst is meant for the very next session, not one days later
    // after the app was killed.
    private val _timingSyncArmed = MutableStateFlow(false)
    val timingSyncArmed: StateFlow<Boolean> = _timingSyncArmed.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _enabled.value = enabled
    }

    fun setTimingSyncArmed(armed: Boolean) {
        _timingSyncArmed.value = armed
    }

    private val _modelRegression = MutableStateFlow(preferences.getBoolean(KEY_MODEL_REGRESSION, false))

    /** With the switch on, record the model-regression record (M0b) as well as field evidence. */
    val modelRegression: StateFlow<Boolean> = _modelRegression.asStateFlow()

    fun setModelRegression(on: Boolean) {
        preferences.edit().putBoolean(KEY_MODEL_REGRESSION, on).apply()
        _modelRegression.value = on
    }

    private val _group = MutableStateFlow(preferences.getString(KEY_GROUP, null))

    /** The recording group for the next sessions (`CaptureGroups`), or null when not set. */
    val group: StateFlow<String?> = _group.asStateFlow()

    fun setGroup(group: String?) {
        preferences.edit().putString(KEY_GROUP, group).apply()
        _group.value = group
    }

    private val _sceneLabel = MutableStateFlow(preferences.getString(KEY_SCENE_LABEL, "").orEmpty())
    val sceneLabel: StateFlow<String> = _sceneLabel.asStateFlow()

    fun setSceneLabel(label: String) {
        preferences.edit().putString(KEY_SCENE_LABEL, label).apply()
        _sceneLabel.value = label
    }

    private val _capGigabytes = MutableStateFlow(
        preferences.getInt(KEY_CAP_GB, DEFAULT_CAP_GB).takeIf { it in CAP_CHOICES_GB } ?: DEFAULT_CAP_GB,
    )

    /** The capture directory's cap, in GB (one of [CAP_CHOICES_GB]). */
    val capGigabytes: StateFlow<Int> = _capGigabytes.asStateFlow()

    fun setCapGigabytes(gigabytes: Int) {
        if (gigabytes !in CAP_CHOICES_GB) return
        preferences.edit().putInt(KEY_CAP_GB, gigabytes).apply()
        _capGigabytes.value = gigabytes
    }

    fun capBytes(): Long = _capGigabytes.value.toLong() * 1024 * 1024 * 1024

    fun sessionLabels(): SessionLabels =
        SessionLabels(group = _group.value, sceneLabel = _sceneLabel.value.trim().ifEmpty { null })

    /**
     * The capture mode for a Guidance session starting now, or null for none. An armed burst is
     * used up here, whether or not the switch is on; otherwise the switch decides.
     */
    fun takeSessionMode(): String? = when {
        _timingSyncArmed.compareAndSet(expect = true, update = false) -> CaptureModes.TIMING_SYNC
        _enabled.value && _modelRegression.value -> CaptureModes.MODEL_REGRESSION
        _enabled.value -> CaptureModes.FIELD_EVIDENCE
        else -> null
    }

    companion object {
        val CAP_CHOICES_GB: List<Int> = listOf(2, 5, 10)
        const val DEFAULT_CAP_GB: Int = 10
        private const val PREFERENCES_NAME = "field_capture_settings"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_MODEL_REGRESSION = "model_regression"
        private const val KEY_GROUP = "group"
        private const val KEY_SCENE_LABEL = "scene_label"
        private const val KEY_CAP_GB = "cap_gb"
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

internal fun captureDeviceInfo(context: Context, build: BuildIdentity?): CaptureDeviceInfo {
    val packageInfo = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }.getOrNull()
    return CaptureDeviceInfo(
        appVersionName = packageInfo?.versionName,
        appVersionCode = packageInfo?.longVersionCode,
        gitSha = build?.gitSha,
        modelArtifacts = build?.modelArtifacts.orEmpty(),
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        sdkInt = Build.VERSION.SDK_INT,
    )
}
