package com.manga.translator.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「框比文本矮」向下扩框的纯判据（[growBubbleBottomPx]）。
 *
 * 两条真机回归都在这里钉住：
 * - 2026-10-11 原始需求：多行台词框矮 → 必须向下扩（否则下半原文不擦）；
 * - 2026-10-12 用户截图：单行小字被旧版折行估算撑大 → 字号炸 4 倍 → 必须不扩。
 */
class BubbleBoxGrowerTest {

    @Test
    fun `single-line small bubble must not grow`() {
        // 真机 [14017366] b2：单行「그래,소꿉놀이나 다름없었다.」，框 316x44@2410，
        // 全页中位行高 36。旧版折成 2 行扩到 2596 → font_height_percent 4.5%（真值 1.04%）。
        val result = growBubbleBottomPx(
            rectLeft = 153f, rectTop = 2410f, rectRight = 469f, rectBottom = 2454f,
            hardLineCount = 1,
            medianLineHeightPx = 36f,
            stitchedHeightPx = 2544f,
            others = emptyList()
        )
        assertNull("单行小字气泡不允许被折行估算撑大", result)
    }

    @Test
    fun `short multi-line box grows to hardLineCount x lineHeight x 1_2`() {
        // 真机 [2026-10-11]：4 行台词、框 360x161@2312、行高 43 → 2312+206.4=2518。
        val result = growBubbleBottomPx(
            rectLeft = 120f, rectTop = 2312f, rectRight = 480f, rectBottom = 2473f,
            hardLineCount = 4,
            medianLineHeightPx = 43f,
            stitchedHeightPx = 2544f,
            others = listOf(SeamBox(left = 130f, top = 2520f, right = 470f, bottom = 2600f))
        )
        assertEquals(2518.4f, result!!, 0.01f)
    }

    @Test
    fun `growth is clamped to a below neighbour top`() {
        val result = growBubbleBottomPx(
            rectLeft = 120f, rectTop = 2312f, rectRight = 480f, rectBottom = 2473f,
            hardLineCount = 4,
            medianLineHeightPx = 50f,
            stitchedHeightPx = 2544f,
            others = listOf(SeamBox(left = 130f, top = 2520f, right = 470f, bottom = 2600f))
        )
        // 需要 2312+240=2552 > 2520 → 夹到邻居顶。
        assertEquals(2520f, result!!, 0.01f)
    }

    @Test
    fun `non neighbour below does not clamp`() {
        // 下方邻居横向几乎不重叠（< 0.3× 较窄者）→ 不算邻居，不夹。
        val result = growBubbleBottomPx(
            rectLeft = 120f, rectTop = 2312f, rectRight = 480f, rectBottom = 2473f,
            hardLineCount = 4,
            medianLineHeightPx = 45f,
            stitchedHeightPx = 2544f,
            others = listOf(SeamBox(left = 500f, top = 2490f, right = 700f, bottom = 2600f))
        )
        // 需要 2312+216=2528（未超图高），非邻居不夹 → 2528。
        assertEquals(2528f, result!!, 0.01f)
    }

    @Test
    fun `growth never passes the stitched bottom`() {
        val result = growBubbleBottomPx(
            rectLeft = 120f, rectTop = 2500f, rectRight = 480f, rectBottom = 2530f,
            hardLineCount = 4,
            medianLineHeightPx = 43f,
            stitchedHeightPx = 2544f,
            others = emptyList()
        )
        assertEquals(2544f, result!!, 0.01f)
    }

    @Test
    fun `degenerate inputs return null`() {
        assertNull(
            growBubbleBottomPx(0f, 0f, 1f, 10f, 2, 36f, 2544f, emptyList()) // 框宽 ≤2
        )
        assertNull(
            growBubbleBottomPx(0f, 0f, 100f, 10f, 0, 36f, 2544f, emptyList()) // 无硬行
        )
        assertNull(
            growBubbleBottomPx(0f, 0f, 100f, 10f, 2, 0f, 2544f, emptyList()) // 行高非法
        )
        // 框已经装得下（需要底 ≤ 框底+0.3 行高）→ 不扩。
        assertNull(
            growBubbleBottomPx(0f, 0f, 100f, 96f, 2, 36f, 2544f, emptyList())
        )
    }
}
