package com.sailens.data.source.ml

import android.content.Context
import android.os.Build
import com.sailens.core.log.LogService
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.BuiltinNpuAcceleratorProvider
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.NpuCompatibilityChecker
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

internal data class LiteRtCompiledModelHandle(
    val model: CompiledModel,
    val environment: Environment,
) {
    fun close() {
        model.close()
        environment.close()
    }
}

/**
 * Builds a LiteRT [CompiledModel] + [Environment] for one accelerator attempt.
 *
 * TODO(npu-vendor): this is the ONLY vendor-coupled point in the ML layer. GPU/CPU take the plain
 * path ([Environment.create] + a bare [CompiledModel.Options]); the `data/ml/session/` layer and
 * everything above it stay vendor-agnostic (they only ever see the [Accelerator] enum). Today the
 * NPU path is Qualcomm-only, coupled in exactly four spots:
 *   1. [requireQualcommDispatchLibrary] — hardcodes the `libLiteRtDispatch_Qualcomm.so` name.
 *   2. [buildOptions] — always attaches [CompiledModel.QualcommOptions] on NPU.
 *   3. [supportsQualcommHmx] — Qualcomm HMX gate.
 *   4. [QualcommNpuDiagnostics] — Qualcomm-specific dump/profiling.
 *
 * To also support MediaTek / Google Tensor NPU: LiteRT's [BuiltinNpuAcceleratorProvider] is already
 * vendor-generic (it resolves the right runtime per device), and the runtime delivery already ships
 * Qualcomm + GoogleTensor dispatch libs. Extract an `NpuVendorSupport` seam (an interface in this
 * package, NOT a new module) selected by `Build.SOC_MANUFACTURER`:
 *     matches(soc) · dispatchLibraryName · applyOptions(options, ctx) · diagnostics
 * and have the four spots above delegate to the chosen vendor (Qualcomm impl = today's code). Do
 * this when wiring the 2nd vendor, against its real options/dispatch requirements — not speculatively.
 */
internal object LiteRtCompiledModelFactory {

    fun create(
        context: Context,
        source: ModelSource,
        accelerator: Accelerator,
        logService: LogService? = null,
    ): LiteRtCompiledModelHandle {
        val appContext = context.applicationContext
        val environment = createEnvironment(appContext, accelerator, logService)
        return try {
            val options = buildOptions(appContext, accelerator, logService)
            // Same compile path for either source; LiteRT mmaps both (nativeCreateFromAsset /
            // nativeCreateFromFile), so an asset-pack/downloaded File loads exactly like a bundled asset.
            val model = when (source) {
                is ModelSource.Asset ->
                    CompiledModel.create(context.assets, source.assetPath, options, environment)
                is ModelSource.File ->
                    CompiledModel.create(source.file.absolutePath, options, environment)
            }
            LiteRtCompiledModelHandle(
                model = model,
                environment = environment,
            )
        } catch (error: CancellationException) {
            environment.close()
            throw error
        } catch (error: Exception) {
            environment.close()
            throw compileFailure(source, accelerator, environment, error)
        } catch (error: UnsatisfiedLinkError) {
            environment.close()
            throw compileFailure(source, accelerator, environment, error)
        }
    }

    private fun buildOptions(
        context: Context,
        accelerator: Accelerator,
        logService: LogService?,
    ): CompiledModel.Options {
        val options = CompiledModel.Options(accelerator)
        if (accelerator == Accelerator.NPU) {
            val diagnostics = QualcommNpuDiagnostics.from(context)
            // Without explicit Qualcomm options the HTP runs at a conservative default clock and
            // op-prep level. Pin sustained high performance for the continuous per-frame workload.
            // OPTIMIZATION LEVEL: we run JIT (compiler plugin on-device), and 2.1.5's QualcommOptions
            // has NO compile cache -> the graph recompiles on every launch. O3 makes that on-device
            // compile very slow (obstacle init blocked ~9s mid-compile and looked like a hang), so
            // use HTP_OPTIMIZE_FOR_PREPARE (fastest compile) to keep init responsive. Trade-off: less
            // inference optimization. For production switch to AOT (precompile the model O3 offline,
            // ship the context binary) and this runtime compile cost disappears.
            options.qualcommOptions = CompiledModel.QualcommOptions(
                logLevel = diagnostics.logLevel,
                profiling = diagnostics.profiling,
                irJsonDir = diagnostics.irJsonDir,
                dlcDir = diagnostics.dlcDir,
                htpPerformanceMode =
                    CompiledModel.QualcommOptions.HtpPerformanceMode.SUSTAINED_HIGH_PERFORMANCE,
                optimizationLevel =
                    CompiledModel.QualcommOptions.OptimizationLevel.HTP_OPTIMIZE_FOR_PREPARE,
                // HMX is only available on newer Snapdragon NPUs. Keep SM8450/8 Gen 1 on the
                // default HVX path so forcing NPU does not fail during compile.
                useConvHmx = supportsQualcommHmx(),
                useFoldRelu = true,
            )
            if (diagnostics.enabled) {
                logService?.info(
                    TAG,
                    "LiteRT Qualcomm NPU diagnostics enabled: dumpDir=${diagnostics.dumpDir}"
                )
            }
        }
        return options
    }

    private fun createEnvironment(
        context: Context,
        accelerator: Accelerator,
        logService: LogService?,
    ): Environment {
        val environment = if (accelerator == Accelerator.NPU) {
            createNpuEnvironment(context, logService)
        } else {
            Environment.create()
        }
        return try {
            requireAcceleratorAvailable(environment, accelerator)
            environment
        } catch (error: CancellationException) {
            environment.close()
            throw error
        } catch (error: Exception) {
            environment.close()
            throw error
        } catch (error: UnsatisfiedLinkError) {
            environment.close()
            throw error
        }
    }

    private fun compileFailure(
        source: ModelSource,
        accelerator: Accelerator,
        environment: Environment,
        error: Throwable,
    ): IllegalStateException {
        return IllegalStateException(
            "Failed to compile LiteRT model: model=${source.label}, accelerator=$accelerator, " +
                "availableAccelerators=${environment.safeAvailableAccelerators()}",
            error,
        )
    }

    private fun createNpuEnvironment(
        context: Context,
        logService: LogService?,
    ): Environment {
        val builtinProvider = BuiltinNpuAcceleratorProvider(context)
        val provider = BuiltinNpuAcceleratorProvider(context, ForceNpuCompatibilityChecker)
        val builtinDeviceSupported = builtinProvider.safeIsDeviceSupported()

        // Newer LiteRT (2.1.x) does NOT bundle the Qualcomm NPU runtime in the AAR; it is delivered
        // at runtime via Play (Feature Delivery / AI Pack). Trigger that download once if the runtime
        // is not present yet, then re-check readiness. NOTE: Play only delivers to a Play-based
        // install (internal testing track, or bundletool --local-testing) — a plain `adb install`
        // receives nothing, so on dev side-loads this is a logged no-op and the checks below fail
        // fast. Also requires the Play delivery dependency on the classpath (else NoClassDefFoundError,
        // which is caught and logged here).
        ensureNpuRuntimeDownloaded(provider, logService)

        val libraryReady = provider.safeIsLibraryReady()
        val libraryDir = provider.safeLibraryDir()
        check(libraryReady) {
            "LiteRT NPU runtime is not ready: " +
                "builtinDeviceSupported=$builtinDeviceSupported, " +
                "libraryReady=$libraryReady, " +
                "libraryDir=$libraryDir, " +
                "hardware=${deviceHardwareLabel()}"
        }
        requireQualcommDispatchLibrary(context, provider)
        return runCatching { Environment.create(provider) }
            .getOrElse { error ->
                throw IllegalStateException(
                    "Failed to create LiteRT NPU environment: " +
                        "builtinDeviceSupported=$builtinDeviceSupported, " +
                        "libraryReady=$libraryReady, " +
                        "libraryDir=$libraryDir, " +
                        "hardware=${deviceHardwareLabel()}",
                    error,
                )
            }
    }

    // downloadLibrary() is a suspend, Play-backed fetch of the vendor NPU runtime (the modern
    // replacement for bundling libLiteRtDispatch_Qualcomm.so — Google moved the libs out of the AAR,
    // not removed them). Model init already runs off the main thread, and Play resolves the request
    // on its own callback threads (independent of the caller dispatcher), so bridging this one-time
    // setup with runBlocking will not deadlock. Best-effort: any failure (no Play delivery for a
    // side-loaded install, missing Play dependency, unsupported device) is logged; readiness is then
    // re-checked by the caller and the dispatch-library guard fails fast if the runtime never landed.
    private fun ensureNpuRuntimeDownloaded(
        provider: BuiltinNpuAcceleratorProvider,
        logService: LogService?,
    ) {
        if (provider.safeIsLibraryReady()) return
        runCatching { runBlocking { provider.downloadLibrary() } }
            .onSuccess {
                logService?.info(
                    TAG,
                    "LiteRT NPU runtime download attempted: libraryReady=${provider.safeIsLibraryReady()}, " +
                        "libraryDir=${provider.safeLibraryDir()}, hardware=${deviceHardwareLabel()}"
                )
            }
            .onFailure { error ->
                logService?.warning(
                    TAG,
                    "LiteRT NPU runtime download unavailable (Play delivery needs a Play/bundletool " +
                        "install, not plain adb install): hardware=${deviceHardwareLabel()}",
                    throwable = error,
                )
            }
    }

    private fun BuiltinNpuAcceleratorProvider.safeIsDeviceSupported(): Boolean {
        return runCatching { isDeviceSupported() }.getOrDefault(false)
    }

    private fun BuiltinNpuAcceleratorProvider.safeIsLibraryReady(): Boolean {
        return runCatching { isLibraryReady() }.getOrDefault(false)
    }

    private fun BuiltinNpuAcceleratorProvider.safeLibraryDir(): String {
        return runCatching { getLibraryDir() }
            .getOrElse { "unavailable:${it::class.simpleName}:${it.message}" }
    }

    // LiteRT loads the Qualcomm NPU dispatch runtime (libLiteRtDispatch_Qualcomm.so) from the app's
    // native lib dir (bundled in jniLibs) or the provider's downloaded dir. When it is absent,
    // CompiledModel.create does NOT fail — it silently falls back to XNNPACK/CPU while the app still
    // reports accelerator=NPU. Observed in logcat as:
    //   E litert [litert_dispatch.cc:112] No dispatch library found in /data/app/.../lib/arm64
    //   I tflite Replacing 393 out of 401 node(s) with delegate (TfLiteXNNPackDelegate) ...
    // Fail fast so an explicit NPU request that can't actually reach the HTP surfaces as an init
    // failure instead of a fake success. (Assumes extracted native libs; if a future build packages
    // QNN libs uncompressed and LiteRT still resolves them, relax this to a presence-in-APK check.)
    private fun requireQualcommDispatchLibrary(
        context: Context,
        provider: BuiltinNpuAcceleratorProvider,
    ) {
        val searchDirs = buildList {
            context.applicationInfo.nativeLibraryDir?.let(::add)
            provider.safeLibraryDir().takeIf { dir -> !dir.startsWith("unavailable:") }?.let(::add)
        }.distinct()
        val found = searchDirs.any { dir -> File(dir, QUALCOMM_DISPATCH_LIBRARY).exists() }
        check(found) {
            "LiteRT NPU dispatch library $QUALCOMM_DISPATCH_LIBRARY not found; an explicit NPU " +
                "request would silently fall back to XNNPACK/CPU. Bundle the QNN runtime under " +
                "jniLibs/arm64-v8a (or deliver it via Play) for ${deviceHardwareLabel()}. " +
                "searched=$searchDirs"
        }
    }

    private fun requireAcceleratorAvailable(
        environment: Environment,
        accelerator: Accelerator,
    ) {
        val availableAccelerators = environment.getAvailableAccelerators()
        check(accelerator in availableAccelerators) {
            "LiteRT environment does not expose requested accelerator: requested=$accelerator, " +
                "available=${availableAccelerators.joinToString()}, hardware=${deviceHardwareLabel()}"
        }
    }

    private fun Environment.safeAvailableAccelerators(): String {
        return runCatching { getAvailableAccelerators().joinToString() }
            .getOrElse { "unavailable:${it::class.simpleName}:${it.message}" }
    }

    private fun supportsQualcommHmx(): Boolean {
        val manufacturer = Build.SOC_MANUFACTURER.uppercase(Locale.US)
        val model = Build.SOC_MODEL.uppercase(Locale.US)
        val isQualcomm = manufacturer == "QTI" || manufacturer == "QUALCOMM"
        return isQualcomm && model in QUALCOMM_HMX_SOCS
    }

    private fun deviceHardwareLabel(): String {
        return "soc=${Build.SOC_MANUFACTURER}/${Build.SOC_MODEL}, device=${Build.MANUFACTURER}/${Build.MODEL}"
    }

    private object ForceNpuCompatibilityChecker : NpuCompatibilityChecker {
        override fun isDeviceSupported(): Boolean = true
    }

    private data class QualcommNpuDiagnostics(
        val enabled: Boolean,
        val dumpDir: String?,
        val logLevel: CompiledModel.QualcommOptions.LogLevel? = null,
        val profiling: CompiledModel.QualcommOptions.Profiling? = null,
        val irJsonDir: String? = null,
        val dlcDir: String? = null,
    ) {
        companion object {
            fun from(context: Context): QualcommNpuDiagnostics {
                val marker = File(context.filesDir, DIAGNOSTICS_MARKER_FILE)
                if (!marker.exists()) return QualcommNpuDiagnostics(enabled = false, dumpDir = null)

                val dumpRoot = File(context.filesDir, DIAGNOSTICS_DUMP_DIR).apply { mkdirs() }
                val irJsonDir = File(dumpRoot, "ir_json").apply { mkdirs() }
                val dlcDir = File(dumpRoot, "dlc").apply { mkdirs() }
                return QualcommNpuDiagnostics(
                    enabled = true,
                    dumpDir = dumpRoot.absolutePath,
                    logLevel = CompiledModel.QualcommOptions.LogLevel.DEBUG,
                    profiling = CompiledModel.QualcommOptions.Profiling.OPTRACE,
                    irJsonDir = irJsonDir.absolutePath,
                    dlcDir = dlcDir.absolutePath,
                )
            }
        }
    }

    private val QUALCOMM_HMX_SOCS = setOf(
        "SM8550",
        "SM8650",
        "SM8750",
        "SM8850",
    )

    private const val TAG = "LiteRtCompiledModel"
    private const val QUALCOMM_DISPATCH_LIBRARY = "libLiteRtDispatch_Qualcomm.so"
    private const val DIAGNOSTICS_MARKER_FILE = "litert_npu_diagnostics.enabled"
    private const val DIAGNOSTICS_DUMP_DIR = "litert_npu_diagnostics"
}
