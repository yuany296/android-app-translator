package com.manga.translator.overlay

import kotlin.math.min
import kotlin.math.sqrt

/**
 * 「这块文字的底是不是一条**平底色**」的判据（2026-10-13）。
 *
 * 背景（用户反馈 + 真机日志）：`bubble_region_stats detected=0` 的页面上，所有气泡都
 * 走「无区域」分支 → `bg_type=none` → 盖板从**清理图**（inpaint 产物）裁切。而这份
 * 页面里明明是纯白实底气泡（用户原话：「这个截页处的气泡框是纯色背景，但是它用的是
 * 清理图。希望处理为 solid」）——inpaint 在白底上会留下擦不干净的灰痕，于是气泡边上
 * 出现肉眼可见的污迹。
 *
 * 对齐参考项目 `C:\homework\translator` 的 `bubble-colors.js`
 * （`applySampledBubbleColors` 里的 `adaptiveBackground` 升级）：
 * **条件是量出来的** —— 文字框外圈采样的一致率够高、且底色够亮，才允许纯色平涂；
 * 环不够均匀（网点/渐变/插画）就什么都不做 —— 平涂一块画面会把插画糊成色块，
 * 这条底线不能破（2026-09-30 曾因为「检测不到可信容器还平涂」把斜排文字旁边画出色板）。
 */
internal object BubbleBackgroundPolicy {
    /**
     * 「外圈是不是一条均匀的平底色」的容忍距离（RGB 欧氏）。
     * 取参考项目同名值 `BG_RING_UNIFORM_DISTANCE = 24`（与本地服务
     * `SOLID_BACKGROUND_MAX_DELTA_E_P90 = 20`（Lab）同一量级的保守取值）。
     */
    const val RING_UNIFORM_DISTANCE = 24f

    /**
     * 判成实底需要的环内一致率。0.92 = 允许外圈有 8% 的像素是字体抗锯齿/压缩噪声
     * ——再多就说明这一圈本来就不是平底色。
     */
    const val SOLID_MIN_COVERAGE = 0.92f

    /** 最低亮度门槛：相对亮度 [relativeLuminance] ≥ 0.72 = 「亮底」（白/浅色气泡）。 */
    const val SOLID_MIN_LUMINANCE = 0.72f

    fun rgbDistance(first: Int, second: Int): Float {
        val dr = ((first shr 16) and 0xFF) - ((second shr 16) and 0xFF)
        val dg = ((first shr 8) and 0xFF) - ((second shr 8) and 0xFF)
        val db = (first and 0xFF) - (second and 0xFF)
        return sqrt((dr * dr + dg * dg + db * db).toFloat())
    }

    /**
     * 外圈一致率：贴着 [color]（距离 ≤ [maxDistance]）的采样像素占比（0..1）。
     *
     * @param pixels 环采样像素（前 [count] 个有效）
     */
    fun ringCoverage(
        pixels: IntArray,
        count: Int,
        color: Int,
        maxDistance: Float = RING_UNIFORM_DISTANCE
    ): Float {
        if (count <= 0) return 0f
        var matched = 0
        for (index in 0 until min(count, pixels.size)) {
            if (rgbDistance(pixels[index], color) <= maxDistance) matched += 1
        }
        return matched.toFloat() / count.toFloat()
    }

    /** WCAG 相对亮度（与 `BubbleColorExtractor.relativeLuminance` 同口径）。 */
    fun relativeLuminance(color: Int): Float {
        val r = channelLinear((color shr 16) and 0xFF)
        val g = channelLinear((color shr 8) and 0xFF)
        val b = channelLinear(color and 0xFF)
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    /**
     * 是否允许把这个气泡的盖板换成**纯色平涂**（`bg_type=solid`）。
     *
     * 两个条件缺一不可：
     * 1. 外圈一致率 [coverage] ≥ [SOLID_MIN_COVERAGE]（这一圈真是一条平底色）；
     * 2. 底色够亮（见 [isBright]）。
     *    深色平底在漫画里往往是画面暗部的一部分，纯色平涂容易把纹理一起盖没，
     *    所以与参考项目一致，只收亮底。
     *
     * ⚠️ 外圈被污染时（OCR 框比气泡宽 → 环的左右两端落在气泡外的插画上）这条会失败，
     * 那时用 `BubbleInteriorScanner.Interior.isFlatBrightContainer` 的第二条证据（容器内部量测）。
     */
    fun isFlatBrightBackground(color: Int, coverage: Float): Boolean {
        if (coverage < SOLID_MIN_COVERAGE) return false
        return isBright(color)
    }

    /** 亮底：相对亮度 ≥ [SOLID_MIN_LUMINANCE]（白/浅色底）。 */
    fun isBright(color: Int): Boolean {
        if (alphaOf(color) == 0) return false
        return relativeLuminance(color) >= SOLID_MIN_LUMINANCE
    }

    fun alphaOf(color: Int): Int = (color ushr 24) and 0xFF

    fun redOf(color: Int): Int = (color shr 16) and 0xFF

    fun greenOf(color: Int): Int = (color shr 8) and 0xFF

    fun blueOf(color: Int): Int = color and 0xFF

    private fun channelLinear(channel: Int): Float {
        val value = channel / 255f
        return if (value <= 0.04045f) {
            value / 12.92f
        } else {
            Math.pow(((value + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
        }
    }
}
