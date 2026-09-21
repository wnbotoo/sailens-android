package com.sailens.shell.di

import com.sailens.core.log.LogService
import com.sailens.shell.FileLogService
import com.sailens.shell.diagnostics.GuidanceDiagnosticsStore
import com.sailens.output.AccessibilityStatusProvider
import com.sailens.shell.device.HapticManager
import com.sailens.shell.app.ConfigurationFailureSignal
import com.sailens.shell.device.GuidanceAnnouncements
import com.sailens.shell.device.SceneEventTextResolver
import com.sailens.output.SpeechManager
import com.sailens.shell.describe.DescribeViewModel
import com.sailens.shell.guidance.screen.SceneAnalysisViewModel
import com.sailens.shell.guidance.settings.GuidanceSettingsStore
import com.sailens.shell.guidance.settings.PerceptionSettingsStore
import com.sailens.shell.settings.SettingsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val shellModule = module {
    // FileLogService writes to app-internal files, so it is an application-platform implementation
    // rather than a shared contract (architecture.md §6.7). The interface lives in sailens-core.
    single<LogService> { FileLogService(androidContext()) }
    single { HapticManager(androidContext()) }
    single { SceneEventTextResolver(androidContext()) }
    single { SpeechManager(androidContext(), get()) }
    single { GuidanceAnnouncements(textResolver = get()) }
    single {
        ConfigurationFailureSignal(
            speechManager = get(),
            hapticManager = get(),
            logService = get(),
        )
    }
    single { AccessibilityStatusProvider(androidContext()) }
    single { GuidanceSettingsStore(androidContext()) }
    single { PerceptionSettingsStore(androidContext()) }
    single { GuidanceDiagnosticsStore(sceneOverlayConfig = get(), uiFeatureFlags = get()) }
    viewModel {
        SceneAnalysisViewModel(
            frameSource = get(),
            startSceneAnalysisUseCase = get(),
            stopSceneAnalysisUseCase = get(),
            describeSceneUseCase = get(),
            sceneDescriber = get(),
            hapticManager = get(),
            speechManager = get(),
            logger = get(),
            traceService = get(),
            sceneOverlayConfig = get(),
            guidanceSettingsStore = get(),
            guidanceDiagnosticsStore = get(),
            accessibilityStatusProvider = get(),
            textResolver = get(),
            announcements = get(),
        )
    }
    viewModel {
        DescribeViewModel(
            describeSceneUseCase = get(),
            speechManager = get(),
            hapticManager = get(),
            accessibilityStatusProvider = get(),
            guidanceSettingsStore = get(),
            logger = get(),
        )
    }
    viewModel {
        SettingsViewModel(
            appInfo = get(),
            guidanceSettingsStore = get(),
            perceptionSettingsStore = get(),
            perceptionProfileManager = get(),
            guidanceDiagnosticsStore = get(),
            speechManager = get(),
            hapticManager = get(),
        )
    }
}
