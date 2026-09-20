package com.sailens.domain.processor.analysis

import com.sailens.domain.config.PerceptionConfig
import com.sailens.core.mask.BinaryMask
import com.sailens.core.geometry.NormalizedRect
import com.sailens.domain.model.perception.DetectedObstacle

/**
 * 障碍物遮挡分析器：det 与 sem 的交叉验证。
 *
 * 把跟踪障碍物 bbox 的"接地带"（框底部一段，见 [PerceptionConfig.occlusionBandHeightRatio]）
 * 从语义可行走 mask 中抠掉，得到"扣除障碍物后真正可行走"的 effective mask，再交给连通性
 * 分析。价值场景是 sem 漏检/误判而 det 抓到的障碍：行人等离散目标 sem 可能把其脚下判成
 * 可行走，留下一条穿过障碍物的假通路；抠除接地带修正这种 false clear，障碍物挡路会自然
 * 体现为 blockage 上升。sem 已正确分割的障碍其像素本就不可行走，抠除是幂等的。
 *
 * 设计上单向保守：只清除可行走像素、永不新增，所以只可能让警觉升高、不会降低安全性。
 * 误报控制依赖三层门控——输入是 tracker 输出的稳定轨迹（trackerMinStableFrames 已过滤，
 * det 间隔帧由预测补偿，信号逐帧连续），仅对足够近的障碍生效
 * （bbox 底边 ≥ [PerceptionConfig.occlusionMinBottomY]），并且要求 bbox 与导航走廊有
 * 明确重叠。只取接地带而非整框，避免高瘦目标（行人上身）过度清除其身后的远处可行走区。
 *
 * 坐标约定与既有代码一致：bbox 与可行走 mask 都按帧归一化 [0,1] 对齐。
 */
class ObstacleOcclusionAnalyzer(
    private val config: PerceptionConfig,
) {

    data class Result(
        val effectivePassableMask: BinaryMask,
        val occludedPassableRatio: Float,
    )

    fun analyze(
        passableMask: BinaryMask,
        obstacles: List<DetectedObstacle>,
    ): Result {
        val originalPassable = passableMask.countTrue()
        if (obstacles.isEmpty() || originalPassable == 0) {
            return Result(passableMask, 0f)
        }

        val width = passableMask.width
        val height = passableMask.height
        var effective: BinaryMask? = null
        var clearedCount = 0

        for (obstacle in obstacles) {
            val box = obstacle.boundingBox
            if (!shouldCarveObstacle(box)) continue

            val bandTop = (box.maxY - box.height * config.occlusionBandHeightRatio)
                .coerceAtLeast(box.y)
            val startX = (box.x * width).toInt().coerceIn(0, width - 1)
            val endX = (box.maxX * width).toInt().coerceIn(0, width - 1)
            val startY = (bandTop * height).toInt().coerceIn(0, height - 1)
            val endY = (box.maxY * height).toInt().coerceIn(0, height - 1)

            for (y in startY..endY) {
                for (x in startX..endX) {
                    val target = effective ?: passableMask
                    if (!target.get(x, y)) continue
                    val mask = effective ?: BinaryMask.fromPackedBits(
                        width = width,
                        height = height,
                        packedBits = passableMask.copyPackedBits(),
                    ).also { effective = it }
                    mask.set(x, y, false)
                    clearedCount++
                }
            }
        }

        val result = effective
        if (result == null || clearedCount == 0) {
            return Result(passableMask, 0f)
        }

        return Result(
            effectivePassableMask = result,
            occludedPassableRatio = clearedCount.toFloat() / originalPassable,
        )
    }

    private fun shouldCarveObstacle(box: NormalizedRect): Boolean {
        // 近处门控：底边未到达画面下部的障碍不参与抠除（远处目标不挡脚下的路）。
        if (box.maxY < config.occlusionMinBottomY) return false

        // 走廊门控：近处但在画面侧边的目标可以保留障碍物提示，但不应降低路径连通性。
        return corridorOverlapRatio(box) >= config.semanticObstacleMinCorridorOverlapRatio
    }

    private fun corridorOverlapRatio(box: NormalizedRect): Float {
        val halfWidth = corridorWidthAt(box.maxY) / 2f
        val corridorStart = 0.5f - halfWidth
        val corridorEnd = 0.5f + halfWidth
        val overlap = maxOf(0f, minOf(box.maxX, corridorEnd) - maxOf(box.x, corridorStart))
        return overlap / box.width.coerceAtLeast(0.0001f)
    }

    private fun corridorWidthAt(bottomY: Float): Float {
        val nearWidth = config.navigationCorridorCenterWidth.coerceIn(0.1f, 1.0f)
        val farWidth = config.navigationCorridorFarWidth.coerceIn(0.05f, nearWidth)
        val horizonY = config.navigationCorridorHorizonY.coerceIn(0f, 0.95f)
        val depthProgress = ((bottomY - horizonY) / (1f - horizonY)).coerceIn(0f, 1f)
        return farWidth + (nearWidth - farWidth) * depthProgress
    }
}
