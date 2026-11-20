package com.sailens.runtime

import android.os.SystemClock

/** Timed only for a profiled model call, on the thread doing its preprocessing. */
class InputPreprocessTiming(private val clock: () -> Long = SystemClock::elapsedRealtimeNanos) {
    var cacheWaitNanos: Long = 0L
        private set
    var cacheCopyNanos: Long = 0L
        private set

    internal fun now(): Long = clock()

    internal fun waitedSince(started: Long) {
        cacheWaitNanos += now() - started
    }

    internal fun copiedSince(started: Long) {
        cacheCopyNanos += now() - started
    }
}
