package com.sailens

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import com.sailens.app.App
import com.sailens.shell.app.HardwareKeyHandler
import org.koin.android.ext.android.getKoin

class MainActivity : ComponentActivity() {
    /** Bound only in debug builds (field capture marker); release has none. */
    private val keyHandler: HardwareKeyHandler? by lazy { getKoin().getOrNull<HardwareKeyHandler>() }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            App(
                windowSizeClass = calculateWindowSizeClass(this),
            )
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        keyHandler?.onKeyEvent(event) == true || super.dispatchKeyEvent(event)
}
