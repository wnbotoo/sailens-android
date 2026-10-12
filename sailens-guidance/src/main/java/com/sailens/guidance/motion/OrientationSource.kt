package com.sailens.guidance.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.sailens.guidance.geometry.SensorSample
import com.sailens.guidance.geometry.Vec3

/**
 * Gravity and rotation for M3o's per-frame geometry. Runs only while a Guidance session does:
 * the session starts it and stops it, so the higher sensor rate costs nothing outside Guidance.
 */
interface OrientationSource {
    fun start()
    fun stop()

    /** The gravity sample nearest [timeNanos] (elapsed realtime), or null when there is none. */
    fun gravityNear(timeNanos: Long): SensorSample?

    fun motionState(nowNanos: Long, isStationary: Boolean): MotionState
}

/** No sensors: every geometry comes out unavailable and consumers keep today's behaviour. */
object NoOrientationSource : OrientationSource {
    private val tracker = MotionTracker()
    override fun start() = Unit
    override fun stop() = Unit
    override fun gravityNear(timeNanos: Long): SensorSample? = null
    override fun motionState(nowNanos: Long, isStationary: Boolean) = tracker.state(nowNanos, isStationary)
}

/**
 * `TYPE_GRAVITY` and `TYPE_GAME_ROTATION_VECTOR` at `SENSOR_DELAY_GAME` (§7), delivered on a thread
 * of their own so ~100 events a second never queue behind the UI. A device without one of the
 * sensors simply never fills that half, and the geometry says so.
 */
class AndroidOrientationSource(context: Context) : OrientationSource {
    private val sensorManager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gravitySensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val rotationSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    private val gravity = SensorSampleBuffer()
    private val tracker = MotionTracker()
    private var thread: HandlerThread? = null

    /** Sessions using the sensors: a session ending late must not switch them off under the next one. */
    private var users = 0

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val values = event.values ?: return
            when (event.sensor?.type) {
                Sensor.TYPE_GRAVITY -> if (values.size >= 3) {
                    gravity.add(SensorSample(event.timestamp, Vec3(values[0], values[1], values[2])))
                }
                Sensor.TYPE_GAME_ROTATION_VECTOR -> tracker.onGameRotationVector(event.timestamp, values)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    @Synchronized
    override fun start() {
        val manager = sensorManager ?: return
        if (users++ > 0) return
        // An event already queued when the last session stopped may have landed after its clear.
        gravity.clear()
        tracker.reset()
        val handler = HandlerThread("sailens-orientation").also { it.start(); thread = it }.let { Handler(it.looper) }
        gravitySensor?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME, handler) }
        rotationSensor?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME, handler) }
    }

    @Synchronized
    override fun stop() {
        if (users == 0 || --users > 0) return
        sensorManager?.unregisterListener(listener)
        thread?.quitSafely()
        thread = null
        gravity.clear()
        tracker.reset()
    }

    override fun gravityNear(timeNanos: Long): SensorSample? = gravity.nearest(timeNanos)

    override fun motionState(nowNanos: Long, isStationary: Boolean) = tracker.state(nowNanos, isStationary)
}
