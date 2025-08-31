import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.friady.sailens.data"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 34

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
    buildFeatures {
        mlModelBinding = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(project(":domain"))
    implementation(libs.koin.android)
    implementation(libs.google.litert)
//    implementation(libs.google.litert.gpu)
//    implementation(libs.google.mediapipe)
    // 2. 引入 support 库，但排除它内部引用的旧版 litert-api
    implementation(libs.google.litert.support) {
        exclude(group = "com.google.ai.edge.litert", module = "litert-api")
    }

    // 3. 引入 metadata 库，同样排除掉它内部引用的旧版 litert-api
    implementation(libs.google.litert.metadata) {
        exclude(group = "com.google.ai.edge.litert", module = "litert-api")
    }
    implementation(libs.opencv)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}