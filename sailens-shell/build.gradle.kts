import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.sailens.shell"
    compileSdk = 37

    defaultConfig {
        minSdk = 31

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.all { test ->
            // SceneEventStringsTest reads the string files themselves: an edited string must re-run it.
            test.inputs.dir("src/main/res").withPathSensitivity(PathSensitivity.RELATIVE)
            // SimulatorMirrorPinTest hashes the files the session simulator mirrors: any edit to one, a space
            // included, must re-run it, though it compiles to the same classes.
            test.inputs.files(
                "src/main/java/com/sailens/shell/guidance/screen/SceneAnalysisViewModel.kt",
                "src/main/java/com/sailens/shell/device/HapticManager.kt",
                "src/main/java/com/sailens/shell/device/GuidanceAnnouncements.kt",
                "src/main/java/com/sailens/shell/di/ShellModule.kt",
            ).withPathSensitivity(PathSensitivity.RELATIVE)
            // Session timelines (SessionTimelineTest): -PupdateGoldens rewrites them in the source tree.
            test.systemProperty("sailens.updateGoldens", project.hasProperty("updateGoldens").toString())
            test.systemProperty("sailens.timelines.dir", file("src/test/resources/timelines").absolutePath)
            if (project.hasProperty("updateGoldens")) test.outputs.upToDateWhen { false }
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.window.size)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(project(":sailens-camera"))
    implementation(project(":sailens-output"))
    implementation(project(":sailens-describe"))
    implementation(project(":sailens-core"))
    implementation(project(":sailens-guidance"))
    testImplementation(libs.junit)
    testImplementation(testFixtures(project(":sailens-guidance")))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
