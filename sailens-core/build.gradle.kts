plugins {
    alias(libs.plugins.android.library)
}

kotlin {
    explicitApi()
}

android {
    namespace = "com.sailens.core"
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
    // sailens-core is deliberately small (architecture.md §6.7): stable contracts and value types
    // shared by lower modules, and nothing that needs an Android service or a third-party runtime.
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}
