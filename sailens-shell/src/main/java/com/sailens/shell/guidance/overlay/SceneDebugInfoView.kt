package com.sailens.shell.guidance.overlay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sailens.guidance.model.scene.SceneDebugInfo
import com.sailens.shell.R

/**
 * Dense live-pipeline metrics panel for the debug-only guidance surface. It is an engineering
 * readout, not a polished user-facing status summary.
 */
@Composable
internal fun SceneDebugInfoView(
    debugInfo: SceneDebugInfo,
    maskSourceAgeMs: Long,
    modifier: Modifier = Modifier,
    scrollable: Boolean = false,
) {
    val passablePercent = (debugInfo.passableRatio * 100).toInt()
    val navigationPassablePercent = (debugInfo.navigationPassableRatio * 100).toInt()
    val navigationPassableDeltaPercent = (debugInfo.navigationPassableDelta * 100).toInt()
    val obstaclePercent = (debugInfo.obstacleRatio * 100).toInt()
    val roadPercent = (debugInfo.roadRatio * 100).toInt()
    val roadDeltaPercent = (debugInfo.roadRatioDelta * 100).toInt()
    val bottomCoveragePercent = (debugInfo.bottomCoverage * 100).toInt()
    val bottomWidthPercent = (debugInfo.bottomMaxRunWidthRatio * 100).toInt()
    val blockageConfidencePercent = (debugInfo.blockageConfidence * 100).toInt()
    val blockageConfidenceDeltaPercent = (debugInfo.blockageConfidenceDelta * 100).toInt()
    val roadVehicleConfidencePercent = (debugInfo.roadVehicleConfidence * 100).toInt()
    val roadVehicleBottomYPercent = (debugInfo.roadVehicleBottomY * 100).toInt()
    val roadVehicleCenterBandOverlapPercent = (debugInfo.roadVehicleCenterBandOverlap * 100).toInt()
    val roadVehicleAreaPercent = (debugInfo.roadVehicleAreaRatio * 100).toInt()
    val occludedPassablePercent = (debugInfo.occludedPassableRatio * 100).toInt()
    val verticalReachPercent = (debugInfo.verticalReachRatio * 100).toInt()
    val verticalReachDeltaPercent = (debugInfo.verticalReachDelta * 100).toInt()
    val floodReachPercent = (debugInfo.floodReachRatio * 100).toInt()
    val floodReachDeltaPercent = (debugInfo.floodReachDelta * 100).toInt()
    val widthRetentionPercent = (debugInfo.widthRetentionP25 * 100).toInt()
    val widthRetentionDeltaPercent = (debugInfo.widthRetentionP25Delta * 100).toInt()
    val recentDroppedPercent = (debugInfo.recentDroppedFrameRate * 100).toInt()
    val dominantClasses = debugInfo.dominantClasses.ifEmpty {
        listOf(stringResource(R.string.value_unknown))
    }.joinToString()
    val rawObstacleClasses = debugInfo.rawObstacleClassNames.ifEmpty {
        listOf(stringResource(R.string.value_unknown))
    }.joinToString()
    val trackedObstacleCategories = debugInfo.trackedObstacleCategories.ifEmpty {
        listOf(stringResource(R.string.value_unknown))
    }.joinToString()

    val scrollState = rememberScrollState()
    val scrollModifier = if (scrollable) modifier.verticalScroll(scrollState) else modifier

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = scrollModifier,
    ) {
        Text(
            text = stringResource(R.string.title_live_pipeline_debug),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(stringResource(R.string.debug_semantic_provider, debugInfo.semanticProvider))
        Text(stringResource(R.string.debug_obstacle_provider, debugInfo.obstacleProvider))
        Text(stringResource(R.string.debug_perception_profile, debugInfo.perceptionProfile))
        Text(
            stringResource(
                R.string.debug_semantic_runtime,
                debugInfo.semanticRuntimeInfo.accelerator,
                debugInfo.semanticRuntimeInfo.acceleratorSelection,
            )
        )
        Text(
            stringResource(
                R.string.debug_semantic_backends,
                debugInfo.semanticRuntimeInfo.preprocessBackend,
                debugInfo.semanticRuntimeInfo.postprocessBackend,
            )
        )
        Text(
            stringResource(
                R.string.debug_obstacle_runtime,
                debugInfo.obstacleRuntimeInfo.accelerator,
                debugInfo.obstacleRuntimeInfo.acceleratorSelection,
            )
        )
        Text(
            stringResource(
                R.string.debug_obstacle_backends,
                debugInfo.obstacleRuntimeInfo.preprocessBackend,
                debugInfo.obstacleRuntimeInfo.postprocessBackend,
            )
        )
        Text(stringResource(R.string.debug_passable_ratio, passablePercent))
        Text(stringResource(R.string.debug_navigation_passable_ratio, navigationPassablePercent))
        Text(stringResource(R.string.debug_obstacle_ratio, obstaclePercent))
        Text(stringResource(R.string.debug_road_ratio, roadPercent))
        Text(stringResource(R.string.debug_bottom_coverage, bottomCoveragePercent))
        Text(stringResource(R.string.debug_bottom_width_ratio, bottomWidthPercent))
        Text(stringResource(R.string.debug_blockage_confidence, blockageConfidencePercent))
        Text(stringResource(R.string.debug_blockage_reason, debugInfo.blockageReason))
        Text(
            stringResource(
                R.string.debug_road_vehicle_source,
                debugInfo.roadVehicleSource,
                roadVehicleConfidencePercent,
                debugInfo.roadVehicleReason,
                roadVehicleBottomYPercent,
                roadVehicleCenterBandOverlapPercent,
                roadVehicleAreaPercent,
            )
        )
        Text(stringResource(R.string.debug_occluded_passable, occludedPassablePercent))
        Text(
            stringResource(
                R.string.debug_ground_recognition,
                debugInfo.groundRecognition,
                (debugInfo.unrecognizedGroundRatio * 100).toInt(),
            )
        )
        Text(
            stringResource(
                R.string.debug_vertical_reach,
                verticalReachPercent,
                debugInfo.validLayers,
                debugInfo.totalLayers,
            )
        )
        Text(stringResource(R.string.debug_flood_reach, floodReachPercent))
        Text(stringResource(R.string.debug_width_retention_p25, widthRetentionPercent))
        Text(
            stringResource(
                R.string.debug_stability_deltas,
                navigationPassableDeltaPercent,
                roadDeltaPercent,
                blockageConfidenceDeltaPercent,
                verticalReachDeltaPercent,
                floodReachDeltaPercent,
                widthRetentionDeltaPercent,
            )
        )
        Text(stringResource(R.string.debug_dominant_classes, dominantClasses))
        Text(stringResource(R.string.debug_raw_obstacle_classes, rawObstacleClasses))
        Text(stringResource(R.string.debug_tracked_obstacle_categories, trackedObstacleCategories))
        Text(stringResource(R.string.debug_total_pipeline_ms, debugInfo.totalPipelineMs))
        Text(
            stringResource(
                R.string.debug_pipeline_breakdown_ms,
                debugInfo.processFrameMs,
                debugInfo.analyzeSceneMs,
                debugInfo.decideEventsMs,
            )
        )
        Text(stringResource(R.string.debug_inference_ms, debugInfo.inferenceMs))
        Text(stringResource(R.string.debug_mask_source_age_ms, maskSourceAgeMs))
        Text(stringResource(R.string.debug_recent_avg_pipeline_ms, debugInfo.recentAvgTotalPipelineMs))
        Text(stringResource(R.string.debug_recent_p95_pipeline_ms, debugInfo.recentP95TotalPipelineMs))
        Text(
            stringResource(
                R.string.debug_recent_dropped_rate,
                recentDroppedPercent,
                debugInfo.droppedFramesSinceLast,
            )
        )
        Text(stringResource(R.string.debug_tracked_obstacle_count, debugInfo.trackedObstacleCount))
        Text(
            stringResource(
                R.string.debug_raw_obstacle_detection_count,
                debugInfo.rawObstacleDetectionCount,
            )
        )
        Text(
            stringResource(
                R.string.debug_obstacle_count_deltas,
                debugInfo.obstacleCountDelta,
                debugInfo.rawObstacleDetectionCountDelta,
            )
        )
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
