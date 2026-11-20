package com.sailens.runtime

import android.os.SystemClock

/** Timed only for a profiled model call, on the thread doing its preprocessing. */
class InputPreprocessTiming(
    private val clock: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    var cacheWaitNanos: Long = 0L
        private set
    var cacheAwaitNanos: Long = 0L
        private set
    var cacheCopyNanos: Long = 0L
        private set
    var kotlinHwcToNchwNanos: Long? = null
        private set
    private var nativeYuv: LongArray? = null
    private var resizeFilter: ResizeFilter? = null
    private var outputLayout: ImageTensorLayout? = null

    val jniArrayGetNanos: Long? get() = nativeYuv?.get(0)?.takeIf { it >= 0L }
    val jniArrayReleaseNanos: Long? get() = nativeYuv?.get(1)?.takeIf { it >= 0L }
    val nativeYuvBodyNanos: Long? get() = nativeYuv?.get(2)?.takeIf { it >= 0L }
    /** The four JNI isCopy flags in Y, U, V and output order from least to most significant bit. */
    val jniArrayCopyMask: Int? get() = nativeYuv?.get(3)?.takeIf { it >= 0L }?.toInt()
    val nativeResizeFilter: ResizeFilter? get() = resizeFilter.takeIf { nativeYuvBodyNanos != null }
    val nativeOutputLayout: ImageTensorLayout? get() = outputLayout.takeIf { nativeYuvBodyNanos != null }

    internal fun now(): Long = clock()

    internal fun waitedSince(started: Long) {
        cacheWaitNanos += now() - started
    }

    internal fun awaitedProducerSince(started: Long) {
        val duration = now() - started
        cacheAwaitNanos += duration
        cacheWaitNanos += duration
    }

    internal fun copiedSince(started: Long) {
        cacheCopyNanos += now() - started
    }

    internal fun beginNativeYuv(filter: ResizeFilter, layout: ImageTensorLayout = ImageTensorLayout.NHWC): LongArray {
        resizeFilter = filter
        outputLayout = layout
        return LongArray(4) { -1L }.also { nativeYuv = it }
    }

    internal fun transposedSince(started: Long) {
        kotlinHwcToNchwNanos = now() - started
    }
}
