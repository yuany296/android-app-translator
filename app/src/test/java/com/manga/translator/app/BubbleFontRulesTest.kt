package com.manga.translator.app

import com.manga.translator.translate.QuadPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 译文字号规则单测（2026-09-28，对齐 C:\homework\translator `lifecycle-font-fit.js`）。
 *
 * 用户要求「字体大小要和原文一样」：字号起点 = 原文单行字高 × 1.08，
 * 上限 = min(框高 × 0.88, 原文单行字高 × 1.08)，放不下才二分缩小，最后 × 0.98。
 *
 * 安全系数用 **0.98** 而不是参考项目的 0.96：0.96 是给 DOM 取整留的余量，
 * 画布渲染没有取整误差，0.96 叠上 1.08 起点再叠行距会明显偏小
 * （2026-09-28 用户反馈「字号感觉小了」后上调，见 `BubbleImageRenderer.FONT_SAFETY_SCALE`）。
 */
class BubbleFontRulesTest {

    private val renderer = BubbleImageRenderer()

    /** 用一个「字号 ≤ [limit] 就放得下」的假测量函数驱动纯规则。 */
    private fun planner(contentHeight: Float, originalLine: Float, limit: Float): Float {
        return renderer.planFontSize(contentHeight, originalLine) { size -> size <= limit }
    }

    @Test
    fun originalLineHeightSplitsBySourceLineCount() {
        // 单行原文：整框高就是原文字高
        assertEquals(80f, renderer.originalLineHeightPx(80f, 1), 0.01f)
        // 三行原文（逐行 OCR 框 3 个）：字高 = 框高 / 3
        assertEquals(71f, renderer.originalLineHeightPx(213f, 3), 0.01f)
        // 零行保护
        assertEquals(120f, renderer.originalLineHeightPx(120f, 0), 0.01f)
        // 空内容不炸
        assertEquals(0f, renderer.originalLineHeightPx(0f, 2), 0.01f)
    }

    @Test
    fun verticalLayoutUsesColumnWidthAsGlyphSize() {
        // 竖排：字号跟列宽走，与框高无关（框很高也只按列宽算）
        assertEquals(
            90f,
            renderer.originalLineHeightPx(
                contentHeightPx = 1200f,
                sourceLineCount = 1,
                contentWidthPx = 90f,
                vertical = true
            ),
            0.01f
        )
        // 竖排多列：列宽 / 列数
        assertEquals(
            45f,
            renderer.originalLineHeightPx(
                contentHeightPx = 1200f,
                sourceLineCount = 2,
                contentWidthPx = 90f,
                vertical = true
            ),
            0.01f
        )
        // 横排不受影响
        assertEquals(213f, renderer.originalLineHeightPx(213f, 1, 90f, false), 0.01f)
    }

    @Test
    fun sourceLineCountTakesMaxOfBoxesAndNewlines() {
        assertEquals(3, renderer.resolveSourceLineCount(3, "가 나 다"))
        assertEquals(2, renderer.resolveSourceLineCount(1, "첫 줄\n둘째 줄"))
        assertEquals(3, renderer.resolveSourceLineCount(3, "첫 줄\n둘째 줄"))
        assertEquals(1, renderer.resolveSourceLineCount(0, ""))
    }

    @Test
    fun startSizeFollowsOriginalTextHeightNotBoxHeight() {
        // 大框 + 单行原文：字号只跟原文字高走（旧实现按「框高 ×0.72」会被撑成巨字）
        val originalLine = 60f
        val bigBoxHeight = 600f
        val start = renderer.resolveStartSize(bigBoxHeight, originalLine)
        assertEquals(originalLine * 1.08f, start, 0.01f)
        assertTrue("起点必须远小于旧口径", start < bigBoxHeight * 0.72f)
    }

    @Test
    fun startSizeFallsBackToBoxHeightWhenOriginalUnknown() {
        val start = renderer.resolveStartSize(100f, 0f)
        assertEquals(100f * 0.78f, start, 0.01f)
    }

    @Test
    fun startSizeNeverExceedsBoxHeightCap() {
        // 框很矮（40px）时，即便原文字高很大也要按框高收紧到 40*0.88
        val start = renderer.resolveStartSize(40f, 300f)
        assertEquals(40f * 0.88f, start, 0.01f)
    }

    @Test
    fun maxFontNeverExceedsOriginalHeightTimesScale() {
        assertEquals(60f * 1.08f, renderer.maxFontSizePx(600f, 60f), 0.01f)
        assertEquals(40f * 0.88f, renderer.maxFontSizePx(40f, 60f), 0.01f)
    }

    @Test
    fun shortTranslationKeepsOriginalFontSize() {
        // 短译文放得下（测量上限 999）→ 字号 = 原文字高 × 1.08 × 0.98 ≈ 63.5
        val size = planner(contentHeight = 600f, originalLine = 60f, limit = 999f)
        assertEquals(60f * 1.08f * 0.98f, size, 0.5f)
    }

    @Test
    fun longTranslationShrinksToFit() {
        val size = planner(contentHeight = 600f, originalLine = 60f, limit = 30f)
        assertTrue("放不下必须缩小到 30 附近（实际 $size）", size in 28f..30f)
    }

    @Test
    fun neverGoesBelowMinimumFontSize() {
        val size = planner(contentHeight = 600f, originalLine = 60f, limit = 1f)
        assertEquals(8f, size, 0.01f)
    }

    @Test
    fun withoutOriginalHeightUsesBoxHeightRatio() {
        // 无原文行高：起点 = 100 × 0.78 = 78，放得下 → 78 × 0.98
        val size = planner(contentHeight = 100f, originalLine = 0f, limit = 999f)
        assertEquals(100f * 0.78f * 0.98f, size, 0.5f)
    }

    @Test
    fun shrunkSizeIsAlsoCappedByOriginalHeight() {
        // 测量上限很大但原文字高小：仍不得超过 原字高 × 1.08
        val size = planner(contentHeight = 600f, originalLine = 20f, limit = 999f)
        assertTrue(size <= 20f * 1.08f + 0.01f)
    }

    // -----------------------------------------------------------------------
    // 接缝/跨图气泡的固定字号必须按「DOM 真实可用宽度」估（2026-09-30）
    //
    // 现场：接缝气泡排版框 490×108、原文字高 83（单行）、译文「这是什么？」= 5 个全角字。
    // 旧口径只扣画布留白（490−6=484）→ 5×89.6=448 放得下 → 字号 87.8 直接下发；
    // 但 DOM 的可用宽度是 490−2×0.34×字号（`.mt-bubble{padding:0.12em 0.34em}`），
    // 真实只有 ≈430 → 折行 → 文字层高度翻倍、译文溢出气泡。
    // -----------------------------------------------------------------------
    private val domPaddingEm = 0.34f

    /** 接缝气泡现场参数。 */
    private val seamBoxWidth = 490f
    private val seamBoxHeight = 108f
    private val seamOriginalLine = 83f

    /** 用「n 个全角字 = n × 字号」的假测量驱动字号规则。 */
    private fun seamFixedFont(horizontalPaddingEmRatio: Float, glyphs: Float): Float {
        return renderer.planFontSize(seamBoxHeight, seamOriginalLine) { size ->
            glyphs * size <= renderer.contentWidthPx(seamBoxWidth, size, horizontalPaddingEmRatio)
        }
    }

    @Test
    fun contentWidthKeepsLegacyValueWithoutPadding() {
        // 不传内边距（画布渲染口径）→ 与旧版逐字节一致
        assertEquals(484, renderer.contentWidthPx(490f))
        assertEquals(484, renderer.contentWidthPx(490f, 88f, 0f))
        // 极窄框有下限保护
        assertEquals(8, renderer.contentWidthPx(4f))
    }

    @Test
    fun contentWidthShrinksWithFontSizeForEmPadding() {
        // 同一个框，字号越大可用宽度越小（em 内边距随字号缩放）
        val small = renderer.contentWidthPx(490f, 40f, domPaddingEm)
        val large = renderer.contentWidthPx(490f, 88f, domPaddingEm)
        assertTrue("字号变大可用宽度必须变小（$large vs $small）", large < small)
        // 88 号字：490 − 6 − 2×0.34×88 = 424.16 → 424
        assertEquals(424, large)
    }

    @Test
    fun seamFixedFontFitsRealDomContentWidth() {
        val legacy = seamFixedFont(0f, 5f)
        val fixed = seamFixedFont(domPaddingEm, 5f)

        // 旧口径：起点（原字高 ×1.08）就放得下 → 87.8
        assertEquals(seamOriginalLine * 1.08f * 0.98f, legacy, 0.5f)
        // 旧口径在 DOM 里其实放不下 —— 这正是折行的根因
        assertTrue(
            "旧口径必须被证伪：5×$legacy > 可用 ${renderer.contentWidthPx(seamBoxWidth, legacy, domPaddingEm)}",
            5f * legacy > renderer.contentWidthPx(seamBoxWidth, legacy, domPaddingEm)
        )
        // 新口径：收小，且真的放得下
        assertTrue("扣掉 em 内边距后必须收小（实际 $fixed）", fixed < legacy - 1f)
        assertTrue(
            "收小后必须放得下：5×$fixed ≤ ${renderer.contentWidthPx(seamBoxWidth, fixed, domPaddingEm)}",
            5f * fixed <= renderer.contentWidthPx(seamBoxWidth, fixed, domPaddingEm)
        )
        // 不能因为要放得下就缩到原文一半以下（译文仍应与原文同量级）
        assertTrue("不能过度收缩（实际 $fixed，原字高 $seamOriginalLine）", fixed > seamOriginalLine * 0.8f)
    }

    @Test
    fun seamFixedFontStillCappedByOriginalHeight() {
        // 框很宽、原文字高很小：内边距口径不得放松「原字高 ×1.08」这条上限
        val fixed = renderer.planFontSize(400f, 20f) { size ->
            1f * size <= renderer.contentWidthPx(2000f, size, domPaddingEm)
        }
        assertTrue(fixed <= 20f * 1.08f + 0.01f)
    }

    // ------------------------------------------------------------------
    // 百分比四边形 → 像素厚度（2026-09-30）：单位错了会把原文字高算小 3.5 倍
    // ------------------------------------------------------------------

    @Test
    fun percentQuadThicknessConvertsToPixelsFirst() {
        // 原图 580x340；百分比空间里 50×20 的框 = 像素空间 290×68。
        // 旧实现直接把百分比数值当像素 → 返回 20（而不是 68），
        // 于是原文行高只有真值的 0.29 倍 → 字号被 FONT_MIN 兜在 10px。
        val quad = listOf(
            QuadPoint(10f, 10f), QuadPoint(60f, 10f),
            QuadPoint(60f, 30f), QuadPoint(10f, 30f)
        )
        assertEquals(68f, percentQuadThicknessPx(quad, 580, 340), 0.05f)
    }

    @Test
    fun tiltedNarrationThicknessMatchesRealDeviceMeasurement() {
        // 真机素材（`v-tiltonly.html`，20° 斜排韩文旁白）实测：
        // 字形沿轴长 287.4px、厚度 37.7px（图像 580×340）。
        // 代码里把两个方向的百分比混成同一个比例（用宽度换算）会得到 10.7（=37.7×580/340/6），
        // 那正是真机上 `ink_tilt` 打出来的 lineH。
        val quad = listOf(
            QuadPoint(12.319f, 15.644f),
            QuadPoint(58.871f, 44.526f),
            QuadPoint(56.647f, 54.944f),
            QuadPoint(10.095f, 26.062f)
        )
        val thickness = percentQuadThicknessPx(quad, 580, 340)
        assertEquals("厚度必须还原成像素（真值 37.7）", 37.7f, thickness, 0.6f)
        assertTrue(
            "绝不能退化成 10.7（百分比被当像素用）",
            thickness > 30f
        )
    }

    @Test
    fun percentQuadThicknessHandlesMissingInput() {
        // 没有四边形 / 尺寸非法 → 0（调用方会退回「框高÷行数」口径）
        assertEquals(0f, percentQuadThicknessPx(null, 580, 340), 0.001f)
        assertEquals(
            0f,
            percentQuadThicknessPx(
                listOf(QuadPoint(1f, 1f), QuadPoint(2f, 1f), QuadPoint(2f, 2f)),
                580,
                340
            ),
            0.001f
        )
        assertEquals(
            0f,
            percentQuadThicknessPx(
                listOf(QuadPoint(1f, 1f), QuadPoint(2f, 1f), QuadPoint(2f, 2f), QuadPoint(1f, 2f)),
                0,
                340
            ),
            0.001f
        )
    }
}
