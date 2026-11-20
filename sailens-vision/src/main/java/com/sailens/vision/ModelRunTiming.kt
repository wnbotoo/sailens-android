package com.sailens.vision

import android.os.SystemClock
import com.sailens.runtime.InputPreprocessTiming

/**
 * Checkpoints for one profiled model call. Written by the runner and read after its coroutine
 * has completed. Missing checkpoints distinguish an incomplete call from a zero-duration stage.
 */
class ModelRunTiming(val queuedNanos: Long) {
    internal val input = InputPreprocessTiming()
    internal val nativeOutputAccess = LongArray(2)

    var runnerStartedNanos: Long? = null
        internal set
    var preprocessStartedNanos: Long? = null
        internal set
    var preprocessFinishedNanos: Long? = null
        internal set
    var preprocessBackend: String? = null
        internal set
    var inferenceStartedNanos: Long? = null
        internal set
    var inferenceReturnedNanos: Long? = null
        internal set
    var outputCopyNanos: Long? = null
        internal set
    var postprocessFinishedNanos: Long? = null
        internal set
    var postprocessBackend: String? = null
        internal set

    val cacheWaitNanos: Long get() = input.cacheWaitNanos
    val cacheCopyNanos: Long get() = input.cacheCopyNanos
    val outputLockNanos: Long get() = nativeOutputAccess[0]
    val outputUnlockNanos: Long get() = nativeOutputAccess[1]

    internal fun startRunner() {
        runnerStartedNanos = now()
    }

    internal fun finishPreprocess(backend: String) {
        preprocessFinishedNanos = now()
        preprocessBackend = backend
    }

    internal fun finishPostprocess(backend: String) {
        postprocessFinishedNanos = now()
        postprocessBackend = backend
    }

    internal fun now(): Long = SystemClock.elapsedRealtimeNanos()
}
