package com.friady.sailens.domain.processor.perception

import com.friady.sailens.domain.config.PerceptionConfig
import com.friady.sailens.domain.model.common.BinaryMask
import com.friady.sailens.domain.model.common.DirectionZone
import com.friady.sailens.domain.model.common.DistanceLevel
import com.friady.sailens.domain.model.common.NormalizedRect
import com.friady.sailens.domain.model.common.ObstacleCategory
import com.friady.sailens.domain.model.perception.ClassMapper
import com.friady.sailens.domain.model.perception.DetectedInstance
import com.friady.sailens.domain.model.perception.RawObstacle
import com.friady.sailens.domain.model.perception.SegmentationAnalysis
import com.friady.sailens.domain.model.perception.SegmentationMask

/**
 * 障碍物提取器
 */
class ObstacleExtractor(
    private val config: PerceptionConfig,
    private val classMapper: ClassMapper,
) {
    /**
     * 从语义分割提取障碍物
     */
    fun extractFromSemantic(
        analysis: SegmentationAnalysis,
        depthEstimator: (NormalizedRect) -> DistanceLevel,
    ): List<RawObstacle> {
        val obstacleMask = analysis.obstacleMask
        val segmentation = analysis.segmentation

        val components = findConnectedComponents(obstacleMask)
        val totalPixels = obstacleMask.width * obstacleMask.height
        val minPixels = (totalPixels * config.minObstacleAreaRatio).toInt()

        return components
            .filter { it.pixels.size >= minPixels }
            .map { component ->
                val box = NormalizedRect.fromPixels(
                    component.minX, component.minY,
                    component.width, component.height,
                    obstacleMask.width, obstacleMask.height
                )

                val category = inferCategory(component, segmentation)
                val zone = DirectionZone.fromNormalizedX(box.centerX, config.zoneMode)
                val distance = depthEstimator(box)

                RawObstacle(
                    boundingBox = box,
                    category = category,
                    zone = zone,
                    distance = distance,
                    confidence = 1.0f,
                    areaRatio = component.pixels.size.toFloat() / totalPixels
                )
            }
            .sortedByDescending { it.areaRatio }
            .take(config.maxObstacles)
    }

    /**
     * 从实例分割提取障碍物
     */
    fun extractFromInstances(
        instances: List<DetectedInstance>,
        depthEstimator: (NormalizedRect) -> DistanceLevel,
    ): List<RawObstacle> {
        return instances
            .filter { it.confidence >= config.minObstacleConfidence }
            .filter { it.category != ObstacleCategory.UNKNOWN }
            .map { instance ->
                val zone =
                    DirectionZone.fromNormalizedX(instance.boundingBox.centerX, config.zoneMode)
                val distance = depthEstimator(instance.boundingBox)

                RawObstacle(
                    boundingBox = instance.boundingBox,
                    category = instance.category,
                    zone = zone,
                    distance = distance,
                    confidence = instance.confidence,
                    areaRatio = instance.boundingBox.area
                )
            }
            .sortedByDescending { it.areaRatio }
            .take(config.maxObstacles)
    }

    /**
     * 推断障碍物类别
     * 使用注入的 classMapper 将 classId 转换为 ObstacleCategory
     */
    private fun inferCategory(
        component: ConnectedComponent,
        segmentation: SegmentationMask,
    ): ObstacleCategory {
        val categoryCounts = mutableMapOf<ObstacleCategory, Int>()

        for ((x, y) in component.pixels) {
            val classId = segmentation.getClassId(x, y)
            val category = classMapper.toObstacleCategory(classId)
            if (category != ObstacleCategory.UNKNOWN) {
                categoryCounts[category] = categoryCounts.getOrDefault(category, 0) + 1
            }
        }

        return categoryCounts.maxByOrNull { it.value }?.key ?: ObstacleCategory.STATIC_OBSTACLE
    }

    private fun findConnectedComponents(mask: BinaryMask): List<ConnectedComponent> {
        val visited = BinaryMask(mask.width, mask.height)
        val components = mutableListOf<ConnectedComponent>()

        for (y in 0 until mask.height) {
            for (x in 0 until mask.width) {
                if (mask.get(x, y) && !visited.get(x, y)) {
                    val component = bfs(mask, visited, x, y)
                    components.add(component)
                }
            }
        }

        return components
    }

    private fun bfs(
        mask: BinaryMask,
        visited: BinaryMask,
        startX: Int,
        startY: Int,
    ): ConnectedComponent {
        val pixels = mutableListOf<Pair<Int, Int>>()
        val queue = ArrayDeque<Pair<Int, Int>>()
        queue.addLast(Pair(startX, startY))
        visited.set(startX, startY, true)

        var minX = startX
        var maxX = startX
        var minY = startY
        var maxY = startY

        val dx = intArrayOf(0, 1, 0, -1)
        val dy = intArrayOf(-1, 0, 1, 0)

        while (queue.isNotEmpty()) {
            val (x, y) = queue.removeFirst()
            pixels.add(Pair(x, y))

            minX = minOf(minX, x)
            maxX = maxOf(maxX, x)
            minY = minOf(minY, y)
            maxY = maxOf(maxY, y)

            for (i in 0..3) {
                val nx = x + dx[i]
                val ny = y + dy[i]

                if (nx >= 0 && nx < mask.width && ny >= 0 && ny < mask.height &&
                    mask.get(nx, ny) && !visited.get(nx, ny)
                ) {
                    visited.set(nx, ny, true)
                    queue.addLast(Pair(nx, ny))
                }
            }
        }

        return ConnectedComponent(
            pixels = pixels,
            minX = minX,
            minY = minY,
            maxX = maxX,
            maxY = maxY
        )
    }
}

/**
 * 连通区域
 */
data class ConnectedComponent(
    val pixels: List<Pair<Int, Int>>,
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int,
) {
    val width: Int get() = maxX - minX + 1
    val height: Int get() = maxY - minY + 1
}
