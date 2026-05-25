package com.friady.sailens.presentation.di

import com.friady.sailens.presentation.device.HapticManager
import com.friady.sailens.presentation.device.SpeechManager
import com.friady.sailens.presentation.scene.SceneAnalysisViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val presentationModule = module {
    single { HapticManager(androidContext()) }
    single { SpeechManager(androidContext()) }
    viewModel {
        SceneAnalysisViewModel(
            imageFrameProvider = get(),
            startSceneAnalysisUseCase = get(),
            stopSceneAnalysisUseCase = get(),
            hapticManager = get(),
            speechManager = get(),
            logger = get(),
            traceService = get(),
            listTraceSessionsUseCase = get(),
            loadTraceReplayReportUseCase = get(),
            loadLatestTraceReplayReportUseCase = get(),
            evaluateTraceReplayBudgetUseCase = get(),
            sceneOverlayConfig = get(),
        )
    }
}
