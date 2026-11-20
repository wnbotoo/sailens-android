package com.sailens.vision.semantic

import android.os.SystemClock
import com.sailens.core.frame.PipelineTimingMode

/** Timing for one scored tensor read through a LiteRT handle. */
class SemanticScoreTiming(sampleOffset: Int? = null, useNeonArgmax: Boolean = false) {
    init {
        require(sampleOffset == null || sampleOffset in 0 until PIXEL_SAMPLE_STRIDE)
        require(!useNeonArgmax || sampleOffset == null)
    }

    /** Slot zero carries the kernel request until JNI writes the timing record. */
    val nativeNanos = LongArray(9) { -1L }.also {
        it[0] = if (useNeonArgmax) REQUEST_NEON else sampleOffset?.toLong() ?: -1L
    }
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
        private const val REQUEST_NEON = -2L

        fun forFrame(frameId: Long, mode: PipelineTimingMode): SemanticScoreTiming = when (mode) {
            PipelineTimingMode.SEM_SIMD, PipelineTimingMode.SEM_NCHW -> SemanticScoreTiming(useNeonArgmax = true)
            PipelineTimingMode.RACE_UNSAMPLED, PipelineTimingMode.SEM_SCALAR -> SemanticScoreTiming()
            PipelineTimingMode.RACE, PipelineTimingMode.SEM_FIRST -> {
                val offset = if (frameId % FRAME_SAMPLE_INTERVAL == 0L) frameId.mod(PIXEL_SAMPLE_STRIDE) else null
                SemanticScoreTiming(offset)
            }
        }
    }
}
