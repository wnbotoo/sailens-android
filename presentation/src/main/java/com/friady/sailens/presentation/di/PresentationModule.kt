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
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get()
        )
    }
}