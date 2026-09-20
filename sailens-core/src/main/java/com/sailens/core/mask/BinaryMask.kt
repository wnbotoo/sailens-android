package com.sailens.core.mask

import java.util.BitSet

/**
 * 二值掩码
 */
class BinaryMask private constructor(
    val width: Int,
    val height: Int,
    private val bits: BitSet,
) {
    constructor(width: Int, height: Int) : this(width, height, BitSet(width * height))

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

    fun copyPackedBits(): LongArray = bits.toLongArray()

    /**
     * 将位图打包写入调用方提供的 [reusable] 以复用缓冲、避免每帧分配。
     * 若 [reusable] 长度足够则原地写入并返回它本身，否则分配一个新数组返回。
     * 语义与 [copyPackedBits] 一致：bit i 落在第 i/64 个 word 的第 i%64 位；
     * 所需字长内没有对应 true 像素的位会被清零，调用方按帧尺寸解释返回值。
     */
    fun copyPackedBitsInto(reusable: LongArray): LongArray {
        val wordCount = (width * height + Long.SIZE_BITS - 1) / Long.SIZE_BITS
        val target = if (reusable.size >= wordCount) reusable else LongArray(wordCount)
        target.fill(0L, 0, wordCount)
        var i = bits.nextSetBit(0)
        while (i >= 0) {
            target[i ushr 6] = target[i ushr 6] or (1L shl (i and 63))
            i = bits.nextSetBit(i + 1)
        }
        return target
    }

    /**
     * 获取某行的连续 true 区间（runs）。
     *
     * 注意会分配 `List<IntRange>`。当前唯一调用方是 `KotlinConnectivityStatsExtractor`
     * (直接调用 + 经 [getBottomStats]),即连通性分析的 JVM fallback;生产的 native 连通性
     * 路径不经过这里。所以 AGENTS.md "BinaryMask 用于热循环、避免分配" 的约束主要针对 native
     * 主路径,本方法的分配局限在降级路径,故保留其可读实现。
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

    companion object {
        fun fromPackedBits(
            width: Int,
            height: Int,
            packedBits: LongArray,
        ): BinaryMask {
            return BinaryMask(width, height, BitSet.valueOf(packedBits))
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BinaryMask) return false

        return width == other.width &&
            height == other.height &&
            bits == other.bits
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + bits.hashCode()
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
