package com.manga.translator.overlay

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import kotlin.math.max
import kotlin.math.min

class WritableAreaRefiner {
    fun refine(
        source: Bitmap,
        srcRect: RectF,
        bubbleType: String
    ): RectF {
        val raw = clampRect(srcRect, source.width, source.height)
        if (raw.width() <= 2f || raw.height() <= 2f) {
            return raw
        }
        return when {
            bubbleType.equals("solid", ignoreCase = true) -> refineSolid(source, raw)
            bubbleType.equals("transparent", ignoreCase = true) ||
                bubbleType.equals("none", ignoreCase = true) -> shiftOpticalCenter(raw, upwardRatio = 0.045f)

            else -> raw
        }
    }

    private fun refineSolid(source: Bitmap, raw: RectF): RectF {
        val roi = Rect(
            raw.left.toInt().coerceIn(0, source.width - 1),
            raw.top.toInt().coerceIn(0, source.height - 1),
            raw.right.toInt().coerceIn(1, source.width),
            raw.bottom.toInt().coerceIn(1, source.height)
        )
        val maxInsetX = max(1, (roi.width() * MAX_INSET_RATIO_X).toInt())
        val maxInsetY = max(1, (roi.height() * MAX_INSET_RATIO_Y).toInt())

        // ⚠️ 判据阈值必须**相对本区域自己的背景亮度**（2026-09-30 修）。
        //
        // 原实现写死 `averageLuma < 208`，隐含假设「气泡底是白的」。漫画里彩色/渐变背景很常见，
        // 实测某页背景是蓝灰渐变、亮度只有 170~193 —— 于是**每一条**边缘条带都被判成「有内容」，
        // 四个方向一路缩到 `MAX_INSET_RATIO` 上限：`283x136@206,929` 被砍成 `182x95@257,950`
        // （宽 -36%、高 -30%）。而斜排文字的倾角就是在这个被砍小的框里估计的：
        // 最小外接矩形只包得住中段 → 用户看到「倾斜的文本两边都有字没被遮住」。
        //
        // 取 ROI 的 92 分位亮度当背景（背景像素占比远高于墨迹，高分位稳落在背景上），
        // 并**保留旧阈值作为上限** —— 新的判据只会比原来更保守，绝不会新引入「缩过头」：
        // 白底气泡（背景 ≈255）算出来仍是 208，行为逐像素不变。
        val backgroundLuma = estimateBackgroundLuma(source, roi)
        val brightCut = WritableAreaThresholds.brightCut(backgroundLuma)
        val darkCut = WritableAreaThresholds.darkCut(backgroundLuma)

        val leftInset = scanInset(
            limit = maxInsetX,
            brightCut = brightCut,
            sampler = { inset -> sampleVerticalStrip(source, roi, inset, fromLeft = true, darkCut = darkCut) }
        )
        val rightInset = scanInset(
            limit = maxInsetX,
            brightCut = brightCut,
            sampler = { inset -> sampleVerticalStrip(source, roi, inset, fromLeft = false, darkCut = darkCut) }
        )
        val topInset = scanInset(
            limit = maxInsetY,
            brightCut = brightCut,
            sampler = { inset -> sampleHorizontalStrip(source, roi, inset, fromTop = true, darkCut = darkCut) }
        )
        val bottomInset = scanInset(
            limit = maxInsetY,
            brightCut = brightCut,
            sampler = { inset -> sampleHorizontalStrip(source, roi, inset, fromTop = false, darkCut = darkCut) }
        )

        val refined = RectF(
            roi.left + leftInset.toFloat(),
            roi.top + topInset.toFloat(),
            roi.right - rightInset.toFloat(),
            roi.bottom - bottomInset.toFloat()
        )
        return enforceMinimumSize(raw, refined)
    }

    /**
     * 「这一条边缘带还是背景吗」→ 是则继续往里收，否则就停（那就是字形外边界）。
     *
     * 2026-09-30 修：这里的判据此前是**反的**。旧实现是
     * 「条带还有墨 → 继续往里缩，遇到空白就停」，于是**文字内部**的字间空隙被当成了边界。
     * 实测（离线复刻 `logs/refine_probe.py`，与真机 `refine_boxes` 逐像素一致）：
     * 接缝气泡的 OCR 框是 `174..551`、文字真实范围是 `174..543`，精修却给出 `217..484` ——
     * 左端整个首字被切掉一半、右端尾字只剩前半，页面表现为「上下两端露字」。
     *
     * 正确语义：**边缘仍是背景（没有墨）就继续收；一旦碰到有墨的条带就停**。
     * 这样框只会「收掉 OCR 多给的空白」，绝不会缩进文字，与用户要求
     * 「不进行扩展，要扩也只是几个像素」一致。
     */
    internal fun scanInset(
        limit: Int,
        brightCut: Float,
        sampler: (Int) -> StripSignal
    ): Int {
        var inset = 0
        while (inset < limit) {
            val signal = sampler(inset)
            val isBackground = WritableAreaThresholds.looksLikeBackground(
                averageLuma = signal.averageLuma,
                darkRatio = signal.darkRatio,
                contrast = signal.contrast,
                brightCut = brightCut
            )
            if (!isBackground) {
                break
            }
            inset += STRIP_STEP_PX
        }
        return inset.coerceAtMost(limit)
    }

    /**
     * ROI 的背景亮度估计：采样后取 **92 分位**亮度。
     *
     * 为什么用高分位而不是中位数：中位数会被大面积墨迹拉低（密排小字时墨迹像素可能过半），
     * 高分位则稳落在背景上。为什么用分位而不是「最亮」：避免单个高光像素把背景抬飞。
     */
    private fun estimateBackgroundLuma(source: Bitmap, roi: Rect): Float {
        val stepX = max(1, roi.width() / BACKGROUND_SAMPLE_GRID)
        val stepY = max(1, roi.height() / BACKGROUND_SAMPLE_GRID)
        val lumas = ArrayList<Float>(64)
        var y = roi.top
        while (y < roi.bottom) {
            var x = roi.left
            while (x < roi.right) {
                lumas.add(luminance(source.getPixel(x, y)))
                x += stepX
            }
            y += stepY
        }
        if (lumas.isEmpty()) return BRIGHT_LUMA_THRESHOLD
        lumas.sort()
        val index = ((lumas.size * BACKGROUND_PERCENTILE) / 100).coerceIn(0, lumas.size - 1)
        return lumas[index]
    }

    /**
     * 左右收边用的**竖条**：横坐标贴着边缘走，纵坐标覆盖 ROI 的**整个高度**。
     *
     * 2026-09-30 修：这里此前只采 ROI 高度的 22%~78%（SAMPLE_BAND_START/END_RATIO），
     * 本意是回避气泡上下两条描边弧。但**字的最上/最下一行恰恰落在这段之外**：
     * 实测接缝气泡的文字墨迹是 y `751..833`，而宽窄采样带只看 769~815，
     * 于是 751~752 / 816~833 被判成「边缘空白」收掉，精修给出 `753..821` ——
     * 韩文的收音（字的下半截）露在盖板外（用户截图里「这是什」下面那一排横笔画）。
     * 现在整段采样：只要有**任何**一行有墨就不收边，宁可多留几像素也不露字。
     */
    private fun sampleVerticalStrip(
        source: Bitmap,
        roi: Rect,
        inset: Int,
        fromLeft: Boolean,
        darkCut: Float
    ): StripSignal {
        val xStart = if (fromLeft) roi.left + inset else roi.right - inset - STRIP_THICKNESS_PX
        val xEnd = (xStart + STRIP_THICKNESS_PX).coerceIn(roi.left + 1, roi.right)
        return sampleStrip(
            source,
            xStart.coerceIn(roi.left, roi.right - 1),
            xEnd,
            roi.top,
            roi.bottom,
            darkCut
        )
    }

    /** 上下收边用的**横条**：纵坐标贴着边缘走，横坐标覆盖 ROI 的**整个宽度**（同上）。 */
    private fun sampleHorizontalStrip(
        source: Bitmap,
        roi: Rect,
        inset: Int,
        fromTop: Boolean,
        darkCut: Float
    ): StripSignal {
        val yStart = if (fromTop) roi.top + inset else roi.bottom - inset - STRIP_THICKNESS_PX
        val yEnd = (yStart + STRIP_THICKNESS_PX).coerceIn(roi.top + 1, roi.bottom)
        return sampleStrip(
            source,
            roi.left,
            roi.right,
            yStart.coerceIn(roi.top, roi.bottom - 1),
            yEnd,
            darkCut
        )
    }

    private fun sampleStrip(
        source: Bitmap,
        xStart: Int,
        xEnd: Int,
        yStart: Int,
        yEnd: Int,
        darkCut: Float
    ): StripSignal {
        var lumaSum = 0f
        var darkCount = 0
        var contrastSum = 0f
        var samples = 0
        for (y in yStart until yEnd) {
            for (x in xStart until xEnd) {
                val color = source.getPixel(x, y)
                val luma = luminance(color)
                lumaSum += luma
                if (luma < darkCut) {
                    darkCount += 1
                }
                if (x + 1 < xEnd) {
                    contrastSum += kotlin.math.abs(luma - luminance(source.getPixel(x + 1, y)))
                }
                if (y + 1 < yEnd) {
                    contrastSum += kotlin.math.abs(luma - luminance(source.getPixel(x, y + 1)))
                }
                samples += 1
            }
        }
        if (samples == 0) {
            return StripSignal(BRIGHT_LUMA_THRESHOLD, 0f, 0f)
        }
        return StripSignal(
            averageLuma = lumaSum / samples.toFloat(),
            darkRatio = darkCount.toFloat() / samples.toFloat(),
            contrast = contrastSum / samples.toFloat()
        )
    }

    private fun shiftOpticalCenter(rect: RectF, upwardRatio: Float): RectF {
        val shift = rect.height() * upwardRatio
        val newTop = max(0f, rect.top - shift)
        val newBottom = newTop + rect.height()
        return if (newBottom <= rect.bottom) {
            RectF(rect.left, newTop, rect.right, newBottom)
        } else {
            RectF(rect.left, rect.top, rect.right, rect.bottom)
        }
    }

    private fun enforceMinimumSize(raw: RectF, refined: RectF): RectF {
        val minWidth = raw.width() * MIN_REFINED_WIDTH_RATIO
        val minHeight = raw.height() * MIN_REFINED_HEIGHT_RATIO
        val width = refined.width().coerceAtLeast(minWidth)
        val height = refined.height().coerceAtLeast(minHeight)
        val centerX = refined.centerX()
        val centerY = refined.centerY()
        val safe = RectF(
            centerX - width / 2f,
            centerY - height / 2f,
            centerX + width / 2f,
            centerY + height / 2f
        )
        return RectF(
            safe.left.coerceAtLeast(raw.left),
            safe.top.coerceAtLeast(raw.top),
            safe.right.coerceAtMost(raw.right),
            safe.bottom.coerceAtMost(raw.bottom)
        )
    }

    private fun clampRect(rect: RectF, sourceWidth: Int, sourceHeight: Int): RectF {
        val left = rect.left.coerceIn(0f, sourceWidth.toFloat())
        val top = rect.top.coerceIn(0f, sourceHeight.toFloat())
        val right = rect.right.coerceIn((left + 1f).coerceAtMost(sourceWidth.toFloat()), sourceWidth.toFloat())
        val bottom = rect.bottom.coerceIn((top + 1f).coerceAtMost(sourceHeight.toFloat()), sourceHeight.toFloat())
        return RectF(left, top, right, bottom)
    }

    private fun luminance(color: Int): Float {
        return (color.red * 0.299f) + (color.green * 0.587f) + (color.blue * 0.114f)
    }

    /** 一条边缘条带的三个信号（internal 只为单测能直接喂数据）。 */
    internal data class StripSignal(
        val averageLuma: Float,
        val darkRatio: Float,
        val contrast: Float
    )

    private companion object {
        private const val STRIP_THICKNESS_PX = 2
        private const val STRIP_STEP_PX = 1
        private const val MAX_INSET_RATIO_X = 0.18f
        private const val MAX_INSET_RATIO_Y = 0.16f

        /**
         * 采样失败时的兜底「亮背景」值（正常路径走 [WritableAreaThresholds]）。
         * 旧写死判据 208 现在退居为天花板，见 [WritableAreaThresholds.BRIGHT_LUMA_CAP]。
         */
        private const val BRIGHT_LUMA_THRESHOLD = 208f

        private const val MIN_REFINED_WIDTH_RATIO = 0.58f
        private const val MIN_REFINED_HEIGHT_RATIO = 0.54f

        /** 背景亮度估计：每边最多采 [BACKGROUND_SAMPLE_GRID] 个点，取 [BACKGROUND_PERCENTILE] 分位。 */
        private const val BACKGROUND_SAMPLE_GRID = 24
        private const val BACKGROUND_PERCENTILE = 92
    }
}

/**
 * 「可写区域精修」的判据阈值 —— 由 ROI 自己的背景亮度派生（2026-09-30）。
 *
 * 修复前是写死的 `averageLuma < 208`，隐含「气泡底是白的」这一前提。漫画里彩色/渐变背景很常见，
 * 实测某页背景是蓝灰渐变、亮度只有 170~193 → 每条边缘条带都被判成「有内容」→ 四个方向
 * 一路缩到上限（`283x136` → `182x95`，宽 -36%）。倾角估计就在这个被砍小的框里跑，
 * 最小外接矩形只包得住中段 → 页面表现为「倾斜的文本两边都有字没被遮住」。
 *
 * 这里把阈值改成 `min(背景 - 跌落量, 旧上限)`：背景越暗允许的条带亮度越低，
 * 而旧上限保证**只会比修复前更保守**，不会新引入「缩过头」。
 */
internal object WritableAreaThresholds {
    /** 旧写死值，现在降级为「绝不再往更激进方向走」的天花板。 */
    const val BRIGHT_LUMA_CAP = 208f
    const val DARK_LUMA_CAP = 164f

    /** 背景亮度往下掉多少算「这一条不是背景」。 */
    const val BRIGHT_DROP = 40f
    const val DARK_DROP = 70f

    /** 「亮背景」判据。 */
    fun brightCut(backgroundLuma: Float): Float =
        min(backgroundLuma - BRIGHT_DROP, BRIGHT_LUMA_CAP)

    /** 「深墨」判据（用于 darkRatio）。 */
    fun darkCut(backgroundLuma: Float): Float =
        min(backgroundLuma - DARK_DROP, DARK_LUMA_CAP)

    /**
     * 条带里「深墨像素」占比超过它 → 判定为已经压在字形上，收边到此为止。
     *
     * ⚠️ 取 **0**（= 只要沾到一个深墨像素就停），不能放回原来的 0.18。
     * 原值 0.18 是「这条带里深墨超过 18% 才算有内容」，于是**笔画的最上/最下一行**
     * （整条带里只有百分之几的像素沾到墨）被判成空白收掉：实测接缝气泡的文字墨迹是
     * y `751..833`，而首行 751~752 的深墨占比只有 0.057、末行 831~833 只有 0.088 →
     * 上下各收掉 2px，盖板底边只比墨迹多出 1.5px，抗锯齿的那一条灰边就露在外面
     * （用户截图里「这是什」下面那条细横线）。
     *
     * 取 0 的代价只是「OCR 框外侧只要有一点墨就不收边」，方向正好是用户要的
     * 「不进行扩展，要扩也只是几个像素」—— 宁可多盖一两像素，也不能露字。
     */
    const val DARK_RATIO_THRESHOLD = 0f

    /** 条带内平均相邻亮度差超过它 → 判定为有内容（文字边缘）。 */
    const val CONTRAST_THRESHOLD = 22f

    /**
     * 一条边缘条带是否**仍然像背景**（可以继续往里收边）。
     *
     * 三个判据全部满足才算背景：够亮、一个深墨像素都没有、相邻亮度差小。
     * 任一不满足 → 这一条已经压在字形上了，收边到此为止。
     *
     * （2026-09-30：收边的终止条件此前写反成「有内容才继续收」，见
     * [WritableAreaRefiner] 中 `scanInset` 的说明。）
     */
    fun looksLikeBackground(
        averageLuma: Float,
        darkRatio: Float,
        contrast: Float,
        brightCut: Float
    ): Boolean =
        averageLuma >= brightCut &&
            darkRatio <= DARK_RATIO_THRESHOLD &&
            contrast <= CONTRAST_THRESHOLD
}
