package com.sailens.shell.consistency

import android.os.SystemClock
import com.sailens.core.frame.ImageFrame
import com.sailens.guidance.geometry.CameraFactsSource
import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.CameraGeometryResolver
import com.sailens.guidance.geometry.FrameFacts
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.motion.OrientationSource
import kotlinx.coroutines.delay

/**
 * Where the camera pointed for each frame of a consistency check, for the depth model's ground fit.
 *
 * The check runs while Guidance is stopped, and the gravity source stops with Guidance, so the check
 * holds its own use of it ([start]/[stop]; the source counts its users, and Guidance is unaffected).
 * A frame's geometry is resolved once, as soon as the frame is taken and before any model runs on it:
 * the CPU reference takes over a second per frame, and asking for gravity after that would find a
 * sample from a later moment.
 */
internal interface ConsistencyGeometry {
    fun start()
    fun stop()

    /** Waits up to [timeoutMs] for a first gravity sample; returns either way. */
    suspend fun awaitGravity(timeoutMs: Long)

    /** This frame's geometry, resolved now; null when no source is wired. */
    fun resolve(frame: ImageFrame): CameraGeometry?

    /** No sensors: every frame has no geometry, and the depth fit is not judged. */
    object None : ConsistencyGeometry {
        override fun start() = Unit
        override fun stop() = Unit
        override suspend fun awaitGravity(timeoutMs: Long) = Unit
        override fun resolve(frame: ImageFrame): CameraGeometry? = null
    }
}

internal class SensorConsistencyGeometry(
    private val orientation: OrientationSource,
    private val cameraFacts: CameraFactsSource,
    private val phone: () -> PhoneGeometrySettings,
    private val resolver: CameraGeometryResolver = CameraGeometryResolver(),
) : ConsistencyGeometry {
    override fun start() = orientation.start()
    override fun stop() = orientation.stop()

    override suspend fun awaitGravity(timeoutMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (orientation.gravityNear(SystemClock.elapsedRealtimeNanos()) == null && SystemClock.elapsedRealtime() < deadline) {
            delay(POLL_MS)
        }
    }

    override fun resolve(frame: ImageFrame): CameraGeometry =
        resolver.resolve(cameraFacts.current(), FrameFacts.of(frame), orientation::gravityNear, phone())

    private companion object {
        const val POLL_MS = 50L
    }
}
