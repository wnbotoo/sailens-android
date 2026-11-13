package com.sailens.shell.safetynotice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.sailens.shell.R
import com.sailens.shell.design.theme.SailensDimens

/**
 * The version of the safety notice a person confirms before Guidance starts for the first time.
 *
 * Raise it whenever the notice comes to say something materially different: a limit added or
 * changed, a promise narrowed. Everyone who confirmed an earlier version is then shown the notice
 * again before their next start. A rewording that changes nothing a person relies on keeps it.
 */
internal const val SAFETY_NOTICE_VERSION: Int = 1

/**
 * The notice, paragraph by paragraph, in reading order. The notice before the first start and the
 * Safety section in Settings show these same strings, so the two cannot drift apart.
 */
internal val SAFETY_NOTICE_PARAGRAPHS: List<Int> = listOf(
    R.string.safety_notice_assistive_only,
    R.string.safety_notice_no_prompt,
    R.string.safety_notice_walking,
    R.string.safety_notice_background,
    R.string.safety_notice_privacy,
)

/**
 * The notice's paragraphs, one `Text` each so a screen reader stops at each. Plain body text in the
 * surrounding content colour, which both places that show it set to the normal one rather than the
 * muted colour of a side note: this is the part a person should not skim past.
 */
@Composable
internal fun SafetyNoticeParagraphs(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(SailensDimens.spaceMd),
    ) {
        SAFETY_NOTICE_PARAGRAPHS.forEach { resId ->
            Text(
                text = stringResource(resId),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
