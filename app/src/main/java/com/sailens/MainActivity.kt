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

    // Public platform callbacks rather than dispatchKeyEvent (restricted on ComponentActivity).
    // Volume keys reach them because no view consumes them; repeatCount is preserved.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        keyHandler?.onKeyEvent(event) == true || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        keyHandler?.onKeyEvent(event) == true || super.onKeyUp(keyCode, event)
}
