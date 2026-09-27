package com.sailens.shell.di

import com.sailens.guidance.service.TraceServiceDecorator
import com.sailens.shell.capture.AndroidCaptureClock
import com.sailens.shell.capture.AndroidCaptureSensorSource
import com.sailens.shell.app.HardwareKeyHandler
import com.sailens.shell.capture.CaptureExporter
import com.sailens.shell.capture.CapturingTraceService
import com.sailens.shell.capture.FieldCaptureController
import com.sailens.shell.capture.FieldCaptureSettingsStore
import com.sailens.shell.capture.FieldCaptureViewModel
import com.sailens.shell.capture.MissedAlertMarkers
import com.sailens.shell.capture.VolumeDownMarkerKeyHandler
import com.sailens.shell.capture.YuvImageJpegEncoder
import com.sailens.shell.capture.captureDeviceInfo
import com.sailens.shell.trace.TraceReplayViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import java.io.File

/**
 * Debug-only DI: the trace-replay ViewModel and field capture (M0a) exist only in the debug variant.
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
    // Created at app start so retention runs then, even when capture is switched off.
    single(createdAtStart = true) {
        val settings = get<FieldCaptureSettingsStore>()
        FieldCaptureController(
            root = File(androidContext().filesDir, CAPTURES_DIR),
            isEnabled = { settings.enabled.value },
            frameSource = get(),
            cameraFacts = get(),
            sensors = AndroidCaptureSensorSource(androidContext()),
            encoder = YuvImageJpegEncoder,
            clock = AndroidCaptureClock,
            deviceInfo = captureDeviceInfo(androidContext()),
            log = get(),
            exporter = CaptureExporter(
                root = File(androidContext().filesDir, CAPTURES_DIR),
                exportDir = File(androidContext().cacheDir, EXPORTS_DIR),
            ),
        )
    }
    single<TraceServiceDecorator> {
        val controller = get<FieldCaptureController>()
        TraceServiceDecorator { base -> CapturingTraceService(base, controller) }
    }
    single { MissedAlertMarkers(androidContext(), get()) }
    single<HardwareKeyHandler> { VolumeDownMarkerKeyHandler(get(), get()) }
    viewModel { FieldCaptureViewModel(get(), get()) }
}

/** Under `filesDir`; excluded from backup and device transfer (app `backup_rules.xml`). */
private const val CAPTURES_DIR = "captures"

/** Under `cacheDir` (not backed up); the only capture path exposed through FileProvider. */
private const val EXPORTS_DIR = "capture_exports"
