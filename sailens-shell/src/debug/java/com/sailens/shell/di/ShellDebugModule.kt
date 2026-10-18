package com.sailens.shell.di

import com.sailens.guidance.config.AnalysisConfig
import com.sailens.guidance.config.PerceptionConfig
import com.sailens.guidance.processor.perception.PerceptionProfileManager
import com.sailens.guidance.trace.capture.CaptureSchema
import com.sailens.guidance.trace.capture.RegressionManifest
import com.sailens.guidance.trace.regression.RegressionRecorder
import com.sailens.guidance.semantics.NavigationSemantics
import com.sailens.guidance.service.TraceServiceDecorator
import com.sailens.shell.capture.AndroidCaptureClock
import com.sailens.shell.capture.AndroidCaptureSensorSource
import com.sailens.shell.app.BuildIdentity
import com.sailens.shell.app.GuidanceStartGate
import com.sailens.shell.app.HardwareKeyHandler
import com.sailens.shell.capture.CaptureExporter
import com.sailens.shell.capture.CaptureRetention
import com.sailens.shell.capture.CapturingTraceService
import com.sailens.shell.capture.FieldCaptureController
import com.sailens.shell.capture.FieldCaptureStartGate
import com.sailens.shell.capture.FieldCaptureSettingsStore
import com.sailens.shell.capture.FieldCaptureViewModel
import com.sailens.shell.capture.GuidanceSessionMonitor
import com.sailens.shell.capture.MissedAlertMarkers
import com.sailens.shell.capture.RegressionCapture
import com.sailens.shell.capture.VolumeDownMarkerKeyHandler
import com.sailens.shell.capture.YuvImageJpegEncoder
import com.sailens.shell.capture.captureDeviceInfo
import com.sailens.shell.consistency.ModelConsistencyChecker
import com.sailens.shell.consistency.ModelConsistencyReportWriter
import com.sailens.shell.consistency.ModelConsistencyViewModel
import com.sailens.shell.trace.TraceReplayViewModel
import com.sailens.vision.taxonomy.CityscapesTaxonomy
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.util.concurrent.Executors

/**
 * Debug-only DI: the trace-replay ViewModel, field capture (M0a) and the model consistency check exist
 * only in the debug variant.
 */
val shellDebugModule: Module = module {
    viewModel {
        TraceReplayViewModel(
            logger = get(),
            listTraceSessionsUseCase = get(),
            loadTraceReplayReportUseCase = get(),
            loadLatestTraceReplayReportUseCase = get(),
            evaluateTraceReplayBudgetUseCase = get(),
        )
    }

    single { FieldCaptureSettingsStore(androidContext()) }
    // The model-regression record (M0b). Guidance's DI finds it as its RegressionRecorder.
    single {
        val profiles = get<PerceptionProfileManager>()
        val analysis = get<AnalysisConfig>()
        val semantics = get<NavigationSemantics>()
        RegressionCapture(
            manifestBase = {
                RegressionManifest(
                    // The config the session runs with: the profile was activated before the trace
                    // session (and so this capture) started.
                    perceptionConfig = CaptureSchema.json.encodeToJsonElement(PerceptionConfig.serializer(), profiles.activeConfig),
                    analysisConfig = CaptureSchema.json.encodeToJsonElement(AnalysisConfig.serializer(), analysis),
                    taxonomyId = semantics.taxonomyId.value,
                    // Today's level is the box position; the M3a ground contact beside it is governed
                    // by perceptionConfig.groundContact, recorded above.
                    depthSource = "position",
                )
            },
            encoder = YuvImageJpegEncoder,
            clock = AndroidCaptureClock,
            log = get(),
            writerDispatcher = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "regression-capture") }
                .asCoroutineDispatcher(),
        )
    }
    single<RegressionRecorder> { get<RegressionCapture>() }
    // Created at app start so retention runs then, even when capture is switched off.
    single(createdAtStart = true) {
        val settings = get<FieldCaptureSettingsStore>()
        val root = File(androidContext().filesDir, CAPTURES_DIR)
        FieldCaptureController(
            root = root,
            sessionMode = settings::takeSessionMode,
            frameSource = get(),
            cameraFacts = get(),
            sensors = AndroidCaptureSensorSource(androidContext()),
            encoder = YuvImageJpegEncoder,
            clock = AndroidCaptureClock,
            deviceInfo = captureDeviceInfo(androidContext(), getOrNull<BuildIdentity>()),
            log = get(),
            exporter = CaptureExporter(
                root = root,
                exportDir = File(androidContext().cacheDir, EXPORTS_DIR),
            ),
            retention = CaptureRetention(root, maxTotalBytesSetting = settings::capBytes),
            regression = get(),
            sessionLabels = settings::sessionLabels,
        )
    }
    single<TraceServiceDecorator> {
        val controller = get<FieldCaptureController>()
        val sessions = get<GuidanceSessionMonitor>()
        val regression = get<RegressionCapture>()
        TraceServiceDecorator { base -> CapturingTraceService(base, controller, sessions, regression) }
    }
    single { MissedAlertMarkers(androidContext(), get()) }
    single<HardwareKeyHandler> { VolumeDownMarkerKeyHandler(get(), get()) }
    single<GuidanceStartGate> { FieldCaptureStartGate(androidContext(), get()) }
    viewModel { FieldCaptureViewModel(get(), get()) }

    single { GuidanceSessionMonitor() }
    viewModel {
        val perception = get<PerceptionConfig>()
        val semantics = get<NavigationSemantics>()
        ModelConsistencyViewModel(
            checker = ModelConsistencyChecker.liteRt(
                context = androidContext(),
                semanticConfig = get(),
                detectionConfig = get(),
                detectionThreshold = perception.minObstacleConfidence,
                modelSourceResolver = get(),
                snapshots = get(),
                log = get(),
            ),
            sessions = get(),
            writer = ModelConsistencyReportWriter(File(androidContext().filesDir, CONSISTENCY_DIR)),
            build = getOrNull<BuildIdentity>(),
            semanticLabel = if (semantics.taxonomyId == CityscapesTaxonomy.id) {
                CityscapesTaxonomy::label
            } else {
                { classId -> "class_$classId" }
            },
            log = get(),
        )
    }
}

/** Under `filesDir`; excluded from backup and device transfer (app `backup_rules.xml`). */
private const val CAPTURES_DIR = "captures"

/** Under `filesDir`: consistency reports (numbers only, no frames). */
private const val CONSISTENCY_DIR = "diagnostics/consistency"

/** Under `cacheDir` (not backed up); the only capture path exposed through FileProvider. */
private const val EXPORTS_DIR = "capture_exports"
