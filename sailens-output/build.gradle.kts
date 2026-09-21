plugins {
    alias(libs.plugins.android.library)
}

kotlin {
    explicitApi()
}

android {
    namespace = "com.sailens.output"
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

    // Output mechanism only: TTS, audio focus, screen-reader detection, haptics, clause
    // buffering. It must not learn what Guidance or Describe are (architecture.md §6.6), which is
    // why the only Sailens dependency here is the smallest shared contract module.
    implementation(libs.androidx.core.ktx)
    implementation(project(":sailens-core"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
