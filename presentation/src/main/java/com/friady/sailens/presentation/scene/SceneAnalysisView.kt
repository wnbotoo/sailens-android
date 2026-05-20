package com.friady.sailens.presentation.scene

import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.friady.sailens.camera.CameraView
import com.friady.sailens.presentation.R
import org.koin.androidx.compose.koinViewModel

@Composable
fun SceneAnalysisView(
    windowSizeClass: WindowSizeClass,
    modifier: Modifier = Modifier,
    viewModel: SceneAnalysisViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isLandscape = windowSizeClass.heightSizeClass == WindowHeightSizeClass.Compact

    LaunchedEffect(Unit) {
        viewModel.uiEffect.collect { effect ->
            when (effect) {
                is SceneAnalysisUiEffect.ShowToast -> {
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val onToggleClick = {
        viewModel.toggleAnalysis()
    }
    val onSpeechEnabledChange: (Boolean) -> Unit = viewModel::setSpeechEnabled
    val onHapticsEnabledChange: (Boolean) -> Unit = viewModel::setHapticsEnabled

    if (isLandscape) {
        ContentForLandscape(
            state = state,
            onToggleClick = onToggleClick,
            onSpeechEnabledChange = onSpeechEnabledChange,
            onHapticsEnabledChange = onHapticsEnabledChange,
            modifier = modifier
        )
    } else {
        ContentForPortrait(
            state = state,
            onToggleClick = onToggleClick,
            onSpeechEnabledChange = onSpeechEnabledChange,
            onHapticsEnabledChange = onHapticsEnabledChange,
            modifier = modifier
        )
    }
}

@Composable
private fun ContentForLandscape(
    state: SceneAnalysisUiState,
    onToggleClick: () -> Unit,
    onSpeechEnabledChange: (Boolean) -> Unit,
    onHapticsEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxSize()) {
        CaptureView(
            segMask = state.segMask,
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
    onSpeechEnabledChange: (Boolean) -> Unit,
    onHapticsEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        CaptureView(
            segMask = state.segMask,
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
    }
}

@Composable
private fun ControlView(
    isRunning: Boolean,
    isLoading: Boolean,
    isSpeechEnabled: Boolean,
    isHapticsEnabled: Boolean,
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
    }
}
