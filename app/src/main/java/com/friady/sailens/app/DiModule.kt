package com.friady.sailens.app

import com.friady.sailens.camera.di.cameraModule
import com.friady.sailens.data.di.dataModule
import com.friady.sailens.domain.di.domainModule
import com.friady.sailens.presentation.di.presentationModule
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val appModule = module {
    includes(dataModule)
    includes(domainModule)
    includes(cameraModule)
    includes(presentationModule)

    viewModel {
        AppViewModel(get())
    }

}