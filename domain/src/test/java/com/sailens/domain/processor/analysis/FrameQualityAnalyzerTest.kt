package com.sailens.domain.processor.analysis

import com.sailens.domain.model.analysis.FrameQuality
import com.sailens.domain.model.perception.ImageFrame
import com.sailens.domain.model.perception.ImagePixelFormat
import com.sailens.domain.model.perception.Yuv420FrameData
import com.sailens.domain.model.perception.YuvPlaneData
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class FrameQualityAnalyzerTest {

    @Test
    fun `textured daylight frame is usable`() {
        val analyzer = FrameQualityAnalyzer()

        assertEquals(FrameQuality.OK, analyzer.classify(texturedFrame(baseLuma = 130)))
    }

    @Test
    fun `flat dark frame reads as an obstructed lens`() {
        val analyzer = FrameQualityAnalyzer()

        // 手指/口袋盖住镜头：整幅画面没有任何纹理。
        assertEquals(FrameQuality.OBSTRUCTED, analyzer.classify(flatFrame(luma = 4)))
    }

    @Test
    fun `flat bright frame also reads as obstructed`() {
        val analyzer = FrameQualityAnalyzer()

        // 关键区分点：遮挡看的是"没纹理"而不是"暗"。贴在白墙上或亮处被指腹糊住时画面很亮，
        // 但同样什么都检测不到，只看亮度会漏掉这一类。
        assertEquals(FrameQuality.OBSTRUCTED, analyzer.classify(flatFrame(luma = 210)))
    }

    @Test
    fun `dim but textured frame reads as low light rather than obstruction`() {
        val analyzer = FrameQualityAnalyzer()

        // 真实夜路：整体偏暗，但路灯、边缘、反光让纹理仍在。用户挪手机解决不了，
        // 要提示的是环境，不是遮挡。
        assertEquals(FrameQuality.TOO_DARK, analyzer.classify(texturedFrame(baseLuma = 18)))
    }

    @Test
    fun `verdict only changes after the debounce window`() {
        val analyzer = FrameQualityAnalyzer(debounceFrames = 3)
        val covered = flatFrame(luma = 3)

        // 相机自动曝光在明暗切换时会短暂产生低对比帧，单帧就报会让提示闪烁。
        assertEquals(FrameQuality.OK, analyzer.analyze(covered))
        assertEquals(FrameQuality.OK, analyzer.analyze(covered))
        assertEquals(FrameQuality.OBSTRUCTED, analyzer.analyze(covered))
    }

    @Test
    fun `reset clears a latched verdict`() {
        val analyzer = FrameQualityAnalyzer(debounceFrames = 1)
        analyzer.analyze(flatFrame(luma = 3))

        analyzer.reset()

        assertEquals(FrameQuality.OK, analyzer.analyze(texturedFrame(baseLuma = 130)))
    }

    /** 纹理丰富的画面：亮度围绕 [baseLuma] 有明显起伏。 */
    private fun texturedFrame(baseLuma: Int): ImageFrame {
        val random = Random(seed = 42)
        return yuvFrame { _, _ ->
            (baseLuma + random.nextInt(-15, 16)).coerceIn(0, 255)
        }
    }

    /** 完全均匀的画面：没有任何纹理。 */
    private fun flatFrame(luma: Int): ImageFrame = yuvFrame { _, _ -> luma }

    private fun yuvFrame(lumaAt: (x: Int, y: Int) -> Int): ImageFrame {
        val width = 96
        val height = 96
        val plane = ByteArray(width * height) { index ->
            lumaAt(index % width, index / width).toByte()
        }
        val chroma = YuvPlaneData(bytes = ByteArray(width * height / 2), rowStride = width, pixelStride = 2)
        return ImageFrame(
            width = width,
            height = height,
            pixelBytes = ByteArray(0),
            pixelFormat = ImagePixelFormat.YUV_420_888,
            timestamp = 0L,
            rotationDegrees = 0,
            sequenceNumber = 0L,
            yuvData = Yuv420FrameData(
                y = YuvPlaneData(bytes = plane, rowStride = width, pixelStride = 1),
                u = chroma,
                v = chroma,
            ),
        )
    }
}
