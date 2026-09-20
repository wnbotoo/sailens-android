package com.sailens.domain.processor.perception

import com.sailens.domain.config.PerceptionConfig
import com.sailens.core.mask.BinaryMask
import com.sailens.domain.model.common.DirectionZone
import com.sailens.domain.model.common.DistanceLevel
import com.sailens.core.geometry.NormalizedRect
import com.sailens.domain.model.common.ObstacleCategory
import com.sailens.domain.semantics.NavigationSemantics
import com.sailens.vision.taxonomy.CityscapesTaxonomy
import com.sailens.vision.taxonomy.Taxonomy
import com.sailens.domain.model.perception.ObstacleDetection
import com.sailens.domain.model.perception.RawObstacle
import com.sailens.domain.model.perception.SegmentationAnalysis
import com.sailens.domain.model.perception.SegmentationMask
import com.sailens.domain.util.IntArrayQueue
import com.sailens.domain.util.packCoordinate
import com.sailens.domain.util.unpackCoordinateX
import com.sailens.domain.util.unpackCoordinateY
import kotlin.math.max
import kotlin.math.min

/**
 * 障碍物提取器
 */
class ObstacleExtractor(
    private val config: PerceptionConfig,
    private val navigationSemantics: NavigationSemantics,
    // Labels come from the taxonomy, not the semantics: they are a dataset fact (§6.2). These
    // components come off the *semantic* obstacle mask, so the default is the semantic taxonomy;
    // they reach traces and debug overlays only, never a routing decision.
    private val taxonomy: Taxonomy = CityscapesTaxonomy,
) {
    // Reused across frames to avoid per-frame allocation on the hot BFS path.
    // Single-threaded: ProcessFrameUseCase invokes this synchronously within its dispatcher.
    private var visitedMask: BinaryMask? = null
    private val classCountsBuffer = IntArray(navigationSemantics.classCount.coerceAtLeast(1))

    // Shared BFS queue reused across all connected-component passes within a single frame.
    // BFS always drains the queue completely, so clear() on entry is a safety-only reset.
    private val bfsQueue = IntArrayQueue(initialCapacity = 1024)

    /**
     * 从语义分割提取障碍物
     */
    fun extractFromSemantic(
        analysis: SegmentationAnalysis,
        depthEstimator: (NormalizedRect) -> DistanceLevel,
    ): List<RawObstacle> {
        val obstacleMask = analysis.obstacleMask
        val segmentation = analysis.segmentation

        val components = findConnectedComponents(obstacleMask, segmentation)
        val totalPixels = obstacleMask.width * obstacleMask.height
        val minPixels = (totalPixels * config.minObstacleAreaRatio).toInt()

        return components
            .filter { it.pixelCount >= minPixels }
            .mapNotNull { component ->
                val box = NormalizedRect.fromPixels(
                    component.minX, component.minY,
                    component.width, component.height,
                    obstacleMask.width, obstacleMask.height
                )

                val category = component.category
                if (category == ObstacleCategory.UNKNOWN) return@mapNotNull null

                val areaRatio = component.pixelCount.toFloat() / totalPixels
                if (!isSemanticObstacleRelevant(box, category, areaRatio)) return@mapNotNull null

                val zone = DirectionZone.fromNormalizedX(box.centerX, config.zoneMode)
                val distance = depthEstimator(box)

                RawObstacle(
                    boundingBox = box,
                    category = category,
                    className = taxonomy.label(component.classId),
                    zone = zone,
                    distance = distance,
                    confidence = 1.0f,
                    areaRatio = areaRatio
                )
            }
            .sortedByDescending { it.areaRatio }
            .take(config.maxObstacles)
    }

    /**
     * 从障碍物模型检测结果提取障碍物
     */
    fun extractFromDetections(
        detections: List<ObstacleDetection>,
        depthEstimator: (NormalizedRect) -> DistanceLevel,
    ): List<RawObstacle> {
        return detections
            .filter { it.confidence >= config.minObstacleConfidence }
            .filter { it.category != ObstacleCategory.UNKNOWN }
            .filter { isDetectionRelevant(it.boundingBox, it.category) }
            .map { detection ->
                val zone =
                    DirectionZone.fromNormalizedX(detection.boundingBox.centerX, config.zoneMode)
                val distance = depthEstimator(detection.boundingBox)

                RawObstacle(
                    boundingBox = detection.boundingBox,
                    category = detection.category,
                    className = detection.className,
                    zone = zone,
                    distance = distance,
                    confidence = detection.confidence,
                    areaRatio = detection.boundingBox.area
                )
            }
            .sortedByDescending { it.areaRatio }
            .take(config.maxObstacles)
    }

    private fun isSemanticObstacleRelevant(
        box: NormalizedRect,
        category: ObstacleCategory,
        areaRatio: Float,
    ): Boolean {
        val corridorOverlap = corridorOverlapRatio(box)
        val nearNavigationArea = box.maxY >= config.semanticObstacleMinBottomY

        if (category == ObstacleCategory.STATIC_OBSTACLE) {
            if (areaRatio > config.maxBackgroundObstacleAreaRatio) {
                return false
            }
            return box.maxY >= config.staticObstacleMinBottomY &&
                corridorOverlap >= config.staticObstacleMinCorridorOverlapRatio
        }

        return nearNavigationArea ||
            corridorOverlap >= config.semanticObstacleMinCorridorOverlapRatio
    }

    private fun isDetectionRelevant(
        box: NormalizedRect,
        category: ObstacleCategory,
    ): Boolean {
        val corridorOverlap = corridorOverlapRatio(box)
        if (category == ObstacleCategory.STATIC_OBSTACLE) {
            return box.maxY >= config.staticObstacleMinBottomY ||
                corridorOverlap >= config.staticObstacleMinCorridorOverlapRatio
        }

        return box.maxY >= config.semanticObstacleMinBottomY ||
            corridorOverlap >= config.semanticObstacleMinCorridorOverlapRatio
    }

    private fun corridorOverlapRatio(box: NormalizedRect): Float {
        val halfWidth = corridorWidthAt(box.maxY) / 2f
        val corridorStart = 0.5f - halfWidth
        val corridorEnd = 0.5f + halfWidth
        val overlap = max(0f, min(box.maxX, corridorEnd) - max(box.x, corridorStart))
        return overlap / box.width.coerceAtLeast(0.0001f)
    }

    private fun corridorWidthAt(bottomY: Float): Float {
        val nearWidth = config.navigationCorridorCenterWidth.coerceIn(0.1f, 1.0f)
        val farWidth = config.navigationCorridorFarWidth.coerceIn(0.05f, nearWidth)
        val horizonY = config.navigationCorridorHorizonY.coerceIn(0f, 0.95f)
        val depthProgress = ((bottomY - horizonY) / (1f - horizonY)).coerceIn(0f, 1f)
        return farWidth + (nearWidth - farWidth) * depthProgress
    }

    private fun findConnectedComponents(
        mask: BinaryMask,
        segmentation: SegmentationMask,
    ): List<ConnectedComponent> {
        val existing = visitedMask
        val visited = if (existing != null && existing.width == mask.width && existing.height == mask.height) {
            existing.also { it.clear() }
        } else {
            BinaryMask(mask.width, mask.height).also { visitedMask = it }
        }
        val components = mutableListOf<ConnectedComponent>()

        for (y in 0 until mask.height) {
            for (x in 0 until mask.width) {
                if (mask.get(x, y) && !visited.get(x, y)) {
                    components.add(bfs(mask, visited, segmentation, x, y))
                }
            }
        }

        return components
    }

    // Runs BFS from (startX, startY), accumulating category vote counts inline.
    // Eliminates the per-component IntArrayList + toIntArray() allocations of the previous design.
    private fun bfs(
        mask: BinaryMask,
        visited: BinaryMask,
        segmentation: SegmentationMask,
        startX: Int,
        startY: Int,
    ): ConnectedComponent {
        bfsQueue.clear()
        classCountsBuffer.fill(0)

        bfsQueue.addLast(packCoordinate(startX, startY))
        visited.set(startX, startY, true)

        var pixelCount = 0
        var minX = startX
        var maxX = startX
        var minY = startY
        var maxY = startY

        while (bfsQueue.isNotEmpty()) {
            val packed = bfsQueue.removeFirst()
            val x = unpackCoordinateX(packed)
            val y = unpackCoordinateY(packed)
            pixelCount++

            val classId = segmentation.getClassId(x, y)
            if (classId in classCountsBuffer.indices &&
                navigationSemantics.toObstacleCategory(classId) != ObstacleCategory.UNKNOWN
            ) {
                classCountsBuffer[classId]++
            }

            if (x < minX) minX = x else if (x > maxX) maxX = x
            if (y < minY) minY = y else if (y > maxY) maxY = y

            for (i in 0..3) {
                val nx = x + NEIGHBORS_DX[i]
                val ny = y + NEIGHBORS_DY[i]

                if (nx >= 0 && nx < mask.width && ny >= 0 && ny < mask.height &&
                    mask.get(nx, ny) && !visited.get(nx, ny)
                ) {
                    visited.set(nx, ny, true)
                    bfsQueue.addLast(packCoordinate(nx, ny))
                }
            }
        }

        var bestCategory = ObstacleCategory.UNKNOWN
        var bestClassId = -1
        var bestCount = 0
        for (i in classCountsBuffer.indices) {
            val count = classCountsBuffer[i]
            if (count > bestCount) {
                bestClassId = i
                bestCount = count
                bestCategory = navigationSemantics.toObstacleCategory(i)
            }
        }

        return ConnectedComponent(
            pixelCount = pixelCount,
            minX = minX,
            minY = minY,
            maxX = maxX,
            maxY = maxY,
            category = bestCategory,
            classId = bestClassId,
        )
    }

    private companion object {
        private val NEIGHBORS_DX = intArrayOf(0, 1, 0, -1)
        private val NEIGHBORS_DY = intArrayOf(-1, 0, 1, 0)
    }
}

/**
 * 连通区域
 */
class ConnectedComponent(
    val pixelCount: Int,
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int,
    val category: ObstacleCategory,
    val classId: Int,
) {
    val width: Int get() = maxX - minX + 1
    val height: Int get() = maxY - minY + 1
}
