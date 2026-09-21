package com.sailens.shell.about

/** A third-party component shown on the open-source licenses screen. */
data class OssLibrary(
    val name: String,
    val license: String,
    val url: String,
)

/**
 * Curated list of the notable open-source (and one proprietary runtime) components Sailens ships.
 * Kept as a hand-maintained list to avoid pulling a heavy license-aggregation dependency into the
 * release build; revisit before each release when dependencies change.
 */
val SailensOssLibraries: List<OssLibrary> = listOf(
    OssLibrary("Jetpack Compose", "Apache-2.0", "https://developer.android.com/jetpack/compose"),
    OssLibrary("Material Components (Material 3)", "Apache-2.0", "https://github.com/material-components/material-components-android"),
    OssLibrary("AndroidX Navigation 3", "Apache-2.0", "https://developer.android.com/guide/navigation/navigation-3"),
    OssLibrary("AndroidX Lifecycle", "Apache-2.0", "https://developer.android.com/jetpack/androidx/releases/lifecycle"),
    OssLibrary("CameraX", "Apache-2.0", "https://developer.android.com/training/camerax"),
    OssLibrary("Accompanist", "Apache-2.0", "https://github.com/google/accompanist"),
    OssLibrary("Koin", "Apache-2.0", "https://github.com/InsertKoinIO/koin"),
    OssLibrary("Kotlin Coroutines", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
    OssLibrary("kotlinx.serialization", "Apache-2.0", "https://github.com/Kotlin/kotlinx.serialization"),
    OssLibrary("kotlinx-datetime", "Apache-2.0", "https://github.com/Kotlin/kotlinx-datetime"),
    OssLibrary("LiteRT (TensorFlow Lite)", "Apache-2.0", "https://github.com/google-ai-edge/LiteRT"),
    OssLibrary("OpenCV", "Apache-2.0", "https://github.com/opencv/opencv"),
    OssLibrary("Qualcomm AI Engine Direct (QNN)", "Proprietary — Qualcomm", "https://www.qualcomm.com/developer/software/qualcomm-ai-engine-direct-sdk"),
)
