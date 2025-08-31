package com.friady.sailens.domain.model.common

import java.util.BitSet

/**
 * 二值掩码
 */
class BinaryMask(
    val width: Int,
    val height: Int,
) {
    private val bits = BitSet(width * height)

    fun get(x: Int, y: Int): Boolean {
        if (x !in 0..<width || y < 0 || y >= height) return false
        return bits.get(y * width + x)
    }

    fun set(x: Int, y: Int, value: Boolean) {
        if (x !in 0..<width || y < 0 || y >= height) return
        bits.set(y * width + x, value)
    }

    fun clear() {
        bits.clear()
    }

    fun countTrue(): Int = bits.cardinality()

    fun coverage(): Float = countTrue().toFloat() / (width * height)

    /**
     * 获取某行的连续 true 区间（runs）
     */
    fun getRowRuns(row: Int): List<IntRange> {
        if (row !in 0..<height) return emptyList()

        val runs = mutableListOf<IntRange>()
        var runStart = -1

        for (x in 0 until width) {
            val value = get(x, row)
            if (value && runStart == -1) {
                runStart = x
            } else if (!value && runStart != -1) {
                runs.add(runStart until x)
                runStart = -1
            }
        }

        if (runStart != -1) {
            runs.add(runStart until width)
        }

        return runs
    }

    /**
     * 获取底部区域统计
     */
    fun getBottomStats(bottomRatio: Float = 0.2f): BottomStats {
        val startRow = ((1 - bottomRatio) * height).toInt()

        var maxRunWidth = 0
        var maxRunRow = startRow
        var maxRunStart = 0
        var maxRunEnd = 0
        var totalTruePixels = 0

        for (row in startRow until height) {
            val runs = getRowRuns(row)
            for (run in runs) {
                val runWidth = run.last - run.first + 1
                totalTruePixels += runWidth
                if (runWidth > maxRunWidth) {
                    maxRunWidth = runWidth
                    maxRunRow = row
                    maxRunStart = run.first
                    maxRunEnd = run.last
                }
            }
        }

        val totalBottomPixels = (height - startRow) * width

        return BottomStats(
            coverage = if (totalBottomPixels > 0) totalTruePixels.toFloat() / totalBottomPixels else 0f,
            maxRunWidth = maxRunWidth,
            maxRunWidthRatio = maxRunWidth.toFloat() / width,
            maxRunRow = maxRunRow,
            maxRunStart = maxRunStart,
            maxRunEnd = maxRunEnd,
            maxRunCenter = if (maxRunWidth > 0) (maxRunStart + maxRunEnd) / 2f / width else 0.5f
        )
    }

    /**
     * 降采样
     */
    fun downsample(factor: Int): BinaryMask {
        val newWidth = width / factor
        val newHeight = height / factor
        val result = BinaryMask(newWidth, newHeight)

        for (ny in 0 until newHeight) {
            for (nx in 0 until newWidth) {
                var count = 0
                for (dy in 0 until factor) {
                    for (dx in 0 until factor) {
                        if (get(nx * factor + dx, ny * factor + dy)) {
                            count++
                        }
                    }
                }
                result.set(nx, ny, count > factor * factor / 2)
            }
        }

        return result
    }
}

/**
 * 底部区域统计
 */
data class BottomStats(
    val coverage: Float,
    val maxRunWidth: Int,
    val maxRunWidthRatio: Float,
    val maxRunRow: Int,
    val maxRunStart: Int,
    val maxRunEnd: Int,
    val maxRunCenter: Float,
)