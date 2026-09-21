plugins {
    alias(libs.plugins.android.library)
}

kotlin {
    explicitApi()
}

android {
    namespace = "com.sailens.vlm"
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
        // The engine calls SystemClock.uptimeMillis() for its latency numbers and holds a Context
        // it only hands to the runtime factory. Neither is worth an instrumentation test to
        // exercise the cancellation path, which is pure coroutine wiring.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {

    // The VLM contract plus the LiteRT-shaped engine shell. It receives a complete prompt and
    // returns streamed text; it does not know that the user is blind or that Sailens navigates
    // (architecture.md §6.4). A real LiteRT-LM runtime belongs in its own module when it exists,
    // so a Guidance-only edition does not pay for it.
    implementation(libs.androidx.core.ktx)
    implementation(project(":sailens-core"))
    implementation(project(":sailens-runtime"))
    implementation(libs.google.litert)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
}
