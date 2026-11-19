package com.sailens.guidance.world

import com.sailens.core.mask.BinaryMask
import com.sailens.guidance.kernel.NativeGuidanceLibrary
import com.sailens.guidance.kernel.NativeWorldGrid
import com.sailens.guidance.model.perception.SegmentationMask
import com.sailens.runtime.InputImageMapping
import com.sailens.vision.semantic.SemanticImageMapping
import kotlin.math.ceil
import kotlin.math.floor

internal class WorldSemanticFootprint private constructor(
    private val x: Axis,
    private val y: Axis,
    private val mask: SegmentationMask,
) {
    val width: Int get() = mask.width
    val height: Int get() = mask.height

    fun writeAxes(target: DoubleArray) {
        x.writeTo(target, 0)
        y.writeTo(target, AXIS_SIZE)
    }

    fun mapArea(image: ImageFootprint): PixelArea? {
        var area: PixelArea? = null
        visitArea(image.x.low, image.x.high, image.y.low, image.y.high) { left, top, right, bottom ->
            area = PixelArea(left, top, right, bottom)
        }
        return area
    }

    inline fun visitArea(
        minX: Double, maxX: Double, minY: Double, maxY: Double, visit: (Int, Int, Int, Int) -> Unit,
    ) {
        val mappedLeft = x.mapLow(minX)
        val mappedRight = x.mapHigh(maxX)
        val mappedTop = y.mapLow(minY)
        val mappedBottom = y.mapHigh(maxY)
        if (!mappedLeft.isFinite() || !mappedRight.isFinite() || !mappedTop.isFinite() || !mappedBottom.isFinite() ||
            mappedLeft <= 0 || mappedRight >= mask.width || mappedTop <= 0 || mappedBottom >= mask.height ||
            mappedLeft > mappedRight || mappedTop > mappedBottom
        ) return
        val left = ceil(mappedLeft).toInt() - 1
        val right = floor(mappedRight).toInt() + 1
        val top = ceil(mappedTop).toInt() - 1
        val bottom = floor(mappedBottom).toInt() + 1
        if (left >= x.supportStart && right <= x.supportEnd && top >= y.supportStart && bottom <= y.supportEnd) {
            visit(left, top, right, bottom)
        }
    }

    fun isGround(area: PixelArea, ground: BooleanArray): Boolean {
        for (row in area.top until area.bottom) {
            val start = row * mask.width
            for (col in area.left until area.right) {
                val id = mask.classMap[start + col]
                if (id !in ground.indices || !ground[id]) return false
            }
        }
        return true
    }

    internal class Axis private constructor(
        val scale: Bound,
        val offset: Bound,
        val ratio: Bound,
        val crop: Bound,
        val supportStart: Double,
        val supportEnd: Double,
    ) {
        fun writeTo(target: DoubleArray, offsetAt: Int) {
            target[offsetAt] = scale.low
            target[offsetAt + 1] = scale.high
            target[offsetAt + 2] = offset.low
            target[offsetAt + 3] = ratio.low
            target[offsetAt + 4] = ratio.high
            target[offsetAt + 5] = crop.low
            target[offsetAt + 6] = supportStart
            target[offsetAt + 7] = supportEnd
        }

        // The scale and ratio are nonnegative, so only one endpoint can bound each product.
        fun mapLow(value: Double): Double {
            val scaled = Math.nextDown(value * if (value >= 0.0) scale.low else scale.high)
            val padded = Math.nextDown(scaled + offset.low)
            val scored = Math.nextDown(padded * if (padded >= 0.0) ratio.low else ratio.high)
            return Math.nextDown(scored - crop.high)
        }

        fun mapHigh(value: Double): Double {
            val scaled = Math.nextUp(value * if (value >= 0.0) scale.high else scale.low)
            val padded = Math.nextUp(scaled + offset.high)
            val scored = Math.nextUp(padded * if (padded >= 0.0) ratio.high else ratio.low)
            return Math.nextUp(scored - crop.low)
        }

        companion object {
            fun from(
                axis: InputImageMapping.Axis,
                imageSize: Int,
                tensorSize: Int,
                scoreSize: Int,
                crop: Int,
            ): Axis? {
                if (!axis.scale.isFinite() || axis.scale <= 0 || !axis.offset.isFinite() ||
                    axis.writtenStart < 0 || axis.writtenEnd > tensorSize || axis.writtenEnd <= axis.writtenStart ||
                    imageSize <= 0 || tensorSize <= 0 || scoreSize <= 0 || crop < 0
                ) return null
                val scale = Bound.enclosing(axis.scale)
                if (!scale.finite) return null
                val offset = Bound(axis.offset)
                val ratio = Bound(scoreSize.toDouble()) / Bound(tensorSize.toDouble())
                val origin = Bound(crop.toDouble())
                val start = (Bound(maxOf(axis.writtenStart.toDouble(), axis.offset)) * ratio - origin).high
                val nominalEnd = (scale * Bound(imageSize.toDouble()) + offset).low
                val end = (Bound(minOf(axis.writtenEnd.toDouble(), nominalEnd)) * ratio - origin).low
                if (!start.isFinite() || !end.isFinite() || start >= end) return null
                return Axis(scale, offset, ratio, origin, start, end)
            }
        }
    }

    companion object {
        private const val AXIS_SIZE = 8
        const val AXES_SIZE = 2 * AXIS_SIZE

        fun from(mapping: SemanticImageMapping, mask: SegmentationMask): WorldSemanticFootprint? {
            val content = mapping.content
            if (content.width != mask.width || content.height != mask.height || mask.width <= 0 || mask.height <= 0 ||
                content.x.toLong() + content.width > mapping.scoreWidth || content.y.toLong() + content.height > mapping.scoreHeight ||
                mask.width.toLong() * mask.height != mask.classMap.size.toLong()
            ) return null
            val input = mapping.input
            val x = Axis.from(input.x, input.imageWidth, input.tensorWidth, mapping.scoreWidth, content.x) ?: return null
            val y = Axis.from(input.y, input.imageHeight, input.tensorHeight, mapping.scoreHeight, content.y) ?: return null
            return WorldSemanticFootprint(x, y, mask)
        }
    }
}

/** One run borrows and reuses the table; the published frame never retains it. */
internal class WorldPassableTable(private val preferNative: Boolean = true) {
    private var sums = IntArray(0)
    private var effectiveWords = LongArray(0)
    private var stride = 0

    fun fill(effective: BinaryMask, labels: SegmentationMask, passable: BooleanArray): Boolean {
        if (effective.width != labels.width || effective.height != labels.height) return false
        val size = (effective.width.toLong() + 1) * (effective.height.toLong() + 1)
        if (size <= 0L || size > MAX_ENTRIES) return false
        stride = effective.width + 1
        if (sums.size < size) sums = IntArray(size.toInt())
        if (preferNative && NativeGuidanceLibrary.isAvailable) {
            effectiveWords = effective.copyPackedBitsInto(effectiveWords)
            if (NativeWorldGrid.fill(effective.width, effective.height, labels.classMap, effectiveWords, passable, sums)) {
                return true
            }
        }
        sums.fill(0, 0, stride)
        for (row in 0 until effective.height) {
            val labelStart = row * effective.width
            val below = (row + 1) * stride
            sums[below] = 0
            var rowSum = 0
            for (col in 0 until effective.width) {
                val id = labels.classMap[labelStart + col]
                if (id in passable.indices && passable[id] && effective.get(col, row)) rowSum++
                sums[below + col + 1] = sums[below - stride + col + 1] + rowSum
            }
        }
        return true
    }

    fun accepted(area: PixelArea): Int = accepted(area.left, area.top, area.right, area.bottom)

    fun prefixSums(): IntArray = sums

    private fun accepted(left: Int, top: Int, right: Int, bottom: Int): Int =
        sums[bottom * stride + right] - sums[top * stride + right] -
            sums[bottom * stride + left] + sums[top * stride + left]

    fun covers(left: Int, top: Int, right: Int, bottom: Int): Boolean =
        accepted(left, top, right, bottom) == (right - left) * (bottom - top)

    private companion object {
        const val MAX_ENTRIES = 4_194_304
    }
}
