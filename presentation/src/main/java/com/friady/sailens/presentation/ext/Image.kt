package com.friady.sailens.presentation.ext

import android.graphics.Bitmap
import android.graphics.Color
import com.friady.sailens.domain.model.common.BinaryMask
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

//fun ImageFrame.toBitmap(): Bitmap {
//    // 用 NV21 数据创建 YuvImage
//    val yuvImage = YuvImage(nv21Pixels, ImageFormat.NV21, width, height, null)
//
//    // 输出流接收 JPEG 数据
//    val out = ByteArrayOutputStream()
//    yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
//
//    val jpegBytes = out.toByteArray()
//    return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
//
//}