package com.sailens.guidance.processor.analysis

import com.sailens.guidance.model.analysis.FrameQuality
import com.sailens.core.frame.ImageFrame
import com.sailens.core.frame.ImagePixelFormat
import com.sailens.guidance.util.EnumStabilizer
import kotlin.math.sqrt

/**
 * 输入帧质量检测：判断"本应用现在还看不看得见"。
 *
 * 这是整条链路里唯一一个**用户无法自行察觉**的失效模式。明眼人瞥一眼屏幕就知道镜头被手指
 * 挡住了；盲人不能，他会继续举着一个已经瞎掉的手机往前走，而下游所有分析仍在正常输出
 * "前方无障碍"。所以这里宁可误报，也不能漏报。
 *
 * 判据只用亮度平面的两个统计量：
 * - **标准差极低 → 遮挡**。手指、口袋、镜头盖都会让画面失去一切纹理。真实的夜路再暗也有
 *   路灯、反光和边缘，标准差不会塌到个位数。所以低标准差比低亮度更能区分"被挡住"和"天黑"。
 * - **均值偏低（但仍有纹理）→ 过暗**。此时检测结果不可靠，但用户挪一下手机没用，
 *   要提示的是环境本身。
 *
 * 判定顺序上遮挡优先于过暗：遮挡是用户能立刻纠正的（把手指移开），过暗不是。
 *
 * ## 阈值尚未实测校准
 *
 * [obstructedMaxStdDev] 与 [darkMaxMeanLuma] 目前**只有合成帧的单测支撑**，没有用真实相机数据
 * 标定过。已知的几类边界情况还没验：夜间路灯下的真实路面、贴近白墙、镜头朝地、以及自动曝光
 * 在明暗切换时的过渡帧。上线前应当采集这几类样本，用 trace 里的 `event_camera_blocked` /
 * `event_low_light` 占比反查误报率——两个参数留成构造参数就是为了方便按实测调整。
 */
class FrameQualityAnalyzer(
    private val obstructedMaxStdDev: Float = 6.0f,
    private val darkMaxMeanLuma: Float = 35.0f,
    private val gridSteps: Int = 24,
    debounceFrames: Int = 6,
) {
    private val stabilizer = EnumStabilizer(
        requiredFrames = debounceFrames,
        defaultValue = FrameQuality.OK,
    )

    /**
     * 当帧的测量值：亮度均值、亮度标准差和清晰度。帧无法采样时为 null（此时判定为 OK）。
     *
     * 测量与判定分开，是为了 M0b 模型回归记录：记录测量值，回放时从这里继续，阈值改了也能在录像
     * 上重新判定。
     */
    fun measure(frame: ImageFrame): FrameQualityMeasurement? = sampleLuma(frame)

    /** 由测量值得到的未去抖判定。 */
    fun classify(measurement: FrameQualityMeasurement?): FrameQuality {
        val stats = measurement ?: return FrameQuality.OK
        return when {
            stats.stdLuma < obstructedMaxStdDev -> FrameQuality.OBSTRUCTED
            stats.meanLuma < darkMaxMeanLuma -> FrameQuality.TOO_DARK
            else -> FrameQuality.OK
        }
    }

    /** 未去抖的当帧判定，仅用于诊断与测试。 */
    fun classify(frame: ImageFrame): FrameQuality = classify(measure(frame))

    /**
     * 去抖后的判定。相机自动曝光在明暗切换时会短暂产生低对比帧，直接用当帧结果会让提示闪烁，
     * 所以要求连续多帧一致才改变输出。
     */
    fun analyze(frame: ImageFrame): FrameQuality = analyzeDetailed(frame).stabilized

    /** 与 [analyze] 相同，同时给出测量值和未去抖判定（供 M0b 记录）。 */
    fun analyzeDetailed(frame: ImageFrame): FrameQualityResult = analyzeMeasured(measure(frame))

    /** 从已有的测量值继续：判定再去抖。回放用；与 [analyze] 走同一条路。 */
    fun analyzeMeasured(measurement: FrameQualityMeasurement?): FrameQualityResult {
        val classified = classify(measurement)
        return FrameQualityResult(measurement, classified, stabilizer.update(classified))
    }

    fun reset() = stabilizer.reset()

    private fun sampleLuma(frame: ImageFrame): FrameQualityMeasurement? {
        if (frame.width <= 0 || frame.height <= 0) return null

        var count = 0
        var sum = 0.0
        var sumSquares = 0.0
        var laplacianCount = 0
        var laplacianSum = 0.0

        val stepX = (frame.width / gridSteps).coerceAtLeast(1)
        val stepY = (frame.height / gridSteps).coerceAtLeast(1)

        var y = 0
        while (y < frame.height) {
            var x = 0
            while (x < frame.width) {
                val luma = lumaAt(frame, x, y)
                if (luma >= 0) {
                    sum += luma
                    sumSquares += luma.toDouble() * luma
                    count++
                    // 清晰度：采样点处 4 邻域拉普拉斯的绝对值。镜头被完全遮住时画面彻底失焦，
                    // 这个值会掉一个数量级以上；只记录，不参与判定（阈值待在录像上校准）。
                    if (x > 0 && y > 0 && x < frame.width - 1 && y < frame.height - 1) {
                        val left = lumaAt(frame, x - 1, y)
                        val right = lumaAt(frame, x + 1, y)
                        val up = lumaAt(frame, x, y - 1)
                        val down = lumaAt(frame, x, y + 1)
                        if (left >= 0 && right >= 0 && up >= 0 && down >= 0) {
                            laplacianSum += kotlin.math.abs(4 * luma - left - right - up - down)
                            laplacianCount++
                        }
                    }
                }
                x += stepX
            }
            y += stepY
        }

        if (count == 0) return null
        val mean = sum / count
        // 方差可能因浮点误差取到极小的负数，钳到 0 再开方。
        val variance = (sumSquares / count - mean * mean).coerceAtLeast(0.0)
        return FrameQualityMeasurement(
            meanLuma = mean.toFloat(),
            stdLuma = sqrt(variance).toFloat(),
            sharpness = if (laplacianCount > 0) (laplacianSum / laplacianCount).toFloat() else 0f,
        )
    }

    /** 取 (x, y) 处的亮度；越界或格式不支持时返回 -1。 */
    private fun lumaAt(frame: ImageFrame, x: Int, y: Int): Int {
        frame.yuvData?.let { yuv ->
            val plane = yuv.y
            val index = y * plane.rowStride + x * plane.pixelStride
            if (index < 0 || index >= plane.bytes.size) return -1
            return plane.bytes[index].toInt() and 0xFF
        }

        if (frame.pixelFormat != ImagePixelFormat.RGBA_8888) return -1
        val bytesPerPixel = ImagePixelFormat.RGBA_8888.bytesPerPixel
        val index = (y * frame.width + x) * bytesPerPixel
        if (index < 0 || index + 2 >= frame.pixelBytes.size) return -1
        val r = frame.pixelBytes[index].toInt() and 0xFF
        val g = frame.pixelBytes[index + 1].toInt() and 0xFF
        val b = frame.pixelBytes[index + 2].toInt() and 0xFF
        // BT.601 亮度近似，整数权重求和后右移 8 位。
        return (r * 77 + g * 150 + b * 29) shr 8
    }
}

/**
 * 一帧的质量测量值。[sharpness] 是采样点处 4 邻域拉普拉斯绝对值的均值（亮度单位），只用于记录和
 * 校准，暂不参与判定。
 */
data class FrameQualityMeasurement(
    val meanLuma: Float,
    val stdLuma: Float,
    val sharpness: Float,
)

/** 一次 [FrameQualityAnalyzer.analyzeDetailed] 的完整结果。 */
data class FrameQualityResult(
    val measurement: FrameQualityMeasurement?,
    /** 未去抖的当帧判定。 */
    val classified: FrameQuality,
    /** 去抖后的判定，即 [FrameQualityAnalyzer.analyze] 的返回值。 */
    val stabilized: FrameQuality,
)
