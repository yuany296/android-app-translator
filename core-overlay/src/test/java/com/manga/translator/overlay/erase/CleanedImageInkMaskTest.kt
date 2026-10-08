package com.manga.translator.overlay.erase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 清理图墨迹 mask 判据单测（2026-09-29）。
 *
 * 回归背景：`paintInkMask` 原来只看**亮度差**（阈值 46）。粉色旁白 `#e0447c`
 * 的亮度是 121，它所在画面的亮度是 153，只差 32 → 判成「不是字」→ mask 为空 →
 * inpaint 什么都没改 → 导出的清理图与原图**逐像素相同**，用户看到的就是
 * 「粉色的后面没有清理图，我还看得到文字」。
 *
 * 这里用真实色值把「RGB 距离能判出彩色字」钉死。
 */
class CleanedImageInkMaskTest {

    private val pinkStroke = 0xFFE0447C.toInt()      // 原图粉字
    private val midtoneBackdrop = 0xFFA88DB3.toInt() // 它压着的中间调画面（luma≈153）

    @Test
    fun colouredStrokeOnMidtoneBackgroundIsDetectedAsInk() {
        // 前提校验：这两个颜色的**亮度**很接近（旧判据必漏），但 RGB 距离很大。
        val strokeLuma = luma(pinkStroke)
        val backgroundLuma = luma(midtoneBackdrop)
        assertTrue(
            "前提：亮度差应小于旧的 46 阈值（实为 ${kotlin.math.abs(strokeLuma - backgroundLuma)}）",
            kotlin.math.abs(strokeLuma - backgroundLuma) < 46f
        )
        assertTrue("前提：RGB 距离应明显大于阈值", rgbDistance(pinkStroke, midtoneBackdrop) > INK_RGB_DISTANCE)

        val pixels = intArrayOf(pinkStroke, midtoneBackdrop, pinkStroke, midtoneBackdrop)
        val mask = buildInkMaskData(pixels, midtoneBackdrop)
        assertEquals(4, mask.size)
        assertNotEquals("粉字像素必须进 mask", 0, mask[0].toInt())
        assertNotEquals("粉字像素必须进 mask", 0, mask[2].toInt())
        assertEquals("背景像素不能进 mask（否则整块被糊掉）", 0, mask[1].toInt())
        assertEquals(0, mask[3].toInt())
    }

    @Test
    fun plainDarkTextOnWhiteIsStillDetected() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        val mask = buildInkMaskData(intArrayOf(black, white), white)
        assertNotEquals(0, mask[0].toInt())
        assertEquals(0, mask[1].toInt())
    }

    @Test
    fun lightTextOnDarkBackgroundIsDetected() {
        val light = 0xFFE6E2DB.toInt()
        val dark = 0xFF1A1A2E.toInt()
        val mask = buildInkMaskData(intArrayOf(light, dark), dark)
        assertNotEquals(0, mask[0].toInt())
        assertEquals(0, mask[1].toInt())
    }

    @Test
    fun subtleAntiAliasingNearTheThresholdStaysOut() {
        // 与背景差 20 的轻微噪点不该被打成笔画（阈值 45），否则背景会被越擦越糊。
        val near = 0xFFB89BC0.toInt()
        assertTrue(rgbDistance(near, midtoneBackdrop) < INK_RGB_DISTANCE)
        val mask = buildInkMaskData(intArrayOf(near), midtoneBackdrop)
        assertEquals(0, mask[0].toInt())
    }

    @Test
    fun emptyInputIsHandled() {
        assertEquals(0, buildInkMaskData(IntArray(0), midtoneBackdrop).size)
    }

    // ------------------------------------------------------------------
    // 外框保护可用性（2026-09-30）
    //
    // 回归背景：`build()` 里 `coerceAtLeast(outer.top + BUBBLE_BORDER_GUARD_PX)` 用区域框
    // 给「外扩」设上限，但区域框可能是**假阳性亮斑**（整片浅色页面）。实测某页斜排文字在
    // `y 929..1066`、区域却在 `y 981..1399` → 上边界被推到文字框下方 56px，
    // 斜排文字左上角那半截从来没进 mask → 清理图里留着半截原字。
    // ------------------------------------------------------------------

    @Test
    fun outerBoxThatDoesNotContainTheTextIsNotUsableAsGuard() {
        // 实测值：内容框 y 929..1066，假阳性区域 y 981..1399（区域在文字**下方**）。
        val usable = isOuterGuardUsable(
            outerLeft = 150, outerTop = 981, outerRight = 750, outerBottom = 1399,
            contentLeft = 206, contentTop = 929, contentRight = 489, contentBottom = 1065
        )
        assertTrue("区域不含文字 → 不能拿它收边（否则半截原字擦不掉）", !usable)
    }

    @Test
    fun outerBoxThatFullyContainsTheTextIsUsable() {
        // 真实白色椭圆：区域 y 185..363，两条文字 y 194..239 / 302..353。
        val usable = isOuterGuardUsable(
            outerLeft = 100, outerTop = 185, outerRight = 700, outerBottom = 363,
            contentLeft = 261, contentTop = 194, contentRight = 541, contentBottom = 353
        )
        assertTrue("区域包住文字 → 正常收边，保住气泡描边不被 inpaint 掉", usable)
    }

    @Test
    fun outerBoxTouchingTheContentBoundaryIsStillUsable() {
        val usable = isOuterGuardUsable(
            outerLeft = 206, outerTop = 929, outerRight = 489, outerBottom = 1065,
            contentLeft = 206, contentTop = 929, contentRight = 489, contentBottom = 1065
        )
        assertTrue("恰好重合 = 没有多余余量但也没切掉字形，允许使用", usable)
    }

    private fun luma(color: Int): Float =
        ((color shr 16) and 0xFF) * 0.299f +
            ((color shr 8) and 0xFF) * 0.587f +
            (color and 0xFF) * 0.114f

    private fun rgbDistance(first: Int, second: Int): Float {
        val dr = ((first shr 16) and 0xFF) - ((second shr 16) and 0xFF)
        val dg = ((first shr 8) and 0xFF) - ((second shr 8) and 0xFF)
        val db = (first and 0xFF) - (second and 0xFF)
        return kotlin.math.sqrt((dr * dr + dg * dg + db * db).toFloat())
    }
}
