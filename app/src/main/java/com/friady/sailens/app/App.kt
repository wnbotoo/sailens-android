package com.friady.sailens.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.friady.sailens.presentation.scene.SceneAnalysisView
import com.friady.sailens.ux.theme.SailensTheme
import org.koin.androidx.compose.koinViewModel

@Composable
fun App(
    appViewModel: AppViewModel = koinViewModel(),
    windowSizeClass: WindowSizeClass,
) {
    SailensTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            AppContent(
                windowSizeClass = windowSizeClass
            )
        }
    }
}

@Composable
private fun AppContent(
    windowSizeClass: WindowSizeClass,
) {
    Box(modifier = Modifier.safeDrawingPadding()) {
        SceneAnalysisView(windowSizeClass)
    }
}