package com.manga.translator.app

import com.manga.translator.app.BubbleFitBoxPolicy.Box
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「自适应缩小以对话框为界」的纯几何策略单测（2026-10-16 用户要求）。
 *
 * 规则：默认 = 字形框原样；量到可信容器时绕字形框中点**对称**扩到容器内缩一圈；
 * 只扩不缩；容器不可信/量不到 → 不扩。位置（锚点=字形框中点）绝不挪。
 */
class BubbleFitBoxPolicyTest {

    private fun box(l: Float, t: Float, r: Float, b: Float) = Box(l, t, r, b)

    @Test
    fun nullContainerReturnsGlyphUnchanged() {
        val glyph = box(100f, 200f, 180f, 260f)
        val fit = BubbleFitBoxPolicy.expandSymmetrically(glyph, null, 4f)
        assertEquals(glyph, fit)
    }

    @Test
    fun roomyContainerExpandsSymmetricallyAndKeepsCenter() {
        // 字形框 100..180 x 200..260（中心 140,230）；容器 40..240 x 120..340
        // 内缩 4 → 44..236 x 124..336；近侧半宽 = min(140-44, 236-140) = 96
        // 近侧半高 = min(230-124, 336-230) = 106
        val glyph = box(100f, 200f, 180f, 260f)
        val container = box(40f, 120f, 240f, 340f)
        val fit = BubbleFitBoxPolicy.expandSymmetrically(glyph, container, 4f)
        assertEquals(140f, (fit.left + fit.right) / 2f, 0.01f)
        assertEquals(230f, (fit.top + fit.bottom) / 2f, 0.01f)
        assertEquals(44f, fit.left, 0.01f)
        assertEquals(236f, fit.right, 0.01f)
        assertEquals(124f, fit.top, 0.01f)
        assertEquals(336f, fit.bottom, 0.01f)
    }

    @Test
    fun asymmetricContainerLimitsByNearerSide() {
        // 容器左近右远：左 110（中心 140 → 30），右 400（260）→ 半宽 30（> 字形半宽 40？不，
        // 30 < 40 → 不会缩，保持字形半宽 40）。改一个更宽的容器：左 60（80）、右 400（260）
        // → 近侧 80 > 40 → 半宽 80。
        val glyph = box(100f, 200f, 180f, 260f)
        val fit = BubbleFitBoxPolicy.expandSymmetrically(glyph, box(60f, 140f, 400f, 360f), 0f)
        assertEquals(80f, fit.width / 2f, 0.01f)
        assertEquals(140f, (fit.left + fit.right) / 2f, 0.01f)
        // 近侧比字形半宽还小 → 保持字形（只扩不缩）
        val tight = BubbleFitBoxPolicy.expandSymmetrically(glyph, box(130f, 140f, 400f, 360f), 0f)
        assertEquals(glyph.width, tight.width, 0.01f)
        assertEquals(glyph.left, tight.left, 0.01f)
    }

    @Test
    fun insetShrinksContainerSoFitNeverTouchesOutline() {
        val glyph = box(100f, 200f, 180f, 260f)
        val container = box(40f, 120f, 240f, 340f)
        val tight = BubbleFitBoxPolicy.expandSymmetrically(glyph, container, 8f)
        assertEquals(48f, tight.left, 0.01f)
        assertEquals(232f, tight.right, 0.01f)
        assertEquals(128f, tight.top, 0.01f)
        assertEquals(332f, tight.bottom, 0.01f)
    }

    @Test
    fun degenerateOrInvalidInputsReturnGlyph() {
        val glyph = box(100f, 200f, 180f, 260f)
        assertEquals(glyph, BubbleFitBoxPolicy.expandSymmetrically(glyph, box(200f, 300f, 100f, 150f), 4f))
        assertEquals(glyph, BubbleFitBoxPolicy.expandSymmetrically(glyph, box(140f, 230f, 140.2f, 230.2f), 4f))
        val brokenGlyph = box(100f, 200f, 100.2f, 200.2f)
        assertEquals(brokenGlyph, BubbleFitBoxPolicy.expandSymmetrically(brokenGlyph, box(0f, 0f, 300f, 400f), 4f))
    }

    @Test
    fun insetLargerThanContainerReturnsGlyph() {
        val glyph = box(100f, 200f, 180f, 260f)
        // 内缩 60 → 容器 40..240 内缩后 100..180 仍有空间，但 40..44 内缩会让它退化
        val tiny = box(100f, 200f, 103f, 203f)
        assertEquals(glyph, BubbleFitBoxPolicy.expandSymmetrically(glyph, tiny, 4f))
    }

    // ------------------------------------------------------------------
    // credibleContainer：区域框当对话框用的两道闸（2026-10-16 第二级证据）
    // ------------------------------------------------------------------

    @Test
    fun regionContainerPassesWhenItWrapsGlyphWithinLimits() {
        // 真机样本（아.참 气泡）：字形 88×54，区域 246×178 = 2.8×/3.3× → 可用
        val glyph = box(100f, 200f, 188f, 254f)
        val region = box(60f, 130f, 306f, 308f)
        val ok = BubbleFitBoxPolicy.credibleContainer(glyph, region, 3.0f, 4.0f)
        assertEquals(region, ok)
    }

    @Test
    fun regionContainerRejectedWhenWayTooLarge() {
        // 真机事故形态：单字 chip 24×21 配到 597×587 的白色带（约 25 倍）→ 必须拒绝
        val glyph = box(324f, 75f, 348f, 96f)
        val region = box(0f, 0f, 597f, 587f)
        assertEquals(null, BubbleFitBoxPolicy.credibleContainer(glyph, region, 3.0f, 4.0f))
    }

    @Test
    fun regionContainerRejectedWhenItDoesNotWrapGlyph() {
        val glyph = box(100f, 200f, 180f, 260f)
        // 右侧没包住
        assertEquals(null, BubbleFitBoxPolicy.credibleContainer(glyph, box(60f, 130f, 170f, 300f), 3.0f, 4.0f))
        // 上方没包住
        assertEquals(null, BubbleFitBoxPolicy.credibleContainer(glyph, box(60f, 210f, 300f, 300f), 3.0f, 4.0f))
    }

    @Test
    fun regionContainerAcceptsExactGlyphBoxAsNoOp() {
        // 区域=字形框（外部旁白框的常见形态）→ 通过，但对称扩后不变（调用方会退化成原样）
        val glyph = box(100f, 200f, 180f, 260f)
        assertEquals(glyph, BubbleFitBoxPolicy.credibleContainer(glyph, glyph.copy(), 3.0f, 4.0f))
        val fit = BubbleFitBoxPolicy.expandSymmetrically(
            glyph,
            BubbleFitBoxPolicy.credibleContainer(glyph, glyph.copy(), 3.0f, 4.0f),
            4f
        )
        assertEquals(glyph, fit)
    }
}
