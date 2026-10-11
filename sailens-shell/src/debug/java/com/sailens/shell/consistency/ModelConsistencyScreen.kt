package com.sailens.shell.consistency

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sailens.shell.R
import com.sailens.shell.camera.CameraViewWithPermission
import com.sailens.shell.design.components.SailensScaffold
import com.sailens.shell.design.theme.SailensDimens
import com.sailens.vision.consistency.ConsistencyVerdict
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun ModelConsistencyScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ModelConsistencyViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    SailensScaffold(
        title = stringResource(R.string.title_model_consistency_page),
        onNavigateBack = onNavigateBack,
        navigateBackContentDescription = stringResource(R.string.cd_navigate_back),
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(SailensDimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(SailensDimens.spaceMd),
        ) {
            Text(stringResource(R.string.model_consistency_intro), style = MaterialTheme.typography.bodyMedium)
            // Binds the camera, so snapshots exist; also lets the person aim it.
            CameraViewWithPermission(modifier = Modifier.fillMaxWidth().height(PREVIEW_HEIGHT))
            if (state.guidanceActive) {
                Text(
                    text = stringResource(R.string.model_consistency_guidance_active),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            Button(onClick = viewModel::run, enabled = state.canRun, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.btn_model_consistency_run))
            }
            when (val phase = state.phase) {
                ConsistencyPhase.Idle -> Unit
                is ConsistencyPhase.Running -> Text(
                    text = stringResource(R.string.model_consistency_running, phase.done, phase.total),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                is ConsistencyPhase.Failed -> Text(
                    text = stringResource(R.string.model_consistency_failed, phase.message),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                is ConsistencyPhase.Done -> Results(phase, viewModel.semanticLabel)
            }
        }
    }
}

@Composable
private fun Results(done: ConsistencyPhase.Done, semanticLabel: (Int) -> String) {
    val result = done.result
    Column(
        verticalArrangement = Arrangement.spacedBy(SailensDimens.spaceSm),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            text = when (result.overall) {
                ConsistencyVerdict.PASS -> stringResource(R.string.model_consistency_overall_pass)
                ConsistencyVerdict.FAIL -> stringResource(R.string.model_consistency_overall_fail)
                ConsistencyVerdict.INCONCLUSIVE -> stringResource(R.string.model_consistency_overall_inconclusive)
                null -> stringResource(R.string.model_consistency_overall_none)
            },
            style = MaterialTheme.typography.titleMedium,
            color = if (result.overall == ConsistencyVerdict.FAIL) MaterialTheme.colorScheme.error else Color.Unspecified,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            text = stringResource(R.string.model_consistency_coverage, result.modelsChecked, result.modelsApplicable),
            style = MaterialTheme.typography.bodySmall,
        )
        if (result.framesChanged > 0) {
            Text(
                text = stringResource(R.string.model_consistency_frames_changed, result.framesChanged),
                color = MaterialTheme.colorScheme.error,
            )
        }
        ModelCard(
            title = stringResource(R.string.model_consistency_semantic),
            inconclusiveText = stringResource(R.string.model_consistency_semantic_inconclusive),
            status = result.semantic.status,
            failure = result.semantic.failure,
            verdict = result.semanticVerdict,
            accelerators = result.semantic.result?.accelerators,
            details = result.semantic.result?.consistency?.let { c ->
                listOfNotNull(
                    stringResource(R.string.model_consistency_semantic_metrics, percent(c.meanAgreement), percent(c.minAgreement)),
                    pooledConfusions(c.frames).takeIf { it.isNotEmpty() }?.let { top ->
                        stringResource(
                            R.string.model_consistency_confusions,
                            top.joinToString { (pair, f) -> "${semanticLabel(pair.first)}→${semanticLabel(pair.second)} ${percent(f)}" },
                        )
                    },
                )
            }.orEmpty(),
        )
        ModelCard(
            title = stringResource(R.string.model_consistency_detection),
            inconclusiveText = stringResource(R.string.model_consistency_verdict_inconclusive),
            status = result.detection.status,
            failure = result.detection.failure,
            verdict = result.detectionVerdict,
            accelerators = result.detection.result?.accelerators,
            details = result.detection.result?.consistency?.let { c ->
                listOf(
                    stringResource(
                        R.string.model_consistency_detection_metrics,
                        c.recall?.let(::percent) ?: "–",
                        c.frames.sumOf { it.referenceCount },
                        c.extraFraction?.let(::percent) ?: "–",
                        c.frames.sumOf { it.candidateCount },
                    ),
                )
            }.orEmpty(),
        )
        done.reportPath?.let {
            Text(stringResource(R.string.model_consistency_report_saved, it), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ModelCard(
    title: String,
    inconclusiveText: String,
    status: ModelCheckStatus,
    failure: String?,
    verdict: ConsistencyVerdict?,
    accelerators: AcceleratorPair?,
    details: List<String>,
) {
    Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(SailensDimens.spaceMd),
            verticalArrangement = Arrangement.spacedBy(SailensDimens.spaceXs),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            val (line, color) = when (status) {
                ModelCheckStatus.INCOMPLETE -> if (verdict == ConsistencyVerdict.FAIL) {
                    stringResource(R.string.model_consistency_verdict_fail) to MaterialTheme.colorScheme.error
                } else {
                    stringResource(R.string.model_consistency_status_incomplete, failure.orEmpty()) to Color.Unspecified
                }
                ModelCheckStatus.CHECKED -> when (verdict) {
                    ConsistencyVerdict.PASS -> stringResource(R.string.model_consistency_verdict_pass) to Color.Unspecified
                    ConsistencyVerdict.FAIL -> stringResource(R.string.model_consistency_verdict_fail) to MaterialTheme.colorScheme.error
                    else -> inconclusiveText to Color.Unspecified
                }
                ModelCheckStatus.NOT_APPLICABLE -> stringResource(R.string.model_consistency_status_not_applicable) to Color.Unspecified
                ModelCheckStatus.UNAVAILABLE -> stringResource(R.string.model_consistency_status_unavailable, failure.orEmpty()) to Color.Unspecified
                ModelCheckStatus.NO_FRAMES -> stringResource(R.string.model_consistency_status_no_frames) to MaterialTheme.colorScheme.error
            }
            Text(line, color = color, style = MaterialTheme.typography.bodyLarge)
            accelerators?.let {
                Text(stringResource(R.string.model_consistency_accelerators, it.candidate, it.reference), style = MaterialTheme.typography.bodySmall)
            }
            details.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

private fun percent(fraction: Float): String = "%.1f%%".format(fraction * 100)

private val PREVIEW_HEIGHT = 240.dp
