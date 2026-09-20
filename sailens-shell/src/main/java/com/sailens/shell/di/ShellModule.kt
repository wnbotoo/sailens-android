package com.sailens.shell.di

import com.sailens.shell.diagnostics.GuidanceDiagnosticsStore
import com.sailens.shell.device.AccessibilityStatusProvider
import com.sailens.shell.device.HapticManager
import com.sailens.shell.device.SceneEventTextResolver
import com.sailens.shell.device.SpeechManager
import com.sailens.shell.guidance.screen.SceneAnalysisViewModel
import com.sailens.shell.guidance.settings.GuidanceSettingsStore
import com.sailens.shell.guidance.settings.PerceptionSettingsStore
import com.sailens.shell.settings.SettingsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val shellModule = module {
    single { HapticManager(androidContext()) }
    single { SceneEventTextResolver(androidContext()) }
    single { SpeechManager(androidContext(), get(), get()) }
    single { AccessibilityStatusProvider(androidContext()) }
    single { GuidanceSettingsStore(androidContext()) }
    single { PerceptionSettingsStore(androidContext()) }
    single { GuidanceDiagnosticsStore(sceneOverlayConfig = get(), uiFeatureFlags = get()) }
    viewModel {
        SceneAnalysisViewModel(
            imageFrameProvider = get(),
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
