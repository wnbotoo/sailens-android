package com.sailens.guidance.processor.perception

import com.sailens.guidance.config.AnalysisConfig
import com.sailens.core.mask.BinaryMask
import com.sailens.core.mask.BottomStats
import com.sailens.guidance.model.common.GroundType
import com.sailens.guidance.semantics.NavigationSemantics
import com.sailens.guidance.model.perception.SegmentationAnalysisStats
import com.sailens.guidance.model.perception.SegmentationMask

interface SegmentationStatsExtractor {
    fun extract(segmentation: SegmentationMask): SegmentationAnalysisStats
}

/**
 * 语义分割单帧统计的纯 JVM 实现,是降级/fallback 路径。
 *
 * 仅当上游未提供 native 统计时才被调用:[SegmentationAnalyzer.analyze] 的逻辑是
 * `stats ?: statsExtractor.extract(...)`,而 `stats` 来自 `SegmentationOutput.analysisStats`
 * (即 native 后处理算出的 `nativePostprocessResult?.stats`)。native 后处理生效时整段跳过本类。
 * 因此这里每帧新建 2 个 [BinaryMask] + `IntArray` + `mutableMapOf` **属可接受**,不在 native
 * 后处理的热路径上——如要按 [ObstacleExtractor] 那样做缓冲复用,先确认 native stats 确实为 null。
 */
class KotlinSegmentationStatsExtractor(
    private val config: AnalysisConfig,
    private val navigationSemantics: NavigationSemantics,
) : SegmentationStatsExtractor {
    override fun extract(segmentation: SegmentationMask): SegmentationAnalysisStats {
        val width = segmentation.width
        val height = segmentation.height
        val totalPixels = width * height
        require(segmentation.classMap.size == totalPixels) {
            "Segmentation classMap size ${segmentation.classMap.size} does not match ${width}x$height"
        }

        val passableMask = BinaryMask(width, height)
        val obstacleMask = BinaryMask(width, height)

        var roadPixelCount = 0
        var passablePixelCount = 0
        var obstaclePixelCount = 0
        var hasTrafficLight = false
        val classCounts = IntArray(navigationSemantics.classCount)

        val bottomStartY = ((1 - config.segmentationBottomRatio) * height).toInt()
        val navigationStartY = ((1 - config.segmentationNavigationRegionRatio) * height).toInt()
        val centerStartX = ((1 - config.segmentationCenterRatio) / 2 * width).toInt()
        val centerEndX = ((1 + config.segmentationCenterRatio) / 2 * width).toInt()

        val groundTypeCounts = mutableMapOf<GroundType, Int>()
        var bottomCenterRoadPixels = 0
        var bottomCenterTotalPixels = 0
        var navigationPassablePixelCount = 0
        var navigationTotalPixels = 0

        var bottomTruePixels = 0
        var maxRunWidth = 0
        var maxRunRow = bottomStartY
        var maxRunStart = 0
        var maxRunEnd = 0

        for (y in 0 until height) {
            var currentRunStart = -1

            for (x in 0 until width) {
                val classId = segmentation.classMap[y * width + x]
                if (classId in 0 until classCounts.size) {
                    classCounts[classId]++
                }

                val isPassable = navigationSemantics.isPassable(classId)
                val isObstacle = navigationSemantics.isObstacle(classId)
                val isRoad = navigationSemantics.isRoad(classId)
                val isTrafficLight = navigationSemantics.isTrafficLight(classId)

                if (isPassable) {
                    passableMask.set(x, y, true)
                    passablePixelCount++
                }
                if (isObstacle) {
                    obstacleMask.set(x, y, true)
                    obstaclePixelCount++
                }

                if (isRoad) {
                    roadPixelCount++
                }
                if (isTrafficLight) {
                    hasTrafficLight = true
                }
                if (y >= navigationStartY) {
                    navigationTotalPixels++
                    if (isPassable) {
                        navigationPassablePixelCount++
                    }
                }

                if (y >= bottomStartY) {
                    if (isPassable) {
                        bottomTruePixels++
                    }

                    if (x in centerStartX until centerEndX) {
                        bottomCenterTotalPixels++
                        val groundType = navigationSemantics.toGroundType(classId)
                        if (groundType != GroundType.UNKNOWN) {
                            groundTypeCounts[groundType] =
                                groundTypeCounts.getOrDefault(groundType, 0) + 1
                        }
                        if (isRoad) {
                            bottomCenterRoadPixels++
                        }
                    }

                    if (isPassable && currentRunStart == -1) {
                        currentRunStart = x
                    } else if (!isPassable && currentRunStart != -1) {
                        val runWidth = x - currentRunStart
                        if (runWidth > maxRunWidth) {
                            maxRunWidth = runWidth
                            maxRunRow = y
                            maxRunStart = currentRunStart
                            maxRunEnd = x - 1
                        }
                        currentRunStart = -1
                    }
                }
            }

            if (y >= bottomStartY && currentRunStart != -1) {
                val runWidth = width - currentRunStart
                if (runWidth > maxRunWidth) {
                    maxRunWidth = runWidth
                    maxRunRow = y
                    maxRunStart = currentRunStart
                    maxRunEnd = width - 1
                }
            }
        }

        val bottomCenterGroundDistribution = if (bottomCenterTotalPixels > 0) {
            groundTypeCounts.mapValues { it.value.toFloat() / bottomCenterTotalPixels }
        } else {
            emptyMap()
        }

        return SegmentationAnalysisStats(
            passableMask = passableMask,
            obstacleMask = obstacleMask,
            roadRatio = if (totalPixels > 0) roadPixelCount.toFloat() / totalPixels else 0f,
            hasTrafficLight = hasTrafficLight,
            bottomCenterGroundDistribution = bottomCenterGroundDistribution,
            bottomCenterRoadRatio = if (bottomCenterTotalPixels > 0) {
                bottomCenterRoadPixels.toFloat() / bottomCenterTotalPixels
            } else {
                0f
            },
            bottomStats = buildBottomStats(
                width = width,
                height = height,
                bottomStartY = bottomStartY,
                bottomTruePixels = bottomTruePixels,
                maxRunWidth = maxRunWidth,
                maxRunRow = maxRunRow,
                maxRunStart = maxRunStart,
                maxRunEnd = maxRunEnd,
            ),
            passablePixelCount = passablePixelCount,
            navigationPassableRatio = if (navigationTotalPixels > 0) {
                navigationPassablePixelCount.toFloat() / navigationTotalPixels
            } else {
                0f
            },
            obstaclePixelCount = obstaclePixelCount,
            classCounts = classCounts,
        )
    }

    private fun buildBottomStats(
        width: Int,
        height: Int,
        bottomStartY: Int,
        bottomTruePixels: Int,
        maxRunWidth: Int,
        maxRunRow: Int,
        maxRunStart: Int,
        maxRunEnd: Int,
    ): BottomStats {
        val totalBottomPixels = (height - bottomStartY) * width
        return BottomStats(
            coverage = if (totalBottomPixels > 0) bottomTruePixels.toFloat() / totalBottomPixels else 0f,
            maxRunWidth = maxRunWidth,
            maxRunWidthRatio = maxRunWidth.toFloat() / width,
            maxRunRow = maxRunRow,
            maxRunStart = maxRunStart,
            maxRunEnd = maxRunEnd,
            maxRunCenter = if (maxRunWidth > 0) (maxRunStart + maxRunEnd) / 2f / width else 0.5f,
        )
    }
}
