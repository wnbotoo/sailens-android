package com.sailens.app

import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.sailens.BuildConfig
import com.sailens.shell.app.ConfigurationFailureSignal
import com.sailens.shell.app.SailensRoot
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
fun App(
    windowSizeClass: WindowSizeClass,
    @Suppress("UNUSED_PARAMETER") appViewModel: AppViewModel = koinViewModel(),
) {
    // appViewModel is resolved for its side effect: it starts/stops device-sensor observation for
    // the lifetime of the app's root composition. Theming + navigation live in SailensRoot.
    val context = LocalContext.current
    val sceneDescriber = koinInject<com.sailens.vlm.SceneDescriber>()
    val failureSignal = koinInject<ConfigurationFailureSignal>()
    val spec = remember(context, sceneDescriber) {
        sailensEditionSpec(context = context, sceneDescriber = sceneDescriber)
    }

    SailensRoot(
        windowSizeClass = windowSizeClass,
        spec = spec,
        // Debug fails fast so a packaging mistake surfaces at once; release shows an accessible
        // fatal state instead of crash-looping (architecture.md §5.2).
        failFastOnConfigurationError = BuildConfig.DEBUG,
        configurationFailureSignal = failureSignal,
    )
}
