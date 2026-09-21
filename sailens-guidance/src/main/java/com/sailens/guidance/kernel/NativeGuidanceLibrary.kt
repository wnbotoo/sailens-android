package com.sailens.guidance.kernel

internal object NativeGuidanceLibrary {
    const val NAME = "sailens_guidance"

    val isAvailable: Boolean

    /**
     * Why the library is unavailable, or null once it has loaded.
     *
     * The library binds its methods in `JNI_OnLoad` (see
     * `sailens-guidance/src/main/cpp/sailens_guidance_jni.cpp`), so a class, method or signature
     * mismatch fails the whole load rather than leaving a single method unbound until it is first
     * called. Every caller falls back to Kotlin when [isAvailable] is false, which keeps that
     * survivable but silent; this holds the reason so diagnostics and the binding-coverage test
     * can report it.
     */
    val loadError: Throwable?

    init {
        val result = runCatching { System.loadLibrary(NAME) }
        isAvailable = result.isSuccess
        loadError = result.exceptionOrNull()
    }
}
