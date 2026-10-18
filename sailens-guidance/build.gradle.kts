plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)

}

android {
    namespace = "com.sailens.guidance"
    compileSdk = 37

    defaultConfig {
        minSdk = 31

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        // Build the native lib only for the target arm64 SoC (Snapdragon 8 Gen 2+).
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
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
    buildFeatures {
        buildConfig = true
    }
    testOptions {
        // SemanticModelPreflight takes a Context it only uses to reach assets; a ModelSource.File
        // never touches it. Letting the stub return defaults is what makes the preflight path
        // testable on the JVM -- which is itself the proof that it loads no native runtime.
        unitTests.isReturnDefaultValues = true
        // M0b: replay a recorded capture on the JVM (CaptureReplayTest), e.g.
        //   gradlew :sailens-guidance:testDebugUnitTest --tests "*CaptureReplayTest*" -PreplayCapture=<dir>
        // Captures contain faces and places: they stay on the developer's machine, never in CI.
        unitTests.all { test ->
            test.systemProperty("sailens.replay.capture", project.findProperty("replayCapture")?.toString().orEmpty())
            test.systemProperty("sailens.replay.out", layout.buildDirectory.dir("replay").get().asFile.path)
            // M3a evaluation (GroundContactCaptureEvaluationTest): the phone height to assume, metres.
            test.systemProperty("sailens.m3a.phoneHeight", project.findProperty("m3aPhoneHeight")?.toString().orEmpty())
            // A capture path given means this run must actually replay, not reuse a cached result.
            if (project.hasProperty("replayCapture")) test.outputs.upToDateWhen { false }
            // M2a scenario goldens: -PupdateGoldens rewrites them in the source tree.
            test.systemProperty("sailens.updateGoldens", project.hasProperty("updateGoldens").toString())
            test.systemProperty("sailens.goldens.dir", file("src/test/resources/scenarios").absolutePath)
            if (project.hasProperty("updateGoldens")) test.outputs.upToDateWhen { false }
        }
    }
}

dependencies {

    api(project(":sailens-core"))
    api(project(":sailens-vision"))
    implementation(project(":sailens-runtime"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.koin.android)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
