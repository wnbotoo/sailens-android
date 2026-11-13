package com.sailens.shell.safetynotice

import android.view.KeyEvent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.espresso.Espresso
import androidx.test.espresso.action.ViewActions.pressKey
import androidx.test.espresso.matcher.RootMatchers.isDialog as isDialogRoot
import androidx.test.espresso.matcher.ViewMatchers.isRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sailens.shell.R
import com.sailens.shell.design.theme.SailensTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The safety notice as a screen reader meets it: the title is a heading and comes first, the
 * paragraphs follow in order, and the one button comes last; only that button confirms.
 *
 * Needs a device. Installed on its own (`adb install -r -t` of the shell's test APK, then
 * `am instrument`), it leaves the app and its data alone.
 */
@RunWith(AndroidJUnit4::class)
class SafetyNoticeDialogTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private var confirmed = 0
    private var dismissed = 0

    private fun show(fontScale: Float? = null) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides if (fontScale == null) density else Density(density.density, fontScale),
            ) {
                SailensTheme {
                    SafetyNoticeDialog(onConfirm = { confirmed++ }, onDismiss = { dismissed++ })
                }
            }
        }
    }

    /** The texts in the notice, in the order the semantics tree (and so a screen reader) goes through them. */
    private fun textsInOrder(): List<String> =
        compose.onAllNodesInDialog().fetchSemanticsNodes().flatMap { node ->
            node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }
        }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesInDialog() =
        onAllNodes(hasAnyAncestor(isDialog()) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text))

    @Test
    fun theTitleIsAHeadingAndComesFirstAndTheButtonComesLast() {
        show()

        compose.onNodeWithText(context.getString(R.string.safety_notice_title))
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))

        val expected = listOf(context.getString(R.string.safety_notice_title)) +
            SAFETY_NOTICE_PARAGRAPHS.map(context::getString) +
            context.getString(R.string.safety_notice_footer) +
            context.getString(R.string.safety_notice_confirm)
        assertEquals(expected, textsInOrder())
        compose.onNode(hasText(context.getString(R.string.safety_notice_confirm)) and hasClickAction()).assertIsDisplayed()
    }

    @Test
    fun onlyTheButtonConfirms() {
        show()

        compose.onNodeWithText(context.getString(R.string.safety_notice_confirm)).performClick()
        compose.runOnIdle {
            assertEquals(1, confirmed)
            assertEquals(0, dismissed)
        }
    }

    @Test
    fun backLeavesItWithoutConfirming() {
        show()

        Espresso.onView(isRoot()).inRoot(isDialogRoot()).perform(pressKey(KeyEvent.KEYCODE_BACK))
        compose.runOnIdle {
            assertEquals(0, confirmed)
            assertEquals(1, dismissed)
        }
    }

    @Test
    fun atTwiceTheFontSizeTheTextScrollsAndTheButtonStaysOnScreen() {
        show(fontScale = 2f)

        compose.onNodeWithText(context.getString(R.string.safety_notice_confirm)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.safety_notice_footer)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.safety_notice_confirm)).assertIsDisplayed()
    }
}
