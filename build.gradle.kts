// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Coordinates for composite-build consumers (architecture.md §4.3, §7). An application that
// includes this build with includeBuild("sailens") asks for "com.sailens:sailens-shell", and
// Gradle substitutes the local :sailens-shell project for it. Nothing is published to Maven --
// the submodule commit is the version boundary.
subprojects {
    group = "com.sailens"
}
