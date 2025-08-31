package com.friady.sailens.presentation.ext

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import com.friady.sailens.domain.model.common.BinaryMask
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import com.friady.sailens.domain.model.perception.ImageFrame
import java.io.ByteArrayOutputStream

fun BinaryMask.visualize(
    color: Int = Color.argb((0.7f * 255).toInt(), 0, 255, 0), // 绿色 + 70% 不透明度
): Bitmap {
    val bitmap = createBitmap(width, height)

    for (y in 0 until height) {
        for (x in 0 until width) {
            if (get(x, y)) {
                bitmap[x, y] = color
            } else {
                bitmap[x, y] = Color.TRANSPARENT
            }
        }
    }
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