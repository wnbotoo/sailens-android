package com.sailens.shell.settings

/**
 * App build identity shown on the Settings screen. Provided by the `app` module from `BuildConfig`,
 * so a redistribution changes its license/source declaration without touching UI code.
 */
data class AppInfo(
    val versionName: String,
    val versionCode: Long,
    /** SPDX identifier this build is distributed under, e.g. `Apache-2.0`. */
    val license: String,
    /** Where this build's corresponding source lives. */
    val sourceUrl: String,
)
