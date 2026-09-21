plugins {
    alias(libs.plugins.android.library)
}

kotlin {
    explicitApi()
}

android {
    namespace = "com.sailens.describe"
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
}

dependencies {

    // Describe product logic: prompt policy, snapshot freshness, request scheduling (§6.4).
    implementation(libs.androidx.core.ktx)
    implementation(project(":sailens-core"))
    // describe returns Flow<SceneDescriptionChunk>, so the contract is part of its own API.
    api(project(":sailens-vlm"))
    implementation(project(":sailens-camera"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
