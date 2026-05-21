package com.friady.sailens.domain.processor.analysis

import com.friady.sailens.domain.config.AnalysisConfig
import com.friady.sailens.domain.model.analysis.WalkPathConnectivity
import com.friady.sailens.domain.model.common.BinaryMask
import com.friady.sailens.domain.model.common.BottomStats
import com.friady.sailens.domain.model.common.DirectionBias
import com.friady.sailens.domain.model.common.Severity
import com.friady.sailens.domain.util.BooleanStabilizer
import com.friady.sailens.domain.util.IntArrayQueue
import com.friady.sailens.domain.util.NullableEnumStabilizer
import com.friady.sailens.domain.util.packCoordinate
import com.friady.sailens.domain.util.unpackCoordinateX
import com.friady.sailens.domain.util.unpackCoordinateY

import kotlin.math.abs

/**
 * 连通性分析器
 */
class ConnectivityChecker(
    private val config: AnalysisConfig,
) {
    private val blockedStabilizer = BooleanStabilizer(config.blockDebounceFrames)
    private val narrowingStabilizer = BooleanStabilizer(config.narrowDebounceFrames)
    private val biasStabilizer = NullableEnumStabilizer<DirectionBias>(config.biasDebounceFrames)

    fun analyze(passableMask: BinaryMask): WalkPathConnectivity {
        val bottomStats = passableMask.getBottomStats(0.15f)

        // 1. 分层扫描
        val layerResults = performLayerScan(passableMask)
        val verticalReachRatio = layerResults.validLayers.toFloat() / config.sampleLayerRatios.size

        // 2.  宽度统计
        val widthStats = computeWidthRetention(layerResults, bottomStats)

        // 3.  洪泛分析
        val floodResult = performFloodFill(passableMask, bottomStats)

        // 4. 计算置信度
        val blockageConfidence = calculateBlockageConfidence(
            verticalReachRatio, floodResult.reachRatio, widthStats.p25
        )
        val narrowingConfidence = calculateNarrowingConfidence(
            widthStats.p25, widthStats.slope
        )

        // 5.  布尔判定
        val isBlockedRaw = blockageConfidence >= config.blockageThreshold
        val isNarrowingRaw = narrowingConfidence >= config.narrowingThreshold && !isBlockedRaw

        // 6. 稳定化
        val isBlocked = blockedStabilizer.update(isBlockedRaw)
        val isNarrowing = narrowingStabilizer.update(isNarrowingRaw)

        // 7. 方向建议
        val suggestedBiasRaw = computeDirectionBias(layerResults)
        val suggestedBias = biasStabilizer.update(suggestedBiasRaw)

        return WalkPathConnectivity(
            isBlocked = isBlocked,
            isNarrowing = isNarrowing,
            suggestedBias = suggestedBias,
            blockageConfidence = blockageConfidence,
            narrowingConfidence = narrowingConfidence,
            blockageSeverity = Severity.fromConfidence(blockageConfidence),
            narrowingSeverity = Severity.fromConfidence(narrowingConfidence),
            verticalReachRatio = verticalReachRatio,
            validLayers = layerResults.validLayers,
            totalLayers = config.sampleLayerRatios.size,
            widthRetentionAvg = widthStats.avg,
            widthRetentionP25 = widthStats.p25,
            widthSlope = widthStats.slope,
            floodReachRatio = floodResult.reachRatio,
            floodWidthRetentionP25 = floodResult.widthP25,
            floodVisitedRatio = floodResult.visitedRatio
        )
    }

    private fun calculateBlockageConfidence(
        verticalReachRatio: Float,
        floodReachRatio: Float,
        widthP25: Float,
    ): Float {
        var score = 0f
        val continuityPenaltyScale = when {
            widthP25 >= config.narrowEnterP25 -> 0.35f
            widthP25 >= config.narrowEnterP25 * 0.8f -> 0.6f
            else -> 1f
        }

        if (verticalReachRatio < config.reachRatioThreshold) {
            score += 0.35f * continuityPenaltyScale * (1 - verticalReachRatio / config.reachRatioThreshold)
        }
        if (floodReachRatio < config.minFloodReachRatio) {
            score += 0.35f * continuityPenaltyScale * (1 - floodReachRatio / config.minFloodReachRatio)
        }
        if (widthP25 < config.narrowEnterP25 * 0.6f) {
            score += 0.30f * (1 - widthP25 / (config.narrowEnterP25 * 0.6f))
        }

        return score.coerceIn(0f, 1f)
    }

    private fun calculateNarrowingConfidence(widthP25: Float, slope: Float): Float {
        var score = 0f

        if (widthP25 < config.narrowEnterP25) {
            score += 0.5f * (1 - widthP25 / config.narrowEnterP25)
        }
        if (slope < config.narrowSlopeThreshold) {
            score += 0.5f * (abs(slope) / abs(config.narrowSlopeThreshold)).coerceAtMost(1f)
        }

        return score.coerceIn(0f, 1f)
    }

    private fun performLayerScan(mask: BinaryMask): LayerScanResult {
        val layers = mutableListOf<LayerInfo>()
        var validLayers = 0

        for (ratio in config.sampleLayerRatios) {
            val row = (ratio * mask.height).toInt().coerceIn(0, mask.height - 1)
            var maxRunWidth = 0
            var maxRunCenter = 0.5f

            for (r in maxOf(0, row - 1)..minOf(mask.height - 1, row + 1)) {
                val runs = mask.getRowRuns(r)
                for (run in runs) {
                    val width = run.last - run.first + 1
                    if (width > maxRunWidth) {
                        maxRunWidth = width
                        maxRunCenter = (run.first + run.last) / 2f / mask.width
                    }
                }
            }

            val widthRatio = maxRunWidth.toFloat() / mask.width
            val isValid = widthRatio >= config.minRunWidthRatio
            if (isValid) validLayers++

            layers.add(LayerInfo(row, ratio, maxRunWidth, widthRatio, maxRunCenter, isValid))
        }

        return LayerScanResult(layers, validLayers)
    }

    private fun computeWidthRetention(
        layerResult: LayerScanResult,
        bottomStats: BottomStats,
    ): WidthStats {
        val bottomWidth = bottomStats.maxRunWidth.toFloat()

        if (bottomWidth < 1f) {
            return WidthStats(0f, 0f, 0f)
        }

        val retentions = layerResult.layers
            .filter { it.isValid }
            .map { it.maxRunWidth / bottomWidth }

        if (retentions.isEmpty()) {
            return WidthStats(0f, 0f, 0f)
        }

        val avg = retentions.average().toFloat()
        val sorted = retentions.sorted()
        val p25Index = (sorted.size * 0.25).toInt().coerceIn(0, sorted.size - 1)
        val p25 = sorted[p25Index]

        val topRetention = layerResult.layers.lastOrNull()?.let {
            it.maxRunWidth / bottomWidth
        } ?: 1f
        val slope = topRetention - 1f

        return WidthStats(avg, p25, slope)
    }

    private fun performFloodFill(mask: BinaryMask, bottomStats: BottomStats): FloodResult {
        if (bottomStats.maxRunWidth < mask.width * config.minRunWidthRatio) {
            return FloodResult(0f, 0f, 0f)
        }

        val windowTop = (config.floodWindowTopRatio * mask.height).toInt()
        val windowBottom = mask.height - 1
        val windowHeight = windowBottom - windowTop

        if (windowHeight <= 0) {
            return FloodResult(0f, 0f, 0f)
        }

        val seedY = bottomStats.maxRunRow.coerceIn(windowTop, windowBottom)
        val seedStartX = bottomStats.maxRunStart
        val seedEndX = bottomStats.maxRunEnd
        val reachableWindowHeight = maxOf(1, seedY - windowTop)
        val seedCount = minOf(32, seedEndX - seedStartX + 1)
        val seedStep = maxOf(1, (seedEndX - seedStartX) / seedCount)

        val queue = IntArrayQueue()
        var x = seedStartX
        var generatedSeeds = 0
        while (x <= seedEndX && generatedSeeds < seedCount) {
            if (mask.get(x, seedY)) {
                queue.addLast(packCoordinate(x, seedY))
                generatedSeeds++
            }
            x += seedStep
        }

        if (queue.size == 0) {
            return FloodResult(0f, 0f, 0f)
        }

        val visited = BinaryMask(mask.width, mask.height)
        val rowWidths = IntArray(mask.height)
        var visitedCount = 0
        var minYReached = seedY

        val dx = intArrayOf(0, 1, 0, -1, 1, 1, -1, -1)
        val dy = intArrayOf(-1, 0, 1, 0, -1, 1, 1, -1)

        while (queue.isNotEmpty() && visitedCount < config.maxFloodNodes) {
            val packed = queue.removeFirst()
            val cx = unpackCoordinateX(packed)
            val cy = unpackCoordinateY(packed)

            if (cx < 0 || cx >= mask.width || cy < windowTop || cy > windowBottom) continue
            if (visited.get(cx, cy) || !mask.get(cx, cy)) continue

            visited.set(cx, cy, true)
            visitedCount++
            minYReached = minOf(minYReached, cy)
            rowWidths[cy]++

            for (i in 0..7) {
                val nextX = cx + dx[i]
                val nextY = cy + dy[i]
                queue.addLast(packCoordinate(nextX, nextY))

                if (i > 3) continue

                val bridgeX = cx + dx[i] * 2
                val bridgeY = cy + dy[i] * 2
                if (bridgeX !in 0 until mask.width || bridgeY < windowTop || bridgeY > windowBottom) {
                    continue
                }
                if (mask.get(nextX, nextY) || !mask.get(bridgeX, bridgeY)) {
                    continue
                }

                queue.addLast(packCoordinate(bridgeX, bridgeY))
            }

            val currentReach = (seedY - minYReached).toFloat() / reachableWindowHeight
            if (currentReach >= config.floodEarlyStopReachRatio) {
                var totalRowWidth = 0
                var activeRows = 0
                for (row in windowTop..windowBottom) {
                    val rowWidth = rowWidths[row]
                    if (rowWidth > 0) {
                        totalRowWidth += rowWidth
                        activeRows++
                    }
                }
                val avgRowWidth = if (activeRows > 0) totalRowWidth.toFloat() / activeRows else 0f
                val retention = avgRowWidth / bottomStats.maxRunWidth
                if (retention >= config.floodEarlyStopWidthRetention) {
                    break
                }
            }
        }

        val reachRatio = (seedY - minYReached).toFloat() / reachableWindowHeight
        val windowArea = windowHeight * mask.width
        val visitedRatio = visitedCount.toFloat() / windowArea

        val widthRetentions = buildList {
            for (row in windowTop..windowBottom) {
                val rowWidth = rowWidths[row]
                if (rowWidth > 0) {
                    add(rowWidth.toFloat() / bottomStats.maxRunWidth)
                }
            }
        }
        val widthP25 = if (widthRetentions.isNotEmpty()) {
            val sorted = widthRetentions.sorted()
            sorted[(sorted.size * 0.25).toInt().coerceIn(0, sorted.size - 1)]
        } else 0f

        return FloodResult(reachRatio, widthP25, visitedRatio)
    }

    private fun computeDirectionBias(layerResult: LayerScanResult): DirectionBias? {
        var leftWeight = 0f
        var rightWeight = 0f

        for ((index, layer) in layerResult.layers.withIndex()) {
            if (!layer.isValid) continue

            val offset = layer.maxRunCenter - 0.5f
            val weight = 1f + index * 0.5f

            if (offset < -0.1f) {
                leftWeight += abs(offset) * weight
            } else if (offset > 0.1f) {
                rightWeight += abs(offset) * weight
            }
        }

        val threshold = config.directionBiasThreshold

        return when {
            leftWeight > rightWeight + threshold -> DirectionBias.LEFT
            rightWeight > leftWeight + threshold -> DirectionBias.RIGHT
            else -> null
        }
    }

    fun reset() {
        blockedStabilizer.reset()
        narrowingStabilizer.reset()
        biasStabilizer.reset()
    }
}

data class LayerInfo(
    val row: Int,
    val ratio: Float,
    val maxRunWidth: Int,
    val maxRunWidthRatio: Float,
    val maxRunCenter: Float,
    val isValid: Boolean,
)

data class LayerScanResult(
    val layers: List<LayerInfo>,
    val validLayers: Int,
)

data class WidthStats(
    val avg: Float,
    val p25: Float,
    val slope: Float,
)

data class FloodResult(
    val reachRatio: Float,
    val widthP25: Float,
    val visitedRatio: Float,
)
