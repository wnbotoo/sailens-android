package com.friady.sailens.domain.processor.perception

import com.friady.sailens.domain.config.AnalysisConfig
import com.friady.sailens.domain.model.common.BinaryMask
import com.friady.sailens.domain.model.common.BottomStats
import com.friady.sailens.domain.model.common.GroundType
import com.friady.sailens.domain.model.perception.ClassMapper
import com.friady.sailens.domain.model.perception.SegmentationAnalysisStats
import com.friady.sailens.domain.model.perception.SegmentationMask

interface SegmentationStatsExtractor {
    fun extract(segmentation: SegmentationMask): SegmentationAnalysisStats
}

class KotlinSegmentationStatsExtractor(
    private val config: AnalysisConfig,
    private val classMapper: ClassMapper,
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
        val classCounts = IntArray(classMapper.classCount)

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

                val isPassable = classMapper.isPassable(classId)
                val isObstacle = classMapper.isObstacle(classId)
                val isRoad = classMapper.isRoad(classId)
                val isTrafficLight = classMapper.isTrafficLight(classId)

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
                        val groundType = classMapper.toGroundType(classId)
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
