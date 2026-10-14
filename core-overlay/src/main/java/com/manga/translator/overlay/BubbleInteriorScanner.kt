package com.manga.translator.overlay

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 气泡**内部可写区域**的像素测量（2026-10-13）。
 *
 * ## 为什么需要它
 *
 * 用户反馈：「ML Kit 的 OCR 识别有的时候文字会超出气泡框」「paddle 的识别的清理图是在
 * 正常的位置，但是文字不会自适应缩小」，以及「这个截页处的气泡框是纯色背景，但是它用的是
 * 清理图。希望处理为 solid」。
 *
 * 这一层同时回答两个问题：
 * 1. **排版框该收多窄**：排版框 = 字形框每侧外扩 15%（朝气泡外框方向），而气泡轮廓不是矩形
 *    —— 椭圆/圆角/收腰气泡在上下两端变窄，DOM 译文又在框内垂直居中，最上/最下一行就戳出白边。
 *    这里从字形框水平中心向外走，量出逐行可用宽度（按原文字行数分带取最窄）与中点列余量。
 * 2. **这块底算不算「平底色」**：外圈采样在「OCR 框比气泡宽」的现场会偏低（框两端已经伸到
 *    气泡外的插画上），量不出平底 → 盖板退回清理图（inpaint 灰痕）。这里顺带统计
 *    容器内部「贴着采样底色」的像素占比（[Interior.flatCoverage]），它与
 *    [Interior.isFlatBrightContainer] 一起构成纯色平涂的第二条证据。
 *
 * ## 判据（与 `WritableAreaRefiner` 同一套思路：阈值跟着**这块底自己的亮度**走）
 *
 * 一个像素算「底」的条件：
 * 1. 与采样底色的 RGB 距离 ≤ [DISTANCE_TOLERANCE]（抗锯齿/压缩噪声）；
 *    **或者**亮度落在底色亮度 ± [LUMA_TOLERANCE]（轻微渐变的平底）；
 * 2. 走向上遇到「非底」像素段：段长 ≤ maxRun 且段后**立刻**又是底 → 这是文字笔画或
 *    抗锯齿，跨过去继续走；否则（段太长、或段后已经不是这块底）= 撞上气泡轮廓/画面 → 停。
 *
 * ## 什么时候**不**收窄 / 不升级
 *
 * 只有当中点行确实量到一个**不比字形框窄的容器**（中点行宽 ≥ 字形宽 × 0.9）时才收窄；
 * 旁白/拟声词直接压在插画上时量不出容器，行为与改动前完全一致（绝不画白框、也不缩字号）。
 * 升级成纯色盖板还额外要求底色够亮、且容器内部颜色足够均匀（[Interior.isFlatBrightContainer]）
 * —— 网点/渐变平涂会变成肉眼可见的色块，这条底线不能破。
 */
class BubbleInteriorScanner {

    /** 测量结果（像素，传入坐标系）。 */
    data class Interior(
        val leftPx: Float,
        val topPx: Float,
        val rightPx: Float,
        val bottomPx: Float,
        /** 字形框中点行的可用宽度 —— 「容器是否可信」的判据用它。 */
        val midRowWidthPx: Float,
        /** 按原文字行数分带后，每行带内最窄的可用宽度。 */
        val lineWidthsPx: FloatArray,
        /** 中点列在**字形框上方**多出来的可用高度。 */
        val columnTopExtPx: Float,
        /** 中点列在**字形框下方**多出来的可用高度。 */
        val columnBottomExtPx: Float,
        /** 容器内部采样里贴着采样底色（距离 ≤ 24）的像素占比（0..1）。 */
        val flatCoverage: Float,
        /** 容器内部参与一致率统计的采样数（太少时判据不可信）。 */
        val flatSamples: Int,
        /**
         * 逐行量到的容器边界（拼接图像素）：扁平三元组 `[y, left, right] × N`，自上而下。
         *
         * 用途：纯色盖板是**矩形**，而气泡轮廓不是（椭圆/圆角）——按这份轮廓裁一下就
         * 不会在气泡外露白肩（2026-10-13 真机夹具实测：445px 宽的文字框在椭圆收腰处
         * 比轮廓宽 18px/侧）。
         */
        val profileRows: FloatArray
    ) {
        /** 逐行最窄的可用宽度（空 → 0）。 */
        val minLineWidthPx: Float
            get() = if (lineWidthsPx.isEmpty()) 0f else lineWidthsPx.min()

        /** 中点行是否量到「不比字形框窄」的容器。 */
        fun hasContainer(glyphWidthPx: Float): Boolean =
            glyphWidthPx >= 8f && midRowWidthPx >= glyphWidthPx * CONTAINER_MIN_RATIO

        /**
         * 排版框宽度上限（像素）；null = 不收窄（量不到可信容器）。
         *
         * 收窄值再兜一个下限 [MIN_CAP_RATIO] × 字形宽 —— 测量噪声不该把字号压没。
         */
        fun widthCapPx(glyphWidthPx: Float): Float? {
            if (!hasContainer(glyphWidthPx)) return null
            val cap = minLineWidthPx
            if (cap <= 0f) return null
            return cap.coerceAtLeast(glyphWidthPx * MIN_CAP_RATIO)
        }

        /**
         * 排版框高度上限（像素）；null = 不收窄。
         *
         * 与宽度同一条「容器可信」判据；高度按「上余量 + 字形高 + 下余量」算。
         * 排版框的垂直本来就已经收到字形框 ∓ 0.2 行高，所以这一项通常只是护栏。
         */
        fun heightCapPx(glyphWidthPx: Float, glyphHeightPx: Float): Float? {
            if (!hasContainer(glyphWidthPx) || glyphHeightPx < 8f) return null
            val room = glyphHeightPx + columnTopExtPx + columnBottomExtPx
            return room.coerceAtLeast(glyphHeightPx * MIN_CAP_RATIO)
        }

        /**
         * 「这是一块亮的平色容器」→ 允许盖板走**纯色平涂**（`bg_type=solid`），
         * 不再从清理图（inpaint）裁背景。
         *
         * 三个条件（前两条与 [widthCapPx] 相同，第三条是**量出来的**平底证据）：
         * 1. 中点行量到不比字形框窄的容器（说明文字确实落在一块底里，而不是压在插画上）；
         * 2. 底色够亮（[BubbleBackgroundPolicy.isBright]）；
         * 3. 容器内部采样的一致率 ≥ 0.92（网点/渐变上不去，绝不会被平涂成色块）。
         */
        fun isFlatBrightContainer(glyphWidthPx: Float, bgColor: Int): Boolean {
            if (!hasContainer(glyphWidthPx)) return false
            if (flatSamples < MIN_FLAT_SAMPLES) return false
            if (flatCoverage < BubbleBackgroundPolicy.SOLID_MIN_COVERAGE) return false
            return BubbleBackgroundPolicy.isBright(bgColor)
        }

        /**
         * 容器是否**可信**（可以拿它当排版框的上下限）。
         *
         * 为什么需要（2026-10-14 用户报「超出太多了吧」）：气泡与背景**同色**时（白气泡画在
         * 白面板/白页面上），走向会跨过那 3px 描边继续走到画面边缘 —— 量到的「容器」其实是
         * 整页/整个面板，逐行最窄处于是变成一个乱值（真机夹具：白面板上量到 213px 宽，
         * 排版框被压成一根窄柱，译文挤成 6 行、整块戳出气泡）。
         *
         * 两条判据：
         * 1. 容器不能贴着画面边缘（贴着 = 量到的是页面/面板，不是气泡）；
         * 2. 容器不能比字形框大出离谱（宽 > [CONTAINER_MAX_WIDTH_RATIO]×、
         *    高 > [CONTAINER_MAX_HEIGHT_RATIO]×）—— 真气泡再大也有限，超出就是没被轮廓拦住。
         */
        fun isTrustworthyContainer(
            glyphWidthPx: Float,
            glyphHeightPx: Float,
            imageWidth: Int,
            imageHeight: Int
        ): Boolean {
            if (imageWidth <= 0 || imageHeight <= 0) return false
            if (leftPx <= 1f && rightPx >= imageWidth - 1f) return false
            if (topPx <= 1f && bottomPx >= imageHeight - 1f) return false
            if (glyphWidthPx < 8f || glyphHeightPx < 8f) return false
            if (rightPx - leftPx > glyphWidthPx * CONTAINER_MAX_WIDTH_RATIO) return false
            if (bottomPx - topPx > glyphHeightPx * CONTAINER_MAX_HEIGHT_RATIO) return false
            return true
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Interior) return false
            return leftPx == other.leftPx && topPx == other.topPx && rightPx == other.rightPx &&
                bottomPx == other.bottomPx && midRowWidthPx == other.midRowWidthPx &&
                lineWidthsPx.contentEquals(other.lineWidthsPx) &&
                columnTopExtPx == other.columnTopExtPx && columnBottomExtPx == other.columnBottomExtPx &&
                flatCoverage == other.flatCoverage && flatSamples == other.flatSamples &&
                profileRows.contentEquals(other.profileRows)
        }

        override fun hashCode(): Int {
            var result = leftPx.hashCode()
            result = 31 * result + topPx.hashCode()
            result = 31 * result + rightPx.hashCode()
            result = 31 * result + bottomPx.hashCode()
            result = 31 * result + midRowWidthPx.hashCode()
            result = 31 * result + lineWidthsPx.contentHashCode()
            result = 31 * result + columnTopExtPx.hashCode()
            result = 31 * result + columnBottomExtPx.hashCode()
            result = 31 * result + flatCoverage.hashCode()
            result = 31 * result + flatSamples
            result = 31 * result + profileRows.contentHashCode()
            return result
        }

        companion object {
            const val CONTAINER_MIN_RATIO = 0.9f
            const val MIN_CAP_RATIO = 0.55f
            /** 一致率判据至少要有这么多样本才可信（字形框 8×8 起步就有几十个采样点）。 */
            const val MIN_FLAT_SAMPLES = 48

            /** 可信容器的宽度上限：比字形框宽出 > 它 = 没被气泡轮廓拦住（量到的是页面/面板）。 */
            const val CONTAINER_MAX_WIDTH_RATIO = 2.0f

            /** 同上（高度）：单行文字的气泡可以比字高好几倍，所以这一档放宽。 */
            const val CONTAINER_MAX_HEIGHT_RATIO = 2.6f
        }
    }

    /**
     * 测量 [glyph]（字形框，像素）周围的容器内部区域；量不到返回 null。
     *
     * @param bgColor 采样底色（[BubbleColorExtractor.sampleBackground]）
     * @param lineCount 原文字行数（用于把字形框高度分带）
     */
    fun scan(source: Bitmap, glyph: RectF, lineCount: Int, bgColor: Int): Interior? {
        if (source.isRecycled || source.width <= 0 || source.height <= 0) return null
        return runCatching {
            scanWith(
                imageWidth = source.width,
                imageHeight = source.height,
                glyphLeft = glyph.left,
                glyphTop = glyph.top,
                glyphRight = glyph.right,
                glyphBottom = glyph.bottom,
                lineCount = lineCount,
                bgColor = bgColor,
                pixelAt = { x, y -> source.getPixel(x, y) }
            )
        }.getOrNull()
    }

    /**
     * 纯逻辑核心（可单测）：[pixelAt] 之外不依赖任何 Android API。
     *
     * ⚠️ 参数刻意收成裸 float 而不是 `RectF`：单元测试里 `android.graphics` 是 mockable jar，
     * `RectF(width)` 之类的方法会抛 `Method … not mocked`，**字段**读出来恒为 0
     * （2026-10-13 实测：`RectF(100f,65f,360f,245f).left == 0f`）——拿 `RectF` 当纯数据容器
     * 会让这套测量逻辑完全无法单测。
     */
    internal fun scanWith(
        imageWidth: Int,
        imageHeight: Int,
        glyphLeft: Float,
        glyphTop: Float,
        glyphRight: Float,
        glyphBottom: Float,
        lineCount: Int,
        bgColor: Int,
        pixelAt: (Int, Int) -> Int
    ): Interior? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        val left = glyphLeft.toInt().coerceIn(0, imageWidth - 1)
        val right = glyphRight.toInt().coerceIn(left + 1, imageWidth)
        val top = glyphTop.toInt().coerceIn(0, imageHeight - 1)
        val bottom = glyphBottom.toInt().coerceIn(top + 1, imageHeight)
        if (right - left < 6 || bottom - top < 6) return null

        val bgLuma = lumaOf(bgColor)
        val isBackground = { x: Int, y: Int -> isBackgroundPixel(pixelAt(x, y), bgColor, bgLuma) }
        val centerX = (left + right) / 2
        val centerY = (top + bottom) / 2
        val rowStep = max(1, min(ROW_STEP_PX, (bottom - top) / 8))
        // 允许跨过的「非底」段长：按**整个字形框高度**算，而不是按单行高。
        //
        // 为什么不能按单行：CJK 的横画在**行方向**上很长（「三 / 言」这类字的一横能有 20~30px），
        // 按单行高 ×0.4 算出来的上限（4 行时约 17px）会被这一横卡住 → 走向在文字内部就停，
        // 盖板轮廓于是切进文字里（原文会从缺口露出来）。按框高 ×0.22（上限 48px）算，
        // 既能跨过笔画，又不会跨过气泡轮廓：轮廓外面是插画（不是底色），run 会一直涨到上限。
        val maxRun = max(MIN_RUN_BRIDGE_PX.toFloat(), (bottom - top) * RUN_BRIDGE_BOX_RATIO)
            .toInt()
            .coerceAtMost(MAX_RUN_BRIDGE_PX)

        var spanLeft = centerX
        var spanRight = centerX
        var spanTop = centerY
        var spanBottom = centerY
        var midRowWidth = 0
        var flatMatched = 0
        var flatTotal = 0
        val rows = ArrayList<Int>(64)
        val chords = ArrayList<Float>(64)
        val profile = ArrayList<Float>(PROFILE_MAX_ROWS * 3)
        var lastProfileY = Int.MIN_VALUE
        val profileStride = max(rowStep, (bottom - top) / PROFILE_MAX_ROWS)
        var y = top
        while (y < bottom) {
            val extremes = rowExtent(y, centerX, imageWidth, isBackground, maxRun)
            if (extremes != null) {
                val rowLeft = extremes[0]
                val rowRight = extremes[1]
                if (abs(y - centerY) < rowStep) {
                    midRowWidth = rowRight - rowLeft + 1
                }
                rows += y
                chords += (rowRight - rowLeft + 1).toFloat()
                if (y - lastProfileY >= profileStride || lastProfileY == Int.MIN_VALUE) {
                    lastProfileY = y
                    profile += y.toFloat()
                    profile += rowLeft.toFloat()
                    profile += (rowRight + 1).toFloat()
                }
                if (rowLeft < spanLeft) spanLeft = rowLeft
                if (rowRight > spanRight) spanRight = rowRight
                // 一致率统计：沿这一行的可用区间等距采样（背景像素才计入）。
                var sampleX = rowLeft
                while (sampleX <= rowRight) {
                    val pixel = pixelAt(sampleX, y)
                    if (isBackground(sampleX, y)) {
                        flatTotal += 1
                        if (BubbleBackgroundPolicy.rgbDistance(pixel, bgColor) <=
                            BubbleBackgroundPolicy.RING_UNIFORM_DISTANCE
                        ) {
                            flatMatched += 1
                        }
                    }
                    sampleX += FLAT_SAMPLE_STEP_PX
                }
            }
            y += rowStep
        }
        if (rows.isEmpty()) return null
        if (midRowWidth <= 0) {
            // 中点行没测到（步长跳过）→ 取离中点最近的一行。
            var bestIndex = 0
            var bestDistance = Int.MAX_VALUE
            rows.forEachIndexed { index, rowY ->
                val distance = abs(rowY - centerY)
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestIndex = index
                }
            }
            midRowWidth = chords[bestIndex].toInt()
        }
        // 逐行分带（与 DOM 排版同一口径：行高 = 字形高 ÷ 行数，从上往下切）。
        val lineBands = max(1, lineCount)
        val lineBandHeight = (bottom - top).toFloat() / lineBands
        val lineWidths = FloatArray(lineBands) { -1f }
        rows.forEachIndexed { index, rowY ->
            val band = ((rowY - top) / lineBandHeight).toInt().coerceIn(0, lineBands - 1)
            val chord = chords[index]
            if (lineWidths[band] < 0f || chord < lineWidths[band]) lineWidths[band] = chord
        }
        // 没有行数据的带（步长跳过）→ 用中点行宽度兜底。
        for (band in lineWidths.indices) {
            if (lineWidths[band] < 0f) lineWidths[band] = midRowWidth.toFloat()
        }

        val upExtent = columnExtent(centerX, centerY, -1, imageHeight, isBackground, maxRun)
        val downExtent = columnExtent(centerX, centerY, 1, imageHeight, isBackground, maxRun)
        val columnTop = centerY - upExtent
        val columnBottom = centerY + downExtent
        if (columnTop < spanTop) spanTop = columnTop
        if (columnBottom > spanBottom) spanBottom = columnBottom
        // 中点列同样计入一致率（上下两条竖直采样）。
        var sampleY = columnTop
        while (sampleY <= columnBottom) {
            val pixel = pixelAt(centerX, sampleY)
            if (isBackground(centerX, sampleY)) {
                flatTotal += 1
                if (BubbleBackgroundPolicy.rgbDistance(pixel, bgColor) <=
                    BubbleBackgroundPolicy.RING_UNIFORM_DISTANCE
                ) {
                    flatMatched += 1
                }
            }
            sampleY += FLAT_SAMPLE_STEP_PX
        }

        return Interior(
            leftPx = spanLeft.toFloat(),
            topPx = spanTop.toFloat(),
            rightPx = (spanRight + 1).toFloat(),
            bottomPx = (spanBottom + 1).toFloat(),
            midRowWidthPx = midRowWidth.toFloat(),
            lineWidthsPx = lineWidths,
            columnTopExtPx = (top - columnTop).toFloat().coerceAtLeast(0f),
            columnBottomExtPx = (columnBottom - bottom).toFloat().coerceAtLeast(0f),
            flatCoverage = if (flatTotal <= 0) 0f else flatMatched.toFloat() / flatTotal.toFloat(),
            flatSamples = flatTotal,
            profileRows = profile.toFloatArray()
        )
    }

    /** 单行向左/右扩到哪：返回 `[left, right]`（含端点）；中心不是底且两侧都没扩出去 → null。 */
    private fun rowExtent(
        y: Int,
        centerX: Int,
        imageWidth: Int,
        isBackground: (Int, Int) -> Boolean,
        maxRun: Int
    ): IntArray? {
        val centerIsBackground = isBackground(centerX, y)
        var left = centerX
        var right = centerX
        var x = centerX
        var run = 0
        while (x >= 0) {
            if (isBackground(x, y)) {
                left = x
                run = 0
            } else {
                run += 1
                if (run > maxRun) break
            }
            x -= 1
        }
        x = centerX
        run = 0
        while (x < imageWidth) {
            if (isBackground(x, y)) {
                right = x
                run = 0
            } else {
                run += 1
                if (run > maxRun) break
            }
            x += 1
        }
        if (!centerIsBackground && left == centerX && right == centerX) return null
        return intArrayOf(left, right)
    }

    /** 中点列往上/下扩到哪：返回从 centerY 起算的像素距离（含起点）。 */
    private fun columnExtent(
        x: Int,
        centerY: Int,
        direction: Int,
        imageHeight: Int,
        isBackground: (Int, Int) -> Boolean,
        maxRun: Int
    ): Int {
        var extent = 1
        var y = centerY + direction
        var run = 0
        while (y in 0 until imageHeight) {
            if (isBackground(x, y)) {
                extent += 1
                run = 0
            } else {
                run += 1
                if (run > maxRun) break
            }
            y += direction
        }
        return extent
    }

    private fun isBackgroundPixel(pixel: Int, bgColor: Int, bgLuma: Float): Boolean {
        if (BubbleBackgroundPolicy.rgbDistance(pixel, bgColor) <= DISTANCE_TOLERANCE) return true
        return abs(lumaOf(pixel) - bgLuma) <= LUMA_TOLERANCE
    }

    private fun lumaOf(color: Int): Float {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return r * 0.299f + g * 0.587f + b * 0.114f
    }

    private companion object {
        const val DISTANCE_TOLERANCE = 52f

        /**
         * 「亮度落在底色附近」这条放行只给**轻微渐变**（±26）。
         *
         * ⚠️ 2026-10-13 真机夹具实测：44 太松 —— 白气泡外的浅灰云（luma 213 vs 底色 251）
         * 会被当成「还是这块底」，走向上直接跨过气泡描边伸进插画里，盖板轮廓于是比气泡大出一圈
         * （截图里气泡上沿外侧一条白边）。26 与「一致率 24」同量级，够吃渐变、吃不掉插画。
         */
        const val LUMA_TOLERANCE = 26f
        const val ROW_STEP_PX = 3
        const val MIN_RUN_BRIDGE_PX = 6

        /** 一致率统计的采样步长（行内与列上同值）。 */
        const val FLAT_SAMPLE_STEP_PX = 3

        /** 轮廓剖面最多取几行（盖板裁剪用；越少越省、越多越贴轮廓）。 */
        const val PROFILE_MAX_ROWS = 18

        /** 允许跨过的「非底」段长 = 字形框高 × 它（文字笔画/抗锯齿；见 `maxRun` 的说明）。 */
        const val RUN_BRIDGE_BOX_RATIO = 0.22f

        /** 段长上限（px）：再长就只可能是轮廓外的插画，不该跨过去。 */
        const val MAX_RUN_BRIDGE_PX = 48
    }
}
