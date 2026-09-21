package com.sailens.shell.app

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sailens.shell.FileLogService
import com.sailens.shell.R
import com.sailens.shell.device.HapticManager
import com.sailens.output.SpeechManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a mispackaged build does, on a real device.
 *
 * Two behaviours that cannot be checked from a JVM test because they are Compose and Android
 * respectively (architecture.md §5.2):
 *
 * - a debug build **fails fast**, so a wiring mistake is found by whoever made it;
 * - a release build does **not** crash-loop; it shows a fatal state and pushes the reason out
 *   through a channel a blind user can perceive.
 *
 * Needs a device: `./gradlew.bat :sailens-shell:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class SailensRootConfigurationTest {

    @get:Rule
    val compose = createComposeRule()

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    private val windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(400.dp, 800.dp))

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun releaseBuildShowsTheFatalStateInsteadOfCrashing() {
        compose.setContent {
            SailensRoot(
                windowSizeClass = windowSizeClass,
                spec = brokenPromise(),
                failFastOnConfigurationError = false,
            )
        }

        compose.onNodeWithText(context.getString(R.string.config_failure_title)).assertIsDisplayed()
    }

    @Test(expected = IllegalStateException::class)
    fun debugBuildFailsFastOnABrokenPromise() {
        compose.setContent {
            SailensRoot(
                windowSizeClass = windowSizeClass,
                spec = brokenPromise(),
                failFastOnConfigurationError = true,
            )
        }
    }

    @Test
    fun zeroPipelinesIsALegalStateAndSaysSo() {
        compose.setContent {
            SailensRoot(
                windowSizeClass = windowSizeClass,
                spec = SailensAppSpec(
                    guidance = GuidanceSpec(
                        verifySemanticModel = { StaticUnavailableReason.ModelSourceMissing },
                    ),
                    // Nothing promised: A ships no weights and this is where it legitimately lands.
                    expectations = CapabilityExpectations(),
                ),
            )
        }

        compose.onNodeWithText(context.getString(R.string.zero_pipeline_title)).assertIsDisplayed()
    }

    @Test
    fun theFatalSignalFiresOnceEvenThoughCompositionRepeats() {
        // LaunchedEffect restarts on configuration change and process-death restore. A fatal state
        // that buzzes on every rotation teaches the user to ignore it.
        val signal = ConfigurationFailureSignal(
            speechManager = SpeechManager(context, FileLogService(context)),
            hapticManager = HapticManager(context),
            logService = FileLogService(context),
        )

        assertTrue(signal.signalOnce("navigation assistance is required but unavailable"))
        assertFalse(signal.signalOnce("navigation assistance is required but unavailable"))
        assertFalse(signal.signalOnce("a different message does not reopen it either"))
    }

    private fun brokenPromise() = SailensAppSpec(
        guidance = GuidanceSpec(
            verifySemanticModel = { StaticUnavailableReason.ModelSourceMissing },
        ),
        expectations = CapabilityExpectations(guidanceRequired = true),
    )
}
