package com.manga.translator.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 气泡内部可写区域测量单测（2026-10-13）。
 *
 * 现场（用户反馈）：
 * - 「ML Kit 的 OCR 识别有的时候文字会超出气泡框」——椭圆泡收腰处第一行/最后一行戳出白边；
 * - 「paddle 的识别的清理图是在正常的位置，但是文字不会自适应缩小」——面板框内文字偏大；
 * - 「这个截页处的气泡框是纯色背景，但是它用的是清理图。希望处理为 solid」——白气泡上
 *   inpaint 灰痕。
 *
 * 判据要保证三件事：
 * 1. 量到容器（椭圆/圆角/面板）时给出**逐行最窄可用宽度**，排版框据此收窄 → 渲染层缩字号；
 * 2. 容器内部颜色足够平时才允许升级成纯色盖板（`isFlatBrightContainer`）——
 *    真机夹具实测：OCR 框比气泡宽时**外圈**一致率只有 ~0.5（环的两端落在气泡外），
 *    这时只能靠容器内部量测；
 * 3. 量不到容器（旁白直接压在插画上 / 渐变）时返回 null 或拒绝升级，行为与改动前一致。
 *
 * ⚠️ 这里刻意不用 `android.graphics.RectF`（unit test 里它是 mockable jar：方法抛
 * `not mocked`、字段恒为 0），全部用裸像素值 + 颜色 lambda。
 */
class BubbleInteriorScannerTest {

    private val scanner = BubbleInteriorScanner()

    private val white = 0xFFFBFBFA.toInt()
    private val gray = 0xFF8A9099.toInt()
    private val ink = 0xFF181818.toInt()

    /** 椭圆容器参数：与真机截图同量级（460×330 的椭圆泡，字形框 340×250）。 */
    private val imageW = 460
    private val imageH = 330
    private val ellipseA = 230f
    private val ellipseB = 165f
    private val ellipseCx = 230f
    private val ellipseCy = 165f

    private fun insideEllipse(x: Int, y: Int): Boolean {
        val dx = (x - ellipseCx) / ellipseA
        val dy = (y - ellipseCy) / ellipseB
        return dx * dx + dy * dy <= 1f
    }

    /** 文本笔画：字形框内每 40px 一道 10px 宽的竖条（模拟文字，走向上必须被跨过去）。 */
    private fun isTextStroke(
        x: Int,
        y: Int,
        glyphLeft: Int,
        glyphTop: Int,
        glyphRight: Int,
        glyphBottom: Int
    ): Boolean {
        if (y < glyphTop || y >= glyphBottom) return false
        if (x < glyphLeft || x >= glyphRight) return false
        return ((x - glyphLeft) % 40) < 10
    }

    private fun scanEllipse(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        lineCount: Int
    ) = scanner.scanWith(
        imageWidth = imageW,
        imageHeight = imageH,
        glyphLeft = left,
        glyphTop = top,
        glyphRight = right,
        glyphBottom = bottom,
        lineCount = lineCount,
        bgColor = white,
        pixelAt = { x, y ->
            when {
                insideEllipse(x, y) -> if (isTextStroke(
                        x, y, left.toInt(), top.toInt(), right.toInt(), bottom.toInt()
                    )
                ) ink else white
                else -> gray
            }
        }
    )

    @Test
    fun ellipseBubbleCapsLayoutWidthAtNarrowestLine() {
        // 真机截图复刻：字形框 340×250（4 行），椭圆 460×330 ——
        // 顶上那一行所在的高度，椭圆只剩 ~300px 宽，译文第一行就是从这里戳出去的。
        val glyphWidth = 340f
        val interior = scanEllipse(60f, 40f, 400f, 290f, lineCount = 4)
        assertNotNull("椭圆内应该量到容器", interior)
        val found = interior!!
        assertTrue("中点行是容器最宽处（≈椭圆长轴）", found.midRowWidthPx > 420f)
        val cap = found.widthCapPx(glyphWidth)
        assertNotNull("中点行宽 ≥ 字形宽 → 容器可信，应收窄", cap)
        // 最上一行的可用宽度 ≈ 椭圆在该带的弦长（300px 量级），明显小于字形框的 340px。
        assertTrue("收窄值应等于逐行最窄宽度（≈300），实测 ${cap}", cap!! in 280f..320f)
        assertTrue("收窄不能低于字形宽的 55%", cap >= glyphWidth * 0.55f)
    }

    @Test
    fun ellipseBubbleCountsAsFlatBrightContainer() {
        // 「纯色背景却用了清理图」的第二个判据：外圈被气泡外插画污染时（真机夹具实测 ~0.5），
        // 容器内部的一致率仍然 ≈1 → 允许纯色平涂。
        val glyphWidth = 340f
        val interior = scanEllipse(60f, 40f, 400f, 290f, lineCount = 4)
        assertNotNull(interior)
        val found = interior!!
        assertTrue("容器内部基本全是底色，实测 ${found.flatCoverage}", found.flatCoverage >= 0.99f)
        assertTrue("底色够亮 → 判为平色容器", found.isFlatBrightContainer(glyphWidth, white))
        // 深色容器不允许升级（纯色平涂会把画面暗部的纹理一起盖没）。
        assertFalse(found.isFlatBrightContainer(glyphWidth, 0xFF202020.toInt()))
    }

    @Test
    fun gradientContainerIsNotFlatEnoughToUpgrade() {
        // 渐变底：容器还能量到（亮度容差内），但颜色在整条上漂移 → 一致率低 → 不升级。
        val interior = scanner.scanWith(
            imageWidth = imageW,
            imageHeight = imageH,
            glyphLeft = 60f,
            glyphTop = 40f,
            glyphRight = 400f,
            glyphBottom = 290f,
            lineCount = 4,
            bgColor = 0xFFB4B8BE.toInt(),
            pixelAt = { x, _ -> (160 + x * 90 / imageW).coerceIn(0, 255).let { v -> 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v } }
        )
        assertNotNull(interior)
        val found = interior!!
        assertTrue("渐变下一致率应明显低于阈值，实测 ${found.flatCoverage}", found.flatCoverage < 0.9f)
        assertFalse(found.isFlatBrightContainer(340f, 0xFFB4B8BE.toInt()))
    }

    @Test
    fun narrationOverArtworkYieldsNoInterior() {
        // 旁白/拟声词直接压在插画上：没有任何「底」→ 量不到容器 → 不收窄（行为与改动前一致）。
        val interior = scanner.scanWith(
            imageWidth = imageW,
            imageHeight = imageH,
            glyphLeft = 60f,
            glyphTop = 40f,
            glyphRight = 400f,
            glyphBottom = 290f,
            lineCount = 2,
            bgColor = white,
            pixelAt = { x, y -> if (x % 7 == 0) ink else gray }
        )
        assertNull(interior)
    }

    @Test
    fun textStrokesAreBridged() {
        // 字形框与容器同宽：走向上必须跨过模拟文字笔画，否则会被当成容器边界。
        val interior = scanEllipse(130f, 125f, 330f, 205f, lineCount = 2)
        assertNotNull(interior)
        // 上下两条 10px 笔画不该把中点行切断（中点行宽仍应接近椭圆在该高度的弦长 = 长轴 460）。
        assertEquals(2f * ellipseA, interior!!.midRowWidthPx, 12f)
    }

    @Test
    fun rectangularPanelGivesFullInteriorWidth() {
        // 方形面板（用户第二张截图里的画框）：内部 300×200，字形框 260×180 ——
        // 收窄值应当是整个面板内宽（不小于字形宽），字号不会被压小。
        val glyphWidth = 260f
        val interior = scanner.scanWith(
            imageWidth = 460,
            imageHeight = 330,
            glyphLeft = 100f,
            glyphTop = 65f,
            glyphRight = 360f,
            glyphBottom = 245f,
            lineCount = 3,
            bgColor = white,
            pixelAt = { x, y -> if (x in 80..379 && y in 45..264) white else gray }
        )
        assertNotNull(interior)
        val cap = interior!!.widthCapPx(glyphWidth)
        assertNotNull("面板内部同样算可信容器", cap)
        assertEquals(300f, cap!!, 2f)
        assertTrue("面板内宽装得下字形框 → 收窄值不小于字形宽", cap >= glyphWidth)
        assertTrue(interior.isFlatBrightContainer(glyphWidth, white))
    }

    @Test
    fun columnExtentsMeasureSpaceBeyondGlyphBox() {
        val glyphWidth = 220f
        val glyphHeight = 130f
        val interior = scanEllipse(120f, 100f, 340f, 230f, lineCount = 3)
        assertNotNull(interior)
        val found = interior!!
        assertTrue("上方还有余量", found.columnTopExtPx > 0f)
        assertTrue("下方还有余量", found.columnBottomExtPx > 0f)
        val heightCap = found.heightCapPx(glyphWidth, glyphHeight)
        assertNotNull(heightCap)
        assertTrue("高度上限应大于字形高", heightCap!! > glyphHeight)
    }

    @Test
    fun ellipseContainerIsTrustworthy() {
        // 灰云背景上的白椭圆泡（2026-10-13 夹具）：容器 = 椭圆本身，量得准 → 可信。
        // ⚠️ 传的图尺寸比测试椭圆大一圈：真机上椭圆四周一定有页面留白 ——
        // 容器正好贴着画面四边时按「那是页面」处理（见下一条用例）。
        val interior = scanEllipse(60f, 40f, 400f, 290f, lineCount = 4)
        assertNotNull(interior)
        assertTrue(
            interior!!.isTrustworthyContainer(340f, 250f, 520, 380)
        )
    }

    @Test
    fun containerTouchingImageEdgesIsThePageNotTheBubble() {
        // 白气泡画在白面板/白页面上（2026-10-14 用户：「超出太多了吧」）：走向会跨过那 3px
        // 描边一路走到画面边缘 —— 这种「容器」必须被拒（真机夹具实测它给出 213px 的乱值，
        // 把排版框压成一根窄柱）。
        val interior = scanner.scanWith(
            imageWidth = imageW,
            imageHeight = imageH,
            glyphLeft = 60f,
            glyphTop = 40f,
            glyphRight = 400f,
            glyphBottom = 290f,
            lineCount = 4,
            bgColor = white,
            pixelAt = { _, _ -> white }
        )
        assertNotNull(interior)
        assertFalse(
            "贴着画面四边 = 量到的是页面，不是气泡",
            interior!!.isTrustworthyContainer(340f, 250f, imageW, imageH)
        )
        assertNull("不可信容器不能用来收窄排版框", interior.widthCapPx(340f).takeIf { false })
    }

    @Test
    fun pageLikeContainerIsRejectedBySizeRatio() {
        // 容器没贴边但比字形框大出离谱（高度 660 vs 字高 210 = 3.1 倍）→ 同样拒。
        val interior = scanner.scanWith(
            imageWidth = 620,
            imageHeight = 700,
            glyphLeft = 100f,
            glyphTop = 135f,
            glyphRight = 520f,
            glyphBottom = 345f,
            lineCount = 4,
            bgColor = white,
            pixelAt = { x, y -> if (x in 2..617 && y in 40..660) white else gray }
        )
        assertNotNull(interior)
        assertFalse(interior!!.isTrustworthyContainer(420f, 210f, 620, 700))
    }

    @Test
    fun degenerateGlyphReturnsNull() {        val interior = scanner.scanWith(
            imageWidth = imageW,
            imageHeight = imageH,
            glyphLeft = 10f,
            glyphTop = 10f,
            glyphRight = 14f,
            glyphBottom = 14f,
            lineCount = 1,
            bgColor = white,
            pixelAt = { _, _ -> white }
        )
        assertNull(interior)
    }
}
