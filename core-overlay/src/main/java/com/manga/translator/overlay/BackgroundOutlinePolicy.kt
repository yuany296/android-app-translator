package com.manga.translator.overlay

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF

/**
 * 「要不要给译文加白边/黑边」的判定（2026-09-30 用户要求）。
 *
 * 用户原话：**「那个字体的白边黑边，要不只在背景有多种颜色的时候有吧」**。
 *
 * 判据：把**背景**采样成亮度序列，取 5%~95% 分位的跨度（稳健跨度，躲开孤立噪点）。
 *
 * - 跨度小 = 背景基本是一个色（**含平滑渐变**）→ 不加边：文字色本身已经由
 *   [BubbleColorExtractor] 选成「与背景对比度足够」的那个，再加一圈白边反而把
 *   笔画糊掉（实测 10px 字 + 1.8px 白边 = 空心白字，用户反馈「看不清」）。
 * - 跨度大 = 背景有花纹/照片/多色 → 加边，保证文字轮廓在任何局部都清晰。
 *
 * ⚠️ 采样必须用**清理图**（原文已擦掉的那张），不能用原图：原图那一块还压着原文笔画，
 * 深笔画 + 浅底本身就把亮度跨度拉满 → 永远判定「多色」→ 开关等于没做。
 */
object BackgroundOutlinePolicy {

    /**
     * 稳健亮度跨度阈值（0-255）。低于它视为「背景基本单色」。
     *
     * 取值依据：平滑渐变在一个气泡大小（几百像素）的窗口内跨度只有个位数到十几；
     * 带网点/花纹/照片的背景跨度普遍 60 以上。56 落在两者之间的空档里。
     */
    const val BUSY_LUMA_RANGE = 56

    /** 采样上限：判定「背景是不是一个色」不需要逐像素。 */
    private const val MAX_SAMPLES = 1024

    /** 样本太少时不下结论（宁可保留描边，也不要因为几个像素误判成单色）。 */
    private const val MIN_SAMPLES = 8

    /**
     * 结论：这条译文要不要描边。
     *
     * 两个**互相独立**的理由（命中任一就要）：
     * 1. **原文自带描边** —— 那是原作者的排版意图，译文风格要跟原文一致
     *    （2026-09-30 用户要求「除了背景复杂，还有就是原文有的时候，也需要黑边白边」）；
     * 2. **背景有多种颜色** —— 文字色在任何局部都未必够对比度，必须靠描边保轮廓。
     *
     * ⚠️ 判据 1 优先：原文有描边时，即使背景这次恰好是单色也照样描边，不能吞掉作者风格。
     *
     * @param background 清理图（拼接图像素坐标）；null = 拿不到背景
     * @param rect       气泡内容框（拼接图像素坐标）
     * @param bgType     气泡背景类型："solid"（实底）/ 其它（旁白、复杂背景）
     * @param originalHadOutline 原文是否自带描边（来自 [BubbleColorExtractor]）
     */
    fun needsOutline(
        background: Bitmap?,
        rect: RectF?,
        bgType: String,
        originalHadOutline: Boolean = false
    ): Boolean {
        // 实底气泡在渲染层本来就只画纯色衬底、从不描边（CSS 里描边只挂在 .mt-bg-none 上），
        // 这里直接给 false 保持两边口径一致。
        if (bgType.equals("solid", ignoreCase = true)) return false
        if (originalHadOutline) return true
        val samples = brightnessSamples(background, rect)
        if (samples.size < MIN_SAMPLES) return true
        return isBusy(samples)
    }

    /** 诊断用：一句话说明这条为什么要/不要描边（日志里直接看这个）。 */
    fun describe(background: Bitmap?, rect: RectF?, bgType: String, originalHadOutline: Boolean): String {
        if (bgType.equals("solid", ignoreCase = true)) return "solid"
        if (originalHadOutline) return "outline"
        val samples = brightnessSamples(background, rect)
        if (samples.size < MIN_SAMPLES) return "unknown"
        return if (isBusy(samples)) "busy" else "plain"
    }

    /** 背景像素的亮度序列（0-255）。拿不到背景/框时返回空数组。 */
    fun brightnessSamples(background: Bitmap?, rect: RectF?): IntArray {
        if (background == null || background.isRecycled || rect == null) return IntArray(0)
        if (background.width <= 0 || background.height <= 0) return IntArray(0)
        val left = rect.left.toInt().coerceIn(0, background.width - 1)
        val top = rect.top.toInt().coerceIn(0, background.height - 1)
        val right = rect.right.toInt().coerceIn(left + 1, background.width)
        val bottom = rect.bottom.toInt().coerceIn(top + 1, background.height)
        val boxW = right - left
        val boxH = bottom - top
        if (boxW < 2 || boxH < 2) return IntArray(0)
        // 步长选到「不超过 MAX_SAMPLES 个采样点」为止，同时保证两个方向都真的被扫到。
        val step = maxOf(1, kotlin.math.ceil(kotlin.math.sqrt((boxW.toLong() * boxH) / MAX_SAMPLES.toDouble())).toInt())
        val out = ArrayList<Int>(MAX_SAMPLES)
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val pixel = background.getPixel(x, y)
                if (Color.alpha(pixel) >= 200) out.add(luma(pixel))
                x += step
            }
            y += step
        }
        return out.toIntArray()
    }

    /**
     * 亮度分布是否说明背景「有多种颜色」。
     *
     * 用 5%~95% 稳健跨度而不是 max-min：JPEG/缩放的孤立噪点很容易把 max-min 拉满，
     * 而它对观感毫无影响。
     */
    fun isBusy(samples: IntArray): Boolean {
        if (samples.size < MIN_SAMPLES) return false
        val sorted = samples.sortedArray()
        val low = sorted[((sorted.size - 1) * 5) / 100]
        val high = sorted[((sorted.size - 1) * 95) / 100]
        return high - low > BUSY_LUMA_RANGE
    }

    private fun luma(color: Int): Int {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
