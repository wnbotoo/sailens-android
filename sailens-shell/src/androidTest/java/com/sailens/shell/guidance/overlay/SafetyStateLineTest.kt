package com.sailens.shell.guidance.overlay

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sailens.shell.R
import com.sailens.shell.guidance.safety.GuidanceSafetyState
import com.sailens.shell.guidance.safety.label
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The safety state is shown with the diagnostics, and a screen reader does not come to it.
 *
 * Needs a device: `./gradlew.bat :sailens-shell:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class SafetyStateLineTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theSafetyStateIsShownAndHiddenFromScreenReaders() {
        compose.setContent { SafetyStateLine(GuidanceSafetyState.Guiding) }

        compose.onNodeWithText(context.getString(R.string.debug_safety_state, GuidanceSafetyState.Guiding.label))
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility))
    }
}
