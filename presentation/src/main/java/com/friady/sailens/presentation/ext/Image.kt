package com.friady.sailens.presentation.ext

import android.graphics.Bitmap
import android.graphics.Color
import com.friady.sailens.domain.model.common.BinaryMask
import com.friady.sailens.domain.model.perception.SegmentationMask
import androidx.core.graphics.createBitmap

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
