package com.sailens.shell.guidance.screen

import android.graphics.Paint
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sailens.shell.camera.CameraViewWithPermission
import com.sailens.guidance.model.common.EventPriority
import com.sailens.core.geometry.NormalizedRect
import com.sailens.guidance.model.common.ObstacleCategory
import com.sailens.guidance.model.perception.ObstacleDetection
import com.sailens.shell.R
import com.sailens.shell.device.SceneEventTextResolver
import com.sailens.shell.guidance.overlay.SceneDebugInfoView
import com.sailens.shell.guidance.overlay.SceneOverlayMode
import com.sailens.output.SpeechEngineState
import com.sailens.shell.device.toSceneEventText
import com.sailens.shell.design.components.PrimaryActionButton
import com.sailens.shell.design.theme.SailensDimens
import org.koin.androidx.compose.koinViewModel

/**
 * The primary guidance screen. Camera-forward, with an oversized start/stop control, accessible
 * status announcements, and a direct Settings entry for feedback and debug tooling.
 */
@Composable
fun LiveAnalysisScreen(
    windowSizeClass: WindowSizeClass,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SceneAnalysisViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val view = LocalView.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isLandscape = windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact

    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is SceneAnalysisUiEffect.ShowToast ->
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()

                // 屏幕阅读器模式下的唯一播报出口。用显式 announce 而不是给状态卡片挂
                // liveRegion，是为了播报**简短的提示原文**，而不是卡片上那串带标签和补充说明的
                // 完整描述——后者在行走时太长了。
                is SceneAnalysisUiEffect.Announce ->
                    view.announceForAccessibility(effect.text)
            }
        }
    }

    // 辅助运行期间保持屏幕常亮。
    //
    // 相机是绑在 Activity 生命周期上的（CameraX bindToLifecycle）：屏幕一灭，Activity 走
    // onStop，相机解绑，整条分析链路**静默停止**。盲人用户不会察觉——他会继续举着一个
    // 已经瞎掉的手机往前走。这是防住这个失效的第一道，也是最重要的一道。
    DisposableEffect(view, state.isRunning) {
        view.keepScreenOn = state.isRunning
        onDispose { view.keepScreenOn = false }
    }

    // 第二道：常亮挡不住电源键、来电和系统回收。真断了就必须让用户可感知。
    //
    // 用 ProcessLifecycleOwner 而不是 LocalLifecycleOwner：后者会在转屏（Activity 重建）
    // 时也走一遍 ON_STOP，从而误报一句"辅助已中断"。安全提示一旦有误报，用户很快就会学会
    // 忽略它，那就等于没有。ProcessLifecycleOwner 只在整个应用真的进入后台时才派发，
    // 且自带跨配置变更的延迟去抖。
    val interruptionNotice = stringResource(R.string.notice_guidance_interrupted)
    val processLifecycle = remember { ProcessLifecycleOwner.get().lifecycle }
    DisposableEffect(processLifecycle, interruptionNotice) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> viewModel.onGuidanceInterrupted(interruptionNotice)
                Lifecycle.Event.ON_START -> viewModel.acknowledgeInterruption()
                else -> Unit
            }
        }
        processLifecycle.addObserver(observer)
        onDispose { processLifecycle.removeObserver(observer) }
    }

    val onToggleClick = viewModel::toggleAnalysis
    val onReplay = viewModel::replayLastGuidance
    val onOverlayModeChange: (SceneOverlayMode) -> Unit = viewModel::setOverlayMode

    if (isLandscape) {
        ContentForLandscape(
            state = state,
            onToggleClick = onToggleClick,
            onOpenSettings = onOpenSettings,
            onReplay = onReplay,
            onOverlayModeChange = onOverlayModeChange,
            modifier = modifier,
        )
    } else {
        ContentForPortrait(
            state = state,
            onToggleClick = onToggleClick,
            onOpenSettings = onOpenSettings,
            onReplay = onReplay,
            onOverlayModeChange = onOverlayModeChange,
            modifier = modifier,
        )
    }
}

@Composable
private fun ContentForLandscape(
    state: SceneAnalysisUiState,
    onToggleClick: () -> Unit,
    onOpenSettings: () -> Unit,
    onReplay: () -> Unit,
    onOverlayModeChange: (SceneOverlayMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(SailensDimens.screenPadding),
        horizontalArrangement = Arrangement.spacedBy(SailensDimens.spaceXl),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(SailensDimens.spaceLg),
        ) {
            PreviewPanel(state = state, maxPreviewHeight = 240.dp, contentScale = ContentScale.Fit)
            PrimaryStatusView(
                state = state,
                onReplay = onReplay,
                isLandscape = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(SailensDimens.spaceLg),
        ) {
            HomeTopBar(
                isRunning = state.isRunning,
                isSpeechEnabled = state.isSpeechEnabled,
                speechEngineState = state.speechEngineState,
                isScreenReaderActive = state.isScreenReaderActive,
                onOpenSettings = onOpenSettings,
            )
            ControlView(
                state = state,
                onOverlayModeChange = onOverlayModeChange,
                onToggleClick = onToggleClick,
            )
        }
    }
}

@Composable
private fun ContentForPortrait(
    state: SceneAnalysisUiState,
    onToggleClick: () -> Unit,
    onOpenSettings: () -> Unit,
    onReplay: () -> Unit,
    onOverlayModeChange: (SceneOverlayMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(SailensDimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(SailensDimens.spaceLg),
    ) {
        HomeTopBar(
            isRunning = state.isRunning,
            isSpeechEnabled = state.isSpeechEnabled,
            speechEngineState = state.speechEngineState,
            isScreenReaderActive = state.isScreenReaderActive,
            onOpenSettings = onOpenSettings,
        )
        PreviewPanel(
            state = state,
            maxPreviewHeight = 260.dp,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth(),
        )
        PrimaryStatusView(
            state = state,
            onReplay = onReplay,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
        ControlView(
            state = state,
            onOverlayModeChange = onOverlayModeChange,
            onToggleClick = onToggleClick,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PreviewPanel(
    state: SceneAnalysisUiState,
    maxPreviewHeight: Dp,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp, max = maxPreviewHeight)
            .aspectRatio(16f / 9f),
        shape = MaterialTheme.shapes.medium,
        color = Color.Black,
        tonalElevation = 1.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f),
        ),
    ) {
        CaptureView(
            segMask = state.segMask,
            overlayMode = state.overlayMode,
            obstacleDetections = state.obstacleDetections,
            frameDisplayWidth = state.frameDisplayWidth,
            frameDisplayHeight = state.frameDisplayHeight,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun HomeTopBar(
    isRunning: Boolean,
    isSpeechEnabled: Boolean,
    speechEngineState: SpeechEngineState,
    isScreenReaderActive: Boolean,
    onOpenSettings: () -> Unit,
) {
    val runningLabel = stringResource(
        if (isRunning) R.string.status_preview_live else R.string.status_preview_idle
    )
    val speechLabel = stringResource(
        when {
            !isSpeechEnabled -> R.string.status_speech_off
            // 屏幕阅读器接管播报时自带引擎本就不启动，不该显示成"语音启动中"。
            isScreenReaderActive -> R.string.status_speech_screen_reader
            speechEngineState == SpeechEngineState.UNAVAILABLE -> R.string.status_speech_unavailable
            speechEngineState == SpeechEngineState.READY -> R.string.status_speech_ready
            else -> R.string.status_speech_initializing
        }
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SailensDimens.spaceXs),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = SailensDimens.minTouchTarget),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.title_sailens),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            TextButton(
                onClick = onOpenSettings,
                modifier = Modifier.heightIn(min = SailensDimens.minTouchTargetCompact),
            ) {
                Text(
                    text = stringResource(R.string.btn_settings),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        Row(
            modifier = Modifier.semantics(mergeDescendants = true) {},
            horizontalArrangement = Arrangement.spacedBy(SailensDimens.spaceSm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeaderStatusText(text = runningLabel, highlighted = isRunning)
            Text(
                text = "·",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HeaderStatusText(
                text = speechLabel,
                highlighted = isSpeechEnabled &&
                    (isScreenReaderActive || speechEngineState == SpeechEngineState.READY),
            )
        }
    }
}

@Composable
private fun HeaderStatusText(
    text: String,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelLarge,
        color = if (highlighted) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
private fun PrimaryStatusView(
    state: SceneAnalysisUiState,
    onReplay: () -> Unit,
    modifier: Modifier = Modifier,
    isLandscape: Boolean = false,
) {
    val context = LocalContext.current
    val canReplay = state.lastAnnouncedEvent != null
    // 与语音播报共用同一个渲染器：卡片上的字和听到的话必须逐字一致，否则开/关 TalkBack
    // 会得到两套措辞。
    val textResolver = remember(context) { SceneEventTextResolver(context) }
    // 实时卡片与重播历史必须分开：冷却后的空帧不能让卡片闪退，但已经过期的旧风险也不能
    // 因为还能重播就在屏幕上无限停留。
    val primaryEvent = state.activeStatusEvent
    val eventMessage = primaryEvent?.let { textResolver.resolve(it.toSceneEventText()) }
    val hasHighPriorityEvent = primaryEvent?.priority?.let { it >= EventPriority.HIGH } ?: false
    // 语音是主要输出通道，它挂了等于用户什么都收不到，所以优先级仅次于分析本身失败。
    // 屏幕阅读器接管时自带引擎本就不启动，不算故障。
    val isSpeechBroken = state.isRunning &&
        state.isSpeechEnabled &&
        !state.isScreenReaderActive &&
        state.speechEngineState == SpeechEngineState.UNAVAILABLE

    val title = when {
        state.errorMessage != null -> stringResource(R.string.status_error_title)
        state.wasInterrupted -> stringResource(R.string.status_interrupted_title)
        isSpeechBroken -> stringResource(R.string.status_speech_unavailable_title)
        state.isLoading -> stringResource(R.string.status_starting_title)
        !state.isRunning -> stringResource(R.string.status_idle_title)
        eventMessage != null -> eventMessage
        else -> stringResource(R.string.status_clear_title)
    }
    val detail = when {
        state.errorMessage != null -> stringResource(R.string.error_analysis_start_failed)
        state.wasInterrupted -> stringResource(R.string.status_interrupted_detail)
        isSpeechBroken -> stringResource(R.string.status_speech_unavailable_detail)
        state.isLoading -> stringResource(R.string.status_starting_detail)
        !state.isRunning -> stringResource(R.string.status_idle_detail)
        eventMessage != null -> stringResource(R.string.status_guidance_detail)
        else -> stringResource(R.string.status_clear_detail)
    }
    val label = when {
        state.errorMessage != null -> stringResource(R.string.status_label_error)
        state.wasInterrupted || isSpeechBroken -> stringResource(R.string.status_label_error)
        state.isLoading -> stringResource(R.string.status_label_starting)
        !state.isRunning -> stringResource(R.string.status_label_standby)
        eventMessage != null -> stringResource(R.string.status_label_latest_guidance)
        else -> stringResource(R.string.status_label_monitoring)
    }
    val isAlert = state.errorMessage != null ||
        state.wasInterrupted ||
        isSpeechBroken ||
        hasHighPriorityEvent
    val containerColor = when {
        isAlert -> MaterialTheme.colorScheme.errorContainer
        state.isRunning -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        isAlert -> MaterialTheme.colorScheme.onErrorContainer
        state.isRunning -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val spokenDescription = if (isLandscape) "$label. $title" else "$label. $title. $detail"

    // 这里刻意**不挂 liveRegion**。挂上会让每条提示被念两遍：一遍走播报通道，一遍由
    // TalkBack 朗读变化的 live region，两者还不同步。自动播报统一由 ViewModel 决定走哪条通道
    // （见 SceneAnalysisUiEffect.Announce）；contentDescription 保留，供用户手动浏览到这张卡片时朗读。
    //
    // 整张卡片可点击 = 重播上一条提示。选它当重播入口是因为它是屏幕上最大的一块区域
    // （portrait 下占满剩余空间），走路时不需要任何视觉定位就能戳中；同时挂了自定义无障碍
    // 动作，TalkBack 用户可以从局部菜单直接触发，不必先把焦点移过来。
    val replayLabel = stringResource(R.string.action_replay_last_guidance)
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = spokenDescription
            customActions = listOf(
                CustomAccessibilityAction(replayLabel) {
                    onReplay()
                    true
                }
            )
        },
        onClick = onReplay,
        enabled = canReplay,
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
    ) {
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(SailensDimens.cardPadding)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!isLandscape) {
                GuidanceStatusLabel(text = label, contentColor = contentColor)
                Spacer(modifier = Modifier.height(SailensDimens.spaceMd))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            if (!isLandscape) {
                Spacer(modifier = Modifier.height(SailensDimens.spaceSm))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun GuidanceStatusLabel(
    text: String,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraLarge,
        color = contentColor.copy(alpha = 0.14f),
        contentColor = contentColor,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(
                horizontal = SailensDimens.spaceMd,
                vertical = SailensDimens.spaceSm,
            ),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun CaptureView(
    segMask: android.graphics.Bitmap?,
    overlayMode: SceneOverlayMode,
    obstacleDetections: List<ObstacleDetection>,
    frameDisplayWidth: Int?,
    frameDisplayHeight: Int?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
) {
    // Hoisted out of the Canvas draw lambdas: these are constant, so allocating a Paint per overlay
    // redraw (every frame while a box/mask overlay is active) was pure GC churn.
    val labelPaint = remember { createOverlayLabelPaint() }
    val backgroundPaint = remember { createOverlayLabelBackgroundPaint() }
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        CameraViewWithPermission(modifier = Modifier.fillMaxSize(), contentScale = contentScale)
        if (segMask != null) {
            Image(
                bitmap = segMask.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics {},
                contentScale = contentScale,
                alpha = 0.6f,
            )
        }
        if (overlayMode == SceneOverlayMode.DETECTION_BOXES && obstacleDetections.isNotEmpty()) {
            Canvas(modifier = Modifier.fillMaxSize().clearAndSetSemantics {}) {
                val transform = ViewContentTransform.from(
                    containerSize = size,
                    sourceWidth = frameDisplayWidth,
                    sourceHeight = frameDisplayHeight,
                    contentScale = contentScale,
                )
                obstacleDetections.forEach { detection ->
                    drawOverlayBox(
                        transform = transform,
                        boundingBox = detection.boundingBox,
                        color = detection.category.overlayColor(),
                        label = detection.overlayLabel(),
                        labelPaint = labelPaint,
                        backgroundPaint = backgroundPaint,
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlView(
    state: SceneAnalysisUiState,
    onOverlayModeChange: (SceneOverlayMode) -> Unit,
    onToggleClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 1.dp,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.56f),
        ),
    ) {
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SailensDimens.cardPadding)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(SailensDimens.spaceSm),
        ) {
            PrimaryActionButton(
                text = stringResource(
                    if (state.isRunning) R.string.btn_stop_guidance else R.string.btn_start_guidance
                ),
                onClick = onToggleClick,
                loading = state.isLoading,
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.showDiagnostics) {
                HomePanelDivider()
                HomePanelSectionTitle(text = stringResource(R.string.label_overlay_mode))
                val selectableOverlayModes =
                    listOf(SceneOverlayMode.OFF) + SceneOverlayMode.entries.filter { mode ->
                        mode != SceneOverlayMode.OFF && mode in state.enabledOverlayModes
                    }
                if (selectableOverlayModes.size > 1) {
                    OverlayModeChips(
                        modes = selectableOverlayModes,
                        selectedMode = state.overlayMode,
                        onOverlayModeChange = onOverlayModeChange,
                    )
                }
                Text(
                    text = stringResource(
                        R.string.label_current_overlay_mode,
                        stringResource(state.overlayMode.labelResId()),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HomePanelDivider()
                HomePanelSectionTitle(text = stringResource(R.string.label_live_pipeline))
                state.latestSceneDebugInfo?.let { debugInfo ->
                    SceneDebugInfoView(
                        debugInfo = debugInfo,
                        maskSourceAgeMs = state.maskSourceAgeMs,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } ?: Text(
                    text = stringResource(R.string.msg_no_live_pipeline_debug),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HomePanelDivider(
    modifier: Modifier = Modifier,
) {
    HorizontalDivider(
        modifier = modifier.padding(vertical = SailensDimens.spaceXs),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.64f),
    )
}

@Composable
private fun HomePanelSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun OverlayModeChips(
    modes: List<SceneOverlayMode>,
    selectedMode: SceneOverlayMode,
    onOverlayModeChange: (SceneOverlayMode) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(SailensDimens.spaceSm)) {
        modes.forEach { mode ->
            FilterChip(
                selected = mode == selectedMode,
                onClick = { onOverlayModeChange(mode) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SailensDimens.minTouchTargetCompact),
                label = {
                    Text(
                        text = stringResource(mode.labelResId()),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelLarge,
                    )
                },
            )
        }
    }
}

private fun ObstacleCategory.overlayColor(): Color {
    return when (this) {
        ObstacleCategory.PERSON -> Color(0xFFFF5252)
        ObstacleCategory.VEHICLE -> Color(0xFF42A5F5)
        ObstacleCategory.BICYCLE -> Color(0xFFFFCA28)
        ObstacleCategory.STATIC_OBSTACLE -> Color(0xFFAB47BC)
        ObstacleCategory.UNKNOWN -> Color.White
    }
}

private fun SceneOverlayMode.labelResId(): Int {
    return when (this) {
        SceneOverlayMode.OFF -> R.string.btn_overlay_off
        SceneOverlayMode.PASSABLE_AREA_MASK -> R.string.btn_overlay_passable_area_mask
        SceneOverlayMode.SEMANTIC_CLASS_MASK -> R.string.btn_overlay_semantic_class_mask
        SceneOverlayMode.DETECTION_BOXES -> R.string.btn_overlay_detection_boxes
    }
}

private fun ObstacleDetection.overlayLabel(): String {
    val label = className.ifBlank { category.name }
    return "$label ${(confidence * 100).toInt()}%"
}

private fun createOverlayLabelPaint(): Paint {
    return Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 32f
        isAntiAlias = true
        style = Paint.Style.FILL
    }
}

private fun createOverlayLabelBackgroundPaint(): Paint {
    return Paint().apply {
        color = android.graphics.Color.argb(180, 0, 0, 0)
        style = Paint.Style.FILL
    }
}

private fun DrawScope.drawOverlayBox(
    transform: ViewContentTransform,
    boundingBox: NormalizedRect,
    color: Color,
    label: String,
    labelPaint: Paint,
    backgroundPaint: Paint,
) {
    val left = transform.offsetX + boundingBox.x * transform.sourceWidth * transform.scale
    val top = transform.offsetY + boundingBox.y * transform.sourceHeight * transform.scale
    val width = boundingBox.width * transform.sourceWidth * transform.scale
    val height = boundingBox.height * transform.sourceHeight * transform.scale

    if (width <= 0f || height <= 0f) return

    drawRect(
        color = color,
        topLeft = Offset(left, top),
        size = Size(width, height),
        style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
    )

    val labelLeft = left.coerceIn(0f, size.width)
    val textWidth = labelPaint.measureText(label)
    val labelRight = (labelLeft + textWidth + 18f).coerceAtMost(size.width)
    if (labelRight <= labelLeft) return

    drawContext.canvas.nativeCanvas.apply {
        val labelTop = (top - 38f).coerceAtLeast(0f)
        val labelBottom = (top - 4f).coerceAtLeast(30f).coerceAtMost(size.height)
        drawRoundRect(labelLeft, labelTop, labelRight, labelBottom, 10f, 10f, backgroundPaint)
        drawText(label, labelLeft + 8f, (top - 12f).coerceAtLeast(24f), labelPaint)
    }
}

private data class ViewContentTransform(
    val sourceWidth: Float,
    val sourceHeight: Float,
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
) {
    companion object {
        fun from(
            containerSize: Size,
            sourceWidth: Int?,
            sourceHeight: Int?,
            contentScale: ContentScale,
        ): ViewContentTransform {
            val safeSourceWidth = sourceWidth?.takeIf { it > 0 }?.toFloat() ?: containerSize.width
            val safeSourceHeight = sourceHeight?.takeIf { it > 0 }?.toFloat() ?: containerSize.height
            val scaleX = containerSize.width / safeSourceWidth
            val scaleY = containerSize.height / safeSourceHeight
            val scale = when (contentScale) {
                ContentScale.Fit -> minOf(scaleX, scaleY)
                ContentScale.FillHeight -> scaleY
                ContentScale.FillWidth -> scaleX
                ContentScale.Crop -> maxOf(scaleX, scaleY)
                else -> minOf(scaleX, scaleY)
            }
            val drawnWidth = safeSourceWidth * scale
            val drawnHeight = safeSourceHeight * scale
            return ViewContentTransform(
                sourceWidth = safeSourceWidth,
                sourceHeight = safeSourceHeight,
                scale = scale,
                offsetX = (containerSize.width - drawnWidth) / 2f,
                offsetY = (containerSize.height - drawnHeight) / 2f,
            )
        }
    }
}
