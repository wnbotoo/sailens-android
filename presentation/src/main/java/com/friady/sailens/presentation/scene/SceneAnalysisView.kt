package com.friady.sailens.presentation.scene

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.friady.sailens.camera.CameraView
import com.friady.sailens.domain.model.common.ObstacleCategory
import com.friady.sailens.domain.model.perception.DetectedObstacle
import com.friady.sailens.presentation.R
import org.koin.androidx.compose.koinViewModel
import java.io.File

@Composable
fun SceneAnalysisView(
    windowSizeClass: WindowSizeClass,
    modifier: Modifier = Modifier,
    viewModel: SceneAnalysisViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isLandscape = windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact
    val traceReportCopiedMessage = stringResource(R.string.trace_report_copied)
    val traceFileMissingMessage = stringResource(R.string.trace_file_not_found)

    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is SceneAnalysisUiEffect.ShowToast -> {
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                }

                is SceneAnalysisUiEffect.CopyToClipboard -> {
                    val clipboardManager =
                        context.getSystemService(ClipboardManager::class.java) as ClipboardManager
                    clipboardManager.setPrimaryClip(ClipData.newPlainText(effect.label, effect.text))
                    Toast.makeText(
                        context,
                        traceReportCopiedMessage,
                        Toast.LENGTH_SHORT,
                    ).show()
                }

                is SceneAnalysisUiEffect.ShareTraceFile -> {
                    shareTraceFile(
                        context = context,
                        sessionId = effect.sessionId,
                        fileMissingMessage = traceFileMissingMessage,
                    )
                }
            }
        }
    }

    val onToggleClick = {
        viewModel.toggleAnalysis()
    }
    val onOpenTraceReplayClick = {
        viewModel.openTraceReplaySessionsScreen()
    }
    val onOverlayModeChange: (SegmentationOverlayMode) -> Unit = viewModel::setOverlayMode
    val onSpeechEnabledChange: (Boolean) -> Unit = viewModel::setSpeechEnabled
    val onHapticsEnabledChange: (Boolean) -> Unit = viewModel::setHapticsEnabled

    when (state.currentScreen) {
        SceneAnalysisScreen.LIVE_ANALYSIS -> {
            if (isLandscape) {
                ContentForLandscape(
                    state = state,
                    onToggleClick = onToggleClick,
                    onOpenTraceReplayClick = onOpenTraceReplayClick,
                    onOverlayModeChange = onOverlayModeChange,
                    onSpeechEnabledChange = onSpeechEnabledChange,
                    onHapticsEnabledChange = onHapticsEnabledChange,
                    modifier = modifier,
                )
            } else {
                ContentForPortrait(
                    state = state,
                    onToggleClick = onToggleClick,
                    onOpenTraceReplayClick = onOpenTraceReplayClick,
                    onOverlayModeChange = onOverlayModeChange,
                    onSpeechEnabledChange = onSpeechEnabledChange,
                    onHapticsEnabledChange = onHapticsEnabledChange,
                    modifier = modifier,
                )
            }
        }

        SceneAnalysisScreen.TRACE_REPLAY_SESSIONS -> {
            TraceReplaySessionsScreen(
                state = state,
                onBackToLiveClick = viewModel::showLiveAnalysisScreen,
                onRefreshTraceSessionsClick = viewModel::refreshTraceSessions,
                onLoadLatestTraceReportClick = viewModel::loadLatestTraceReplayReport,
                onLoadTraceSessionClick = viewModel::loadTraceReplayReport,
                modifier = modifier,
            )
        }

        SceneAnalysisScreen.TRACE_REPLAY_REPORT -> {
            TraceReplayReportScreen(
                state = state,
                onBackToSessionsClick = viewModel::showTraceReplaySessionsScreen,
                onBackToLiveClick = viewModel::showLiveAnalysisScreen,
                onLoadLatestTraceReportClick = viewModel::loadLatestTraceReplayReport,
                onCopyTraceReportClick = viewModel::copyTraceReplaySummary,
                onShareTraceFileClick = viewModel::shareTraceReplayFile,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun ContentForLandscape(
    state: SceneAnalysisUiState,
    onToggleClick: () -> Unit,
    onOpenTraceReplayClick: () -> Unit,
    onOverlayModeChange: (SegmentationOverlayMode) -> Unit,
    onSpeechEnabledChange: (Boolean) -> Unit,
    onHapticsEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxSize()) {
        CaptureView(
            segMask = state.segMask,
            overlayMode = state.overlayMode,
            trackedObstacles = state.trackedObstacles,
            frameDisplayWidth = state.frameDisplayWidth,
            frameDisplayHeight = state.frameDisplayHeight,
            contentScale = ContentScale.FillHeight,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(
            modifier = Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            ControlView(
                isRunning = state.isRunning,
                isLoading = state.isLoading,
                isSpeechEnabled = state.isSpeechEnabled,
                isHapticsEnabled = state.isHapticsEnabled,
                overlayMode = state.overlayMode,
                latestSceneDebugInfo = state.latestSceneDebugInfo,
                onOpenTraceReplayClick = onOpenTraceReplayClick,
                onOverlayModeChange = onOverlayModeChange,
                onSpeechEnabledChange = onSpeechEnabledChange,
                onHapticsEnabledChange = onHapticsEnabledChange,
                onToggleClick = onToggleClick
            )
        }

        Spacer(modifier = Modifier.width(16.dp))
    }
}

@Composable
private fun ContentForPortrait(
    state: SceneAnalysisUiState,
    onToggleClick: () -> Unit,
    onOpenTraceReplayClick: () -> Unit,
    onOverlayModeChange: (SegmentationOverlayMode) -> Unit,
    onSpeechEnabledChange: (Boolean) -> Unit,
    onHapticsEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        CaptureView(
            segMask = state.segMask,
            overlayMode = state.overlayMode,
            trackedObstacles = state.trackedObstacles,
            frameDisplayWidth = state.frameDisplayWidth,
            frameDisplayHeight = state.frameDisplayHeight,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            ControlView(
                isRunning = state.isRunning,
                isLoading = state.isLoading,
                isSpeechEnabled = state.isSpeechEnabled,
                isHapticsEnabled = state.isHapticsEnabled,
                overlayMode = state.overlayMode,
                latestSceneDebugInfo = state.latestSceneDebugInfo,
                onOpenTraceReplayClick = onOpenTraceReplayClick,
                onOverlayModeChange = onOverlayModeChange,
                onSpeechEnabledChange = onSpeechEnabledChange,
                onHapticsEnabledChange = onHapticsEnabledChange,
                onToggleClick = onToggleClick
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun CaptureView(
    segMask: android.graphics.Bitmap?,
    overlayMode: SegmentationOverlayMode,
    trackedObstacles: List<DetectedObstacle>,
    frameDisplayWidth: Int?,
    frameDisplayHeight: Int?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        CameraView(
            modifier = Modifier.fillMaxSize(),
            contentScale = contentScale
        )
        segMask?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Segmentation mask overlay",
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
                alpha = 0.6f
            )
        }
        if (overlayMode == SegmentationOverlayMode.INSTANCE_DEBUG && trackedObstacles.isNotEmpty()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val transform = ViewContentTransform.from(
                    containerSize = size,
                    sourceWidth = frameDisplayWidth,
                    sourceHeight = frameDisplayHeight,
                    contentScale = contentScale,
                )
                val labelPaint = Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 32f
                    isAntiAlias = true
                    style = Paint.Style.FILL
                }
                val backgroundPaint = Paint().apply {
                    color = android.graphics.Color.argb(180, 0, 0, 0)
                    style = Paint.Style.FILL
                }

                trackedObstacles.forEach { obstacle ->
                    val color = obstacle.category.overlayColor()
                    val left = transform.offsetX + obstacle.boundingBox.x * transform.sourceWidth * transform.scale
                    val top = transform.offsetY + obstacle.boundingBox.y * transform.sourceHeight * transform.scale
                    val width = obstacle.boundingBox.width * transform.sourceWidth * transform.scale
                    val height = obstacle.boundingBox.height * transform.sourceHeight * transform.scale

                    drawRect(
                        color = color,
                        topLeft = Offset(left, top),
                        size = Size(width, height),
                        style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
                    )

                    val label = "${obstacle.category.name} ${(obstacle.confidence * 100).toInt()}%"
                    drawContext.canvas.nativeCanvas.apply {
                        val textWidth = labelPaint.measureText(label)
                        drawRoundRect(
                            left,
                            (top - 38f).coerceAtLeast(0f),
                            (left + textWidth + 18f).coerceAtMost(size.width),
                            (top - 4f).coerceAtLeast(30f),
                            10f,
                            10f,
                            backgroundPaint,
                        )
                        drawText(label, left + 8f, (top - 12f).coerceAtLeast(24f), labelPaint)
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlView(
    isRunning: Boolean,
    isLoading: Boolean,
    isSpeechEnabled: Boolean,
    isHapticsEnabled: Boolean,
    overlayMode: SegmentationOverlayMode,
    latestSceneDebugInfo: com.friady.sailens.domain.model.scene.SceneDebugInfo?,
    onOpenTraceReplayClick: () -> Unit,
    onOverlayModeChange: (SegmentationOverlayMode) -> Unit,
    onSpeechEnabledChange: (Boolean) -> Unit,
    onHapticsEnabledChange: (Boolean) -> Unit,
    onToggleClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringResource(R.string.label_feedback_speech))
            Switch(checked = isSpeechEnabled, onCheckedChange = onSpeechEnabledChange)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(stringResource(R.string.label_feedback_haptics))
            Switch(checked = isHapticsEnabled, onCheckedChange = onHapticsEnabledChange)
        }

        Button(
            onClick = onOpenTraceReplayClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.btn_open_trace_replay))
        }

        Text(
            text = stringResource(R.string.label_overlay_mode),
            style = MaterialTheme.typography.titleSmall,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onOverlayModeChange(SegmentationOverlayMode.PASSABLE_MASK) },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.btn_overlay_passable))
            }

            Button(
                onClick = { onOverlayModeChange(SegmentationOverlayMode.SEMANTIC_CLASSES) },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.btn_overlay_semantic))
            }

            Button(
                onClick = { onOverlayModeChange(SegmentationOverlayMode.INSTANCE_DEBUG) },
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.btn_overlay_instance_debug))
            }
        }

        Text(
            text = stringResource(
                R.string.label_current_overlay_mode,
                when (overlayMode) {
                    SegmentationOverlayMode.PASSABLE_MASK -> stringResource(R.string.btn_overlay_passable)
                    SegmentationOverlayMode.SEMANTIC_CLASSES -> stringResource(R.string.btn_overlay_semantic)
                    SegmentationOverlayMode.INSTANCE_DEBUG -> stringResource(R.string.btn_overlay_instance_debug)
                }
            ),
            style = MaterialTheme.typography.bodySmall,
        )

        Button(
            onClick = onToggleClick,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(if (isRunning) "Stop Image Analyzer" else "Start Image Analyzer")
        }

        latestSceneDebugInfo?.let { debugInfo ->
            SceneDebugInfoView(debugInfo = debugInfo)
        }
    }
}

@Composable
private fun SceneDebugInfoView(
    debugInfo: com.friady.sailens.domain.model.scene.SceneDebugInfo,
) {
    val passablePercent = (debugInfo.passableRatio * 100).toInt()
    val navigationPassablePercent = (debugInfo.navigationPassableRatio * 100).toInt()
    val obstaclePercent = (debugInfo.obstacleRatio * 100).toInt()
    val roadPercent = (debugInfo.roadRatio * 100).toInt()
    val bottomCoveragePercent = (debugInfo.bottomCoverage * 100).toInt()
    val bottomWidthPercent = (debugInfo.bottomMaxRunWidthRatio * 100).toInt()
    val blockageConfidencePercent = (debugInfo.blockageConfidence * 100).toInt()
    val verticalReachPercent = (debugInfo.verticalReachRatio * 100).toInt()
    val floodReachPercent = (debugInfo.floodReachRatio * 100).toInt()
    val widthRetentionPercent = (debugInfo.widthRetentionP25 * 100).toInt()
    val recentDroppedPercent = (debugInfo.recentDroppedFrameRate * 100).toInt()
    val dominantClasses = debugInfo.dominantClasses.ifEmpty {
        listOf(stringResource(R.string.value_unknown))
    }.joinToString()

    val scrollState = rememberScrollState()

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.verticalScroll(scrollState)
    ) {
        Text(
            text = stringResource(R.string.title_live_pipeline_debug),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(stringResource(R.string.debug_semantic_provider, debugInfo.semanticProvider))
        Text(stringResource(R.string.debug_instance_provider, debugInfo.instanceProvider))
        Text(stringResource(R.string.debug_inference_strategy, debugInfo.inferenceStrategy))
        Text(stringResource(R.string.debug_passable_ratio, passablePercent))
        Text(stringResource(R.string.debug_navigation_passable_ratio, navigationPassablePercent))
        Text(stringResource(R.string.debug_obstacle_ratio, obstaclePercent))
        Text(stringResource(R.string.debug_road_ratio, roadPercent))
        Text(stringResource(R.string.debug_bottom_coverage, bottomCoveragePercent))
        Text(stringResource(R.string.debug_bottom_width_ratio, bottomWidthPercent))
        Text(stringResource(R.string.debug_blockage_confidence, blockageConfidencePercent))
        Text(stringResource(R.string.debug_vertical_reach, verticalReachPercent, debugInfo.validLayers, debugInfo.totalLayers))
        Text(stringResource(R.string.debug_flood_reach, floodReachPercent))
        Text(stringResource(R.string.debug_width_retention_p25, widthRetentionPercent))
        Text(stringResource(R.string.debug_dominant_classes, dominantClasses))
        Text(stringResource(R.string.debug_total_pipeline_ms, debugInfo.totalPipelineMs))
        Text(stringResource(R.string.debug_pipeline_breakdown_ms, debugInfo.processFrameMs, debugInfo.analyzeSceneMs, debugInfo.decideEventsMs))
        Text(stringResource(R.string.debug_inference_ms, debugInfo.inferenceMs))
        Text(stringResource(R.string.debug_recent_avg_pipeline_ms, debugInfo.recentAvgTotalPipelineMs))
        Text(stringResource(R.string.debug_recent_p95_pipeline_ms, debugInfo.recentP95TotalPipelineMs))
        Text(stringResource(R.string.debug_recent_dropped_rate, recentDroppedPercent, debugInfo.droppedFramesSinceLast))
        Text(stringResource(R.string.debug_tracked_obstacle_count, debugInfo.trackedObstacleCount))
        Text(stringResource(R.string.debug_raw_instance_count, debugInfo.rawInstanceCount, debugInfo.rawInstanceMaskCount))
        Text(
            text = stringResource(
                if (debugInfo.isRuntimeOverBudget) {
                    R.string.debug_runtime_budget_warning
                } else {
                    R.string.debug_runtime_budget_ok
                }
            ),
            color = if (debugInfo.isRuntimeOverBudget) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
        )
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

private fun shareTraceFile(
    context: Context,
    sessionId: String,
    fileMissingMessage: String,
) {
    val traceFile = File(File(context.filesDir, "traces"), "trace_$sessionId.jsonl")
    if (!traceFile.exists() || !traceFile.isFile) {
        Toast.makeText(context, fileMissingMessage, Toast.LENGTH_SHORT).show()
        return
    }

    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        traceFile,
    )
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    context.startActivity(
        Intent.createChooser(
            shareIntent,
            context.getString(R.string.title_share_trace_file),
        )
    )
}
