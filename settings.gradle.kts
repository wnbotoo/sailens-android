pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Sailens"
include(":app")

include(":domain")
include(":sailens-shell")
include(":data")
include(":sailens-camera")
include(":sailens-core")
include(":sailens-runtime")
include(":sailens-vision")

val enableLitertNpuRuntime = providers.gradleProperty("sailens.enableLitertNpuRuntime")
    .map { it.toBooleanStrict() }
    .getOrElse(false)
val litertNpuRuntimeRoot = file("litert_npu_runtime_libraries_jit")
val litertNpuRuntimeModules = listOf(
    "runtime_strings",
    "qualcomm_runtime_v81",
    "qualcomm_runtime_v79",
    "qualcomm_runtime_v75",
    "qualcomm_runtime_v73",
    "qualcomm_runtime_v69",
)
if (enableLitertNpuRuntime) {
    val missingModules = litertNpuRuntimeModules.filterNot { moduleName ->
        litertNpuRuntimeRoot.resolve(moduleName).resolve("build.gradle.kts").isFile
    }
    check(missingModules.isEmpty()) {
        "sailens.enableLitertNpuRuntime=true requires local LiteRT NPU runtime modules under " +
            "${litertNpuRuntimeRoot.path}; missing=${missingModules.joinToString()}. " +
            "Set -Psailens.enableLitertNpuRuntime=false for builds that should not package them."
    }
    litertNpuRuntimeModules.forEach { moduleName ->
        include(":litert_npu_runtime_libraries_jit:$moduleName")
    }
}
