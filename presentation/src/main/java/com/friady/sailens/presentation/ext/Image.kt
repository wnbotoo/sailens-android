package com.friady.sailens.presentation.ext

import android.graphics.Bitmap
import android.graphics.Color
import com.friady.sailens.domain.model.common.BinaryMask
import com.friady.sailens.domain.model.common.ObstacleCategory
import com.friady.sailens.domain.model.perception.DetectedInstance
import com.friady.sailens.domain.model.perception.SegmentationMask
import androidx.core.graphics.createBitmap
import kotlin.math.roundToInt

private const val DEFAULT_OVERLAY_LONG_SIDE = 720

fun BinaryMask.visualize(
    color: Int = Color.argb((0.7f * 255).toInt(), 0, 255, 0), // 绿色 + 70% 不透明度
): Bitmap {
    val bitmap = createBitmap(width, height)
    val pixels = IntArray(width * height)

    for (y in 0 until height) {
        val rowOffset = y * width
        for (x in 0 until width) {
            pixels[rowOffset + x] = if (get(x, y)) color else Color.TRANSPARENT
        }
    }

    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap
}

fun BinaryMask.visualizeForAspect(
    sourceAspectRatio: Float?,
    color: Int = Color.argb((0.7f * 255).toInt(), 0, 255, 0),
    maxLongSide: Int = DEFAULT_OVERLAY_LONG_SIDE,
): Bitmap {
    val crop = AspectCrop.from(width, height, sourceAspectRatio)
    val outputSize = crop.outputSize(maxLongSide)
    val bitmap = createBitmap(outputSize.width, outputSize.height)
    val pixels = IntArray(outputSize.width * outputSize.height)

    for (y in 0 until outputSize.height) {
        val sourceY = crop.top + ((y + 0.5f) * crop.height / outputSize.height)
            .toInt()
            .coerceIn(0, height - 1)
        val rowOffset = y * outputSize.width
        for (x in 0 until outputSize.width) {
            val sourceX = crop.left + ((x + 0.5f) * crop.width / outputSize.width)
                .toInt()
                .coerceIn(0, width - 1)
            pixels[rowOffset + x] = if (get(sourceX, sourceY)) color else Color.TRANSPARENT
        }
    }

    bitmap.setPixels(pixels, 0, outputSize.width, 0, 0, outputSize.width, outputSize.height)
    return bitmap
}

fun SegmentationMask.visualizeSemanticClasses(): Bitmap {
    val bitmap = createBitmap(width, height)
    val pixels = IntArray(width * height)

    for (index in classMap.indices) {
        val classId = classMap[index]
        pixels[index] = SEMANTIC_CLASS_COLORS.getOrElse(classId) { UNKNOWN_CLASS_COLOR }
    }

    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap
}

fun SegmentationMask.visualizeSemanticClassesForAspect(
    sourceAspectRatio: Float?,
    maxLongSide: Int = DEFAULT_OVERLAY_LONG_SIDE,
): Bitmap {
    val crop = AspectCrop.from(width, height, sourceAspectRatio)
    val outputSize = crop.outputSize(maxLongSide)
    val bitmap = createBitmap(outputSize.width, outputSize.height)
    val pixels = IntArray(outputSize.width * outputSize.height)

    for (y in 0 until outputSize.height) {
        val sourceY = crop.top + ((y + 0.5f) * crop.height / outputSize.height)
            .toInt()
            .coerceIn(0, height - 1)
        val rowOffset = y * outputSize.width
        for (x in 0 until outputSize.width) {
            val sourceX = crop.left + ((x + 0.5f) * crop.width / outputSize.width)
                .toInt()
                .coerceIn(0, width - 1)
            val classId = getClassId(sourceX, sourceY)
            pixels[rowOffset + x] = SEMANTIC_CLASS_COLORS.getOrElse(classId) { UNKNOWN_CLASS_COLOR }
        }
    }

    bitmap.setPixels(pixels, 0, outputSize.width, 0, 0, outputSize.width, outputSize.height)
    return bitmap
}

fun List<DetectedInstance>.visualizeInstanceMasks(): Bitmap? {
    val firstMask = firstNotNullOfOrNull { it.mask } ?: return null
    val bitmap = createBitmap(firstMask.width, firstMask.height)
    val pixels = IntArray(firstMask.width * firstMask.height) { Color.TRANSPARENT }

    forEach { instance ->
        val mask = instance.mask ?: return@forEach
        val color = when (instance.category) {
            ObstacleCategory.PERSON -> Color.argb(150, 255, 82, 82)
            ObstacleCategory.VEHICLE -> Color.argb(150, 66, 165, 245)
            ObstacleCategory.BICYCLE -> Color.argb(150, 255, 202, 40)
            ObstacleCategory.STATIC_OBSTACLE -> Color.argb(150, 171, 71, 188)
            ObstacleCategory.UNKNOWN -> Color.argb(150, 255, 255, 255)
        }

        for (y in 0 until mask.height) {
            val rowOffset = y * mask.width
            for (x in 0 until mask.width) {
                if (mask.get(x, y)) {
                    pixels[rowOffset + x] = color
                }
            }
        }
    }

    bitmap.setPixels(pixels, 0, firstMask.width, 0, 0, firstMask.width, firstMask.height)
    return bitmap
}

private val SEMANTIC_CLASS_COLORS = intArrayOf(
    Color.argb(170, 128, 64, 128),  // road
    Color.argb(170, 244, 35, 232),  // sidewalk
    Color.argb(170, 70, 70, 70),    // building
    Color.argb(170, 102, 102, 156), // wall
    Color.argb(170, 190, 153, 153), // fence
    Color.argb(170, 153, 153, 153), // pole
    Color.argb(170, 250, 170, 30),  // traffic light
    Color.argb(170, 220, 220, 0),   // traffic sign
    Color.argb(170, 107, 142, 35),  // vegetation
    Color.argb(170, 152, 251, 152), // terrain
    Color.argb(170, 70, 130, 180),  // sky
    Color.argb(170, 220, 20, 60),   // person
    Color.argb(170, 255, 0, 0),     // rider
    Color.argb(170, 0, 0, 142),     // car
    Color.argb(170, 0, 0, 70),      // truck
    Color.argb(170, 0, 60, 100),    // bus
    Color.argb(170, 0, 80, 100),    // train
    Color.argb(170, 0, 0, 230),     // motorcycle
    Color.argb(170, 119, 11, 32),   // bicycle
)

private val UNKNOWN_CLASS_COLOR = Color.argb(170, 255, 255, 255)

private data class AspectCrop(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
) {
    fun outputSize(maxLongSide: Int): OutputSize {
        if (width <= 0 || height <= 0) return OutputSize(1, 1)
        val scale = if (width >= height) {
            maxLongSide.toFloat() / width
        } else {
            maxLongSide.toFloat() / height
        }.coerceAtMost(1f)

        return OutputSize(
            width = (width * scale).roundToInt().coerceAtLeast(1),
            height = (height * scale).roundToInt().coerceAtLeast(1),
        )
    }

    companion object {
        fun from(
            width: Int,
            height: Int,
            sourceAspectRatio: Float?,
        ): AspectCrop {
            val targetAspectRatio = sourceAspectRatio
                ?.takeIf { it > 0f }
                ?: return AspectCrop(left = 0, top = 0, width = width, height = height)
            val maskAspectRatio = width.toFloat() / height

            return if (maskAspectRatio > targetAspectRatio) {
                val cropWidth = (height * targetAspectRatio).roundToInt().coerceIn(1, width)
                AspectCrop(
                    left = (width - cropWidth) / 2,
                    top = 0,
                    width = cropWidth,
                    height = height,
                )
            } else {
                val cropHeight = (width / targetAspectRatio).roundToInt().coerceIn(1, height)
                AspectCrop(
                    left = 0,
                    top = (height - cropHeight) / 2,
                    width = width,
                    height = cropHeight,
                )
            }
        }
    }
}

private data class OutputSize(
    val width: Int,
    val height: Int,
)
