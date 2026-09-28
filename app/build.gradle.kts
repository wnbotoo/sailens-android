import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val enableLitertNpuRuntime = providers.gradleProperty("sailens.enableLitertNpuRuntime")
    .map { it.toBooleanStrict() }
    .getOrElse(false)
val litertNpuRuntimeRoot = rootProject.layout.projectDirectory.dir("litert_npu_runtime_libraries_jit").asFile
val litertNpuRuntimeFeatureModules = listOf(
    "qualcomm_runtime_v81",
    "qualcomm_runtime_v79",
    "qualcomm_runtime_v75",
    "qualcomm_runtime_v73",
    "qualcomm_runtime_v69",
)
val missingLitertNpuRuntimeFeatures = litertNpuRuntimeFeatureModules.filterNot { moduleName ->
    litertNpuRuntimeRoot.resolve(moduleName).resolve("build.gradle.kts").isFile
}
val hasLitertNpuRuntimeStrings =
    litertNpuRuntimeRoot.resolve("runtime_strings").resolve("build.gradle.kts").isFile
if (enableLitertNpuRuntime) {
    check(hasLitertNpuRuntimeStrings && missingLitertNpuRuntimeFeatures.isEmpty()) {
        "sailens.enableLitertNpuRuntime=true requires runtime_strings and all Qualcomm LiteRT NPU " +
            "runtime feature modules under ${litertNpuRuntimeRoot.path}; " +
            "missingFeatures=${missingLitertNpuRuntimeFeatures.joinToString()}, " +
            "runtimeStringsPresent=$hasLitertNpuRuntimeStrings"
    }
}
val availableLitertNpuRuntimeFeatures = litertNpuRuntimeFeatureModules
    .takeIf { enableLitertNpuRuntime }
    .orEmpty()
    .map { moduleName -> ":litert_npu_runtime_libraries_jit:$moduleName" }
    .toSet()

// Build provenance for recorded data (field captures): which code and which model weights produced
// the APK. Guidance's behaviour depends on both, and the weights are not in git (bring your own
// model), so the commit alone does not identify a baseline.
//
// Commit: HEAD, with "-dirty" when `git status --porcelain` lists anything -- modified or untracked
// files alike (an untracked source file is compiled too). The ignored weights under assets/ never
// appear there. Empty when git is unavailable (a source archive); the PC tools then refuse to pool
// such captures. scripts/test_build_identity.py locks this command's semantics.
val buildGitSha: String = runCatching {
    val sha = providers.exec { commandLine("git", "rev-parse", "HEAD") }
        .standardOutput.asText.get().trim()
    val dirty = providers.exec { commandLine("git", "status", "--porcelain") }
        .standardOutput.asText.get().isNotBlank()
    if (Regex("[0-9a-f]{40}").matches(sha)) sha + (if (dirty) "-dirty" else "") else ""
}.getOrDefault("")

/**
 * Writes `build_provenance.json` into a generated assets directory: the commit and the SHA-256 of
 * every packaged `.tflite`. Hashing happens here, once per changed model, never at capture time.
 */
abstract class GenerateBuildProvenance : DefaultTask() {
    @get:Input
    abstract val gitSha: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val models: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val hashes = models.files.filter { it.isFile }.sortedBy { it.name }.map { file ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            file.name to "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
        }
        val artifacts = hashes.joinToString(",") { (name, hash) -> "\"$name\":\"$hash\"" }
        val out = outputDir.get().asFile
        out.mkdirs()
        out.resolve("build_provenance.json")
            .writeText("{\"gitSha\":\"${gitSha.get()}\",\"modelArtifacts\":{$artifacts}}\n")
    }
}

val generateBuildProvenance = tasks.register<GenerateBuildProvenance>("generateBuildProvenance") {
    gitSha.set(buildGitSha)
    models.from(fileTree("src/main/assets") { include("*.tflite") })
}

android {
    namespace = "com.sailens"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.sailens.reference"
        // Android 12 (API 31). 31 is the floor for Build.SOC_MANUFACTURER/SOC_MODEL used by
        // DeviceHardwareProfileProvider, and keeps device reach broad for the GPU-only release.
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        // Reference-host identity, surfaced on the Settings screen. This app exists to assemble and
        // validate Sailens Android; the store product lives in the separate official distribution.
        // Apache-2.0 requires recipients to get the license; an app is the one place users
        // actually look, so it is shown in-product
        // rather than only in the repo. A redistribution under different terms overrides these two
        // and nothing else.
        buildConfigField("String", "APP_LICENSE", "\"Apache-2.0\"")
        buildConfigField("String", "APP_SOURCE_URL", "\"https://github.com/wnbotoo/sailens-android\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // arm64-v8a covers every modern 64-bit Android SoC (Qualcomm, MediaTek Dimensity, Google
        // Tensor) — this is NOT a vendor restriction. It drops 32-bit-only and x86 (emulator/ChromeOS)
        // to shrink the APK and native build time for the packaged native libs (OpenCV plus
        // libsailens_runtime / libsailens_vision / libsailens_guidance).
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    if (enableLitertNpuRuntime) {
        dynamicFeatures += availableLitertNpuRuntimeFeatures
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "SHOW_DIAGNOSTICS", "true")
        }
        release {
            buildConfigField("boolean", "SHOW_DIAGNOSTICS", "false")
            // R8 relies on the default native-methods keep rule plus the JNI keeps in
            // sailens-runtime/vision/guidance consumer-rules.pro. Smoke-test a release build on
            // device before relying on it: name-based JNI / reflection break at runtime, not build.
            isMinifyEnabled = true
            isShrinkResources = true
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
        compose = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            // Only the Qualcomm NPU path needs extracted native libs: LiteRT's QNN dispatch dlopen()s
            // libLiteRtDispatch_Qualcomm.so from the app's lib dir (/data/app/.../lib/arm64), which is
            // empty under the modern uncompressed-in-APK default. GPU-only builds don't need it, so tie
            // legacy packaging to the NPU runtime flag to keep the default (GPU) release install smaller.
            // NOTE: native-lib load failures surface at runtime, not build — smoke-test a release build.
            useLegacyPackaging = enableLitertNpuRuntime
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(generateBuildProvenance, GenerateBuildProvenance::outputDir)
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.window.size)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    // Required for LiteRT's downloadLibrary() to install the on-demand Qualcomm NPU runtime module.
    implementation(libs.google.litert)
    implementation(libs.google.play.feature.delivery)
    implementation(libs.google.play.feature.delivery.ktx)
    if (enableLitertNpuRuntime) {
        implementation(project(":litert_npu_runtime_libraries_jit:runtime_strings"))
    }
    implementation(project(":sailens-core"))
    implementation(project(":sailens-runtime"))
    implementation(project(":sailens-vision"))
    implementation(project(":sailens-vlm"))
    implementation(project(":sailens-guidance"))
    implementation(project(":sailens-camera"))
    implementation(project(":sailens-output"))
    implementation(project(":sailens-describe"))
    implementation(project(":sailens-shell"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
