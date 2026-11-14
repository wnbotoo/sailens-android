package com.sailens.guidance.sim

import com.sailens.guidance.geometry.CameraGeometry
import com.sailens.guidance.geometry.CameraGeometryResolver
import com.sailens.guidance.geometry.FrameFacts
import com.sailens.guidance.geometry.PhoneGeometrySettings
import com.sailens.guidance.geometry.SensorSample
import com.sailens.guidance.geometry.Vec3
import com.sailens.guidance.geometry.toCameraFacts
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The scenario frame whose camera timestamp is nearest [sensorTimestampNanos]: the frame itself for one of its own
 * timestamps, the nearer of two for a moment between them.
 */
fun Scenario.indexNearest(sensorTimestampNanos: Long): Int {
    val offset = if (alignClockToFrames) Scenario.CLOCK_START_MS * 1_000_000 else 0L
    val sequence = ((sensorTimestampNanos - offset).toDouble() / (stepMs * 1_000_000)).roundToLong()
    return (sequence - 1).toInt().coerceIn(0, frames.size - 1)
}

/**
 * The camera geometry of [frame] in this scenario, as the app resolves it from a device: the scenario's camera, and the
 * gravity samples of the frame nearest in time, the one nearest the frame's time chosen. A depth lane in the simulator
 * resolves its frames' geometry, and a view at another moment, with this.
 */
fun Scenario.geometryOf(
    frame: FrameFacts,
    phone: PhoneGeometrySettings = PhoneGeometrySettings.DEFAULT,
    resolver: CameraGeometryResolver = CameraGeometryResolver(),
): CameraGeometry {
    val scenarioFacts = facts(indexNearest(frame.sensorTimestampNanos))
    return resolver.resolve(
        scenarioFacts.camera.toCameraFacts(),
        frame,
        { t -> scenarioFacts.gravity.minByOrNull { abs(it.timestampNanos - t) }?.let { SensorSample(it.timestampNanos, Vec3(it.values[0], it.values[1], it.values[2])) } },
        phone,
    )
}
