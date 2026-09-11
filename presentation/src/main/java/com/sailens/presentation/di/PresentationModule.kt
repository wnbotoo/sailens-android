package com.sailens.presentation.di

import com.sailens.presentation.diagnostics.GuidanceDiagnosticsStore
import com.sailens.presentation.device.AccessibilityStatusProvider
import com.sailens.presentation.device.HapticManager
import com.sailens.presentation.device.SceneEventTextResolver
import com.sailens.presentation.device.SpeechManager
import com.sailens.presentation.scene.SceneAnalysisViewModel
import com.sailens.presentation.settings.GuidanceSettingsStore
import com.sailens.presentation.settings.PerceptionSettingsStore
import com.sailens.presentation.settings.SettingsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val presentationModule = module {
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
