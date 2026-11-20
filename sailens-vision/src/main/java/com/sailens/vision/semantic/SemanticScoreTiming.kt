package com.sailens.vision.semantic

import android.os.SystemClock

/** Timing for one scored tensor read through a LiteRT handle. */
class SemanticScoreTiming(val sampleOffset: Int? = null) {
    init {
        require(sampleOffset == null || sampleOffset in 0 until PIXEL_SAMPLE_STRIDE)
    }

    val nativeNanos = LongArray(9) { -1L }
    val fusedKernelNanos: Long? get() = nativeNanos[1].takeIf { it >= 0 }
    val arraysGetNanos: Long get() = nativeNanos[0]
    val arraysReleaseNanos: Long get() = nativeNanos[2]
    val sampledPixels: Long get() = nativeNanos[3]
    val sampledArgmaxNanos: Long get() = nativeNanos[4]
    val sampledMaskWritesNanos: Long get() = nativeNanos[5]
    val sampledStatisticsNanos: Long get() = nativeNanos[6]
    val sampledClockFloorNanos: Long get() = nativeNanos[7]
    val argmaxImplementation: String?
        get() = when (nativeNanos[8]) {
            0L -> "scalar"
            1L -> "neon"
            else -> null
        }

    var kotlinPrepareNanos: Long = 0
        private set
    var kotlinAssembleNanos: Long = 0
        private set

    fun now(): Long = SystemClock.elapsedRealtimeNanos()

    fun preparedSince(started: Long) {
        kotlinPrepareNanos = now() - started
    }

    fun assembledSince(started: Long) {
        kotlinAssembleNanos = now() - started
    }

    internal companion object {
        const val PIXEL_SAMPLE_STRIDE = 257
        private const val FRAME_SAMPLE_INTERVAL = 8

        fun forFrame(frameId: Long, samplePixels: Boolean): SemanticScoreTiming {
            val offset = if (samplePixels && frameId % FRAME_SAMPLE_INTERVAL == 0L) {
                frameId.mod(PIXEL_SAMPLE_STRIDE)
            } else {
                null
            }
            return SemanticScoreTiming(offset)
        }
    }
}
