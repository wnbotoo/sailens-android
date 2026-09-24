package com.sailens.shell.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sailens.guidance.model.common.PerceptionProfile
import com.sailens.shell.R
import com.sailens.shell.device.GuidanceHaptic
import com.sailens.output.SpeechManager
import com.sailens.shell.ext.openUrl
import com.sailens.shell.design.components.SailensScaffold
import com.sailens.shell.design.components.SelectableRow
import com.sailens.shell.design.components.SettingRow
import com.sailens.shell.design.components.SettingsGroupDivider
import com.sailens.shell.design.components.SettingsInlineNote
import com.sailens.shell.design.components.SettingsSection
import com.sailens.shell.design.components.ToggleRow
import com.sailens.shell.design.theme.SailensDimens
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenTraceReports: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    SailensScaffold(
        title = stringResource(R.string.title_settings),
        onNavigateBack = onNavigateBack,
        navigateBackContentDescription = stringResource(R.string.cd_navigate_back),
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SailensDimens.screenPadding, vertical = SailensDimens.spaceLg),
            verticalArrangement = Arrangement.spacedBy(SailensDimens.sectionSpacing),
        ) {
            SettingsSection(title = stringResource(R.string.settings_section_feedback)) {
                ToggleRow(
                    label = stringResource(R.string.label_feedback_speech),
                    supportingText = stringResource(R.string.settings_feedback_speech_supporting),
                    checked = state.feedbackSettings.speechEnabled,
                    onCheckedChange = viewModel::setSpeechEnabled,
                )
                SettingsGroupDivider()
                ToggleRow(
                    label = stringResource(R.string.label_feedback_haptics),
                    supportingText = stringResource(R.string.settings_feedback_haptics_supporting),
                    checked = state.feedbackSettings.hapticsEnabled,
                    onCheckedChange = viewModel::setHapticsEnabled,
                )
                SettingsGroupDivider()
                SpeechRateRow(
                    rate = state.feedbackSettings.speechRate,
                    enabled = state.feedbackSettings.speechEnabled,
                    onRateChange = viewModel::setSpeechRate,
                    onPreview = viewModel::previewSpeechRate,
                )
            }

            SettingsSection(title = stringResource(R.string.settings_section_haptic_vocabulary)) {
                SettingsInlineNote(text = stringResource(R.string.settings_haptic_vocabulary_intro))
                GuidanceHaptic.learnableVocabulary.forEachIndexed { index, haptic ->
                    if (index > 0) SettingsGroupDivider()
                    SettingRow(
                        title = stringResource(haptic.labelResId()),
                        supportingText = stringResource(haptic.rhythmResId()),
                        onClick = { viewModel.previewHaptic(haptic) },
                        // 这一行不是导航入口，抑制默认的右向箭头；点击语义由 SettingRow
                        // 自带的 Role.Button 承载，读屏用户读到的是"按钮"而不是"跳转"。
                        trailing = {},
                    )
                }
            }

            SettingsSection(title = stringResource(R.string.settings_section_perception)) {
                Column(modifier = Modifier.selectableGroup()) {
                    SelectableRow(
                        label = stringResource(R.string.label_perception_profile_basic),
                        supportingText = stringResource(R.string.settings_perception_basic_supporting),
                        selected = state.perceptionProfile == PerceptionProfile.BASIC,
                        onClick = { viewModel.setPerceptionProfile(PerceptionProfile.BASIC) },
                    )
                    SettingsGroupDivider()
                    SelectableRow(
                        label = stringResource(R.string.label_perception_profile_default),
                        supportingText = stringResource(R.string.settings_perception_default_supporting),
                        selected = state.perceptionProfile == PerceptionProfile.DEFAULT,
                        onClick = { viewModel.setPerceptionProfile(PerceptionProfile.DEFAULT) },
                    )
                }
                SettingsGroupDivider()
                SettingsInlineNote(text = stringResource(R.string.settings_perception_apply_hint))
            }

            if (state.diagnostics.showDiagnostics) {
                SettingsDiagnosticsSection(
                    onOpenTraceReports = onOpenTraceReports,
                )
            }

            SettingsSection(title = stringResource(R.string.settings_section_about)) {
                AboutSummary()
                SettingsGroupDivider()
                SettingRow(
                    title = stringResource(R.string.settings_app_version_label),
                    valueText = stringResource(
                        R.string.settings_app_version_value,
                        state.appInfo.versionName,
                        state.appInfo.versionCode,
                    ),
                )
                SettingsGroupDivider()
                SettingRow(
                    title = stringResource(R.string.settings_app_license_label),
                    valueText = state.appInfo.license,
                    supportingText = state.appInfo.sourceUrl,
                    onClick = { context.openUrl(state.appInfo.sourceUrl) },
                )
                SettingsGroupDivider()
                SettingRow(
                    title = stringResource(R.string.settings_open_source_label),
                    onClick = onOpenLicenses,
                )
            }
        }
    }
}

/**
 * 语速设置。
 *
 * 对目标用户来说这是"提示延迟"的直接旋钮，不是外观偏好：重度屏幕阅读器用户普遍在 1.5–3x，
 * 而每慢 0.5x，一条六字提示就多占大半秒。所以给了两样东西——离散步进的滑块（TalkBack 上可
 * 预期地增减），以及一个"试听"按钮，因为这个值只能靠听来判断，看数字对用户没有意义。
 */
@Composable
private fun SpeechRateRow(
    rate: Float,
    enabled: Boolean,
    onRateChange: (Float) -> Unit,
    onPreview: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rateLabel = stringResource(R.string.settings_speech_rate_value, rate)
    val sampleText = stringResource(R.string.settings_speech_rate_sample)
    val sliderLabel = stringResource(R.string.label_speech_rate)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = SailensDimens.spaceMd),
        verticalArrangement = Arrangement.spacedBy(SailensDimens.spaceXs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.label_speech_rate),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = rateLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // 数值已经进了滑块的 stateDescription，这里再读一遍就是重复。
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
        Slider(
            value = rate,
            onValueChange = onRateChange,
            valueRange = SpeechManager.MIN_SPEECH_RATE..SpeechManager.MAX_SPEECH_RATE,
            steps = SPEECH_RATE_STEPS,
            enabled = enabled,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = sliderLabel
                    // 覆盖 Slider 默认朗读的百分比：用户关心的是"几倍速"，不是滑块位置。
                    stateDescription = rateLabel
                },
        )
        TextButton(
            onClick = { onPreview(sampleText) },
            enabled = enabled,
            modifier = Modifier.heightIn(min = SailensDimens.minTouchTargetCompact),
        ) {
            Text(text = stringResource(R.string.btn_preview_speech_rate))
        }
    }
}

/** 0.7x–3.0x，按 0.1 步进；离散步进让 TalkBack 的增/减手势每次都走同样的量。 */
private const val SPEECH_RATE_STEPS = 22

private fun GuidanceHaptic.labelResId(): Int = when (this) {
    GuidanceHaptic.LEFT -> R.string.haptic_label_left
    GuidanceHaptic.AHEAD -> R.string.haptic_label_ahead
    GuidanceHaptic.RIGHT -> R.string.haptic_label_right
    GuidanceHaptic.MULTIPLE -> R.string.haptic_label_multiple
    GuidanceHaptic.BLOCKED -> R.string.haptic_label_blocked
    GuidanceHaptic.NOTICE -> R.string.haptic_label_notice
    GuidanceHaptic.VISION_UNRELIABLE -> R.string.haptic_label_sensor_failure
    GuidanceHaptic.SPEECH_UNAVAILABLE -> R.string.haptic_label_speech_unavailable
    GuidanceHaptic.INTERRUPTED -> R.string.haptic_label_interrupted
}

private fun GuidanceHaptic.rhythmResId(): Int = when (this) {
    GuidanceHaptic.LEFT -> R.string.haptic_rhythm_left
    GuidanceHaptic.AHEAD -> R.string.haptic_rhythm_ahead
    GuidanceHaptic.RIGHT -> R.string.haptic_rhythm_right
    GuidanceHaptic.MULTIPLE -> R.string.haptic_rhythm_multiple
    GuidanceHaptic.BLOCKED -> R.string.haptic_rhythm_blocked
    GuidanceHaptic.NOTICE -> R.string.haptic_rhythm_notice
    GuidanceHaptic.VISION_UNRELIABLE -> R.string.haptic_rhythm_sensor_failure
    GuidanceHaptic.SPEECH_UNAVAILABLE -> R.string.haptic_rhythm_speech_unavailable
    GuidanceHaptic.INTERRUPTED -> R.string.haptic_rhythm_interrupted
}

@Composable
private fun AboutSummary(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = SailensDimens.spaceLg),
        verticalArrangement = Arrangement.spacedBy(SailensDimens.spaceXs),
    ) {
        Text(
            text = stringResource(R.string.title_sailens),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.about_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
