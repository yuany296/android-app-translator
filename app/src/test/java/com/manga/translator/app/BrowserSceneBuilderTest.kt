package com.manga.translator.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖层场景序列化的纯逻辑单测：坐标换算（拼接图 → 原图百分比）、颜色、JSON 转义。
 * 渲染本身在 WebView 的 mt-overlay-renderer.js 完成（纯逻辑已在 Node 冒烟测试覆盖）。
 */
class BrowserSceneBuilderTest {

    // ------------------------------------------------------------------
    // 坐标换算：拼接图（top 条带 + 原图）→ 原图百分比
    // ------------------------------------------------------------------

    @Test
    fun `stitched y converts to source percent`() {
        // 拼接图高 1200（顶条 200 + 原图 1000），气泡在拼接图 y=50%（=600px），
        // 原图内实际 y = 400px → 原图 40%。
        val y = BrowserSceneBuilder.stitchedYPercentToSource(50f, 1200, 200, 1000)
        assertEquals(40f, y, 0.01f)
    }

    @Test
    fun `stitched y at strip boundary maps to zero`() {
        // 拼接图 y 正好 = 顶条高度（原图顶边）→ 0%。
        val y = BrowserSceneBuilder.stitchedYPercentToSource(200f / 1200f * 100f, 1200, 200, 1000)
        assertEquals(0f, y, 0.01f)
    }

    @Test
    fun `stitched height percent scales by image ratio`() {
        // 拼接图 1200 高里占 20%（240px）→ 原图 1000 高里 24%。
        val h = BrowserSceneBuilder.stitchedHPercentToSource(20f, 1200, 1000)
        assertEquals(24f, h, 0.01f)
    }

    @Test
    fun `stitched rect px converts with strip offset`() {
        // 原图 800x1000，拼接图 800x1200（顶条 200）。内容框像素 (80, 300, 400, 160)
        // → 原图 y=100 → 百分比 x=10 y=10 w=50 h=16。
        val box = BrowserSceneBuilder.stitchedRectPxToSource(
            leftPx = 80f, topPx = 300f, widthPx = 400f, heightPx = 160f,
            stitchedWidthPx = 800, stitchedHeightPx = 1200, topStripHeightPx = 200,
            sourceWidthPx = 800, sourceHeightPx = 1000
        )
        assertEquals(10f, box[0], 0.01f)
        assertEquals(10f, box[1], 0.01f)
        assertEquals(50f, box[2], 0.01f)
        assertEquals(16f, box[3], 0.01f)
    }

    @Test
    fun `cross bubble rect allows negative y`() {
        // 跨图续排：内容框顶边在原图上方 50px（y=-5%），不允许收拢到 0。
        val box = BrowserSceneBuilder.stitchedRectPxToSource(
            leftPx = 0f, topPx = -50f, widthPx = 400f, heightPx = 160f,
            stitchedWidthPx = 800, stitchedHeightPx = 1000, topStripHeightPx = 0,
            sourceWidthPx = 800, sourceHeightPx = 1000,
            allowOverflowY = true
        )
        assertEquals(-5f, box[1], 0.01f)
    }

    @Test
    fun `cross bubble rect clamps y without overflow flag`() {
        val box = BrowserSceneBuilder.stitchedRectPxToSource(
            leftPx = 0f, topPx = -50f, widthPx = 400f, heightPx = 160f,
            stitchedWidthPx = 800, stitchedHeightPx = 1000, topStripHeightPx = 0,
            sourceWidthPx = 800, sourceHeightPx = 1000,
            allowOverflowY = false
        )
        assertEquals(0f, box[1], 0.01f)
    }

    // ------------------------------------------------------------------
    // 颜色
    // ------------------------------------------------------------------

    @Test
    fun `color to hex drops alpha and lowercases`() {
        assertEquals("#10141c", BrowserSceneBuilder.colorToHex(0xFF10141C.toInt()))
        assertEquals("#ffffff", BrowserSceneBuilder.colorToHex(0xFFFFFFFF.toInt()))
        assertEquals("#000000", BrowserSceneBuilder.colorToHex(0))
    }

    // ------------------------------------------------------------------
    // JSON 拼装
    // ------------------------------------------------------------------

    @Test
    fun `scene json contains required fields`() {
        val scene = BrowserSceneBuilder.Scene(
            cleanedUrl = "https://mtlocal.overlay/c1.jpg",
            bubbles = listOf(
                BrowserSceneBuilder.SceneBubble(
                    id = "b0",
                    x = 10f, y = 20f, w = 30f, h = 8f,
                    originalText = "안녕 \"하세요\"",
                    translatedText = "你好\n世界",
                    sourceLineCount = 2,
                    vertical = false,
                    alignment = "center",
                    bgType = "solid",
                    bgColorHex = "#ffffff",
                    textColorHex = "#111827",
                    strokeColorHex = "#ffffff",
                    fontHeightPercent = 3.2f
                )
            )
        )
        val json = BrowserSceneBuilder.buildSceneJson("mt7", scene)
        assertTrue(json.contains("\"image_id\":\"mt7\""))
        assertTrue(json.contains("\"cleaned_url\":\"https://mtlocal.overlay/c1.jpg\""))
        assertTrue(json.contains("\"original_text\":\"안녕 \\\"하세요\\\"\""))
        assertTrue(json.contains("\"translated_text\":\"你好\\n世界\""))
        assertTrue(json.contains("\"bg_type\":\"solid\""))
        assertTrue(json.contains("\"font_height_percent\":3.2"))
        assertTrue(json.contains("\"stitch_overflow\":false"))
        assertTrue(json.contains("\"patch_url\":null"))
    }

    @Test
    fun `scene json escapes control chars`() {
        val scene = BrowserSceneBuilder.Scene(
            cleanedUrl = null,
            bubbles = listOf(
                BrowserSceneBuilder.SceneBubble(
                    id = "b0", x = 0f, y = 0f, w = 10f, h = 10f,
                    originalText = "a\\b'c",
                    translatedText = "x",
                    sourceLineCount = 1,
                    vertical = false,
                    alignment = "left",
                    bgType = "none",
                    bgColorHex = "#ffffff",
                    textColorHex = "#000000",
                    strokeColorHex = "#ffffff",
                    fontHeightPercent = 1f
                )
            )
        )
        val json = BrowserSceneBuilder.buildSceneJson("m", scene)
        // 反斜杠与单引号都要安全落到 JS 字符串字面量里
        assertTrue(json.contains("\"original_text\":\"a\\\\b'c\""))
        assertTrue(json.contains("\"cleaned_url\":null"))
    }

    // ------------------------------------------------------------------
    // 渲染契约扩展字段（2026-09-29）：polygon / rotation_deg / fill_box /
    // region_polygon / cleaned_source_box / font_weight
    // ------------------------------------------------------------------

    @Test
    fun `scene json serializes quad geometry and fill box`() {
        val scene = BrowserSceneBuilder.Scene(
            cleanedUrl = null,
            bubbles = listOf(
                BrowserSceneBuilder.SceneBubble(
                    id = "b0", x = 20f, y = 20f, w = 30f, h = 10f,
                    originalText = "原",
                    translatedText = "译",
                    sourceLineCount = 1,
                    vertical = false,
                    alignment = "center",
                    bgType = "solid",
                    bgColorHex = "#ffeedd",
                    textColorHex = "#111827",
                    strokeColorHex = "#ffffff",
                    fontHeightPercent = 6f,
                    polygon = listOf(10f, 41f, 50f, 53f, 50f, 59f, 10f, 47f),
                    rotationDeg = -12.5f,
                    fillBox = floatArrayOf(10f, 12f, 60f, 20f),
                    regionPolygon = listOf(12f, 14f, 66f, 14f, 66f, 30f),
                    cleanedSourceBox = floatArrayOf(0f, 0f, 50f, 25f),
                    fontWeight = 700
                )
            )
        )
        val json = BrowserSceneBuilder.buildSceneJson("mt1", scene)
        assertTrue(json.contains("\"polygon\":[{\"x\":10,\"y\":41},{\"x\":50,\"y\":53},{\"x\":50,\"y\":59},{\"x\":10,\"y\":47}]"))
        assertTrue(json.contains("\"rotation_deg\":-12.5"))
        assertTrue(json.contains("\"fill_box\":{\"x\":10,\"y\":12,\"w\":60,\"h\":20}"))
        assertTrue(json.contains("\"region_polygon\":[{\"x\":12,\"y\":14},{\"x\":66,\"y\":14},{\"x\":66,\"y\":30}]"))
        assertTrue(json.contains("\"cleaned_source_box\":{\"x\":0,\"y\":0,\"w\":50,\"h\":25}"))
        assertTrue(json.contains("\"font_weight\":700"))
    }

    @Test
    fun `missing optional geometry serializes as null`() {
        val scene = BrowserSceneBuilder.Scene(
            cleanedUrl = null,
            bubbles = listOf(
                BrowserSceneBuilder.SceneBubble(
                    id = "b0", x = 0f, y = 0f, w = 10f, h = 10f,
                    originalText = "", translatedText = "", sourceLineCount = 1,
                    vertical = false, alignment = "center", bgType = "none",
                    bgColorHex = "#ffffff", textColorHex = "#000000", strokeColorHex = "#ffffff",
                    fontHeightPercent = 0f
                )
            )
        )
        val json = BrowserSceneBuilder.buildSceneJson("mt1", scene)
        assertTrue(json.contains("\"polygon\":null"))
        assertTrue(json.contains("\"rotation_deg\":null"))
        assertTrue(json.contains("\"fill_box\":null"))
        assertTrue(json.contains("\"region_polygon\":null"))
        assertTrue(json.contains("\"cleaned_source_box\":null"))
        assertTrue(json.contains("\"font_weight\":null"))
    }

    @Test
    fun `degenerate quad is dropped instead of emitting a bad polygon`() {
        // 点数不足（6 个数 = 3 点）或尺寸非法的框一律输出 null，渲染层走降级路径。
        val scene = BrowserSceneBuilder.Scene(
            cleanedUrl = null,
            bubbles = listOf(
                BrowserSceneBuilder.SceneBubble(
                    id = "b0", x = 0f, y = 0f, w = 10f, h = 10f,
                    originalText = "a", translatedText = "b", sourceLineCount = 1,
                    vertical = false, alignment = "center", bgType = "solid",
                    bgColorHex = "#ffffff", textColorHex = "#000000", strokeColorHex = "#ffffff",
                    fontHeightPercent = 1f,
                    polygon = listOf(1f, 2f, 3f, 4f, 5f, 6f),
                    fillBox = floatArrayOf(1f, 2f, 3f)
                )
            )
        )
        val json = BrowserSceneBuilder.buildSceneJson("mt1", scene)
        assertTrue(json.contains("\"polygon\":null"))
        assertTrue(json.contains("\"fill_box\":null"))
    }

    @Test
    fun `flatten quad keeps baseline order`() {
        val points = listOf(
            com.manga.translator.translate.QuadPoint(1f, 2f),
            com.manga.translator.translate.QuadPoint(3f, 4f),
            com.manga.translator.translate.QuadPoint(5f, 6f),
            com.manga.translator.translate.QuadPoint(7f, 8f)
        )
        assertEquals(listOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f), BrowserSceneBuilder.flattenQuad(points))
        assertEquals(null, BrowserSceneBuilder.flattenQuad(null))
        assertEquals(null, BrowserSceneBuilder.flattenQuad(emptyList()))
    }

    // ------------------------------------------------------------------
    // §11 跨页/分片：cover_segments + 整页清理图（三级背景降级）
    // ------------------------------------------------------------------

    @Test
    fun `scene json serializes cover segments and page cleaned url`() {
        val scene = BrowserSceneBuilder.Scene(
            cleanedUrl = "https://mtlocal.overlay/band.jpg",
            pageCleanedUrl = "https://mtlocal.overlay/page.jpg",
            bubbles = listOf(
                BrowserSceneBuilder.SceneBubble(
                    id = "b0", x = 20f, y = 20f, w = 30f, h = 10f,
                    originalText = "原", translatedText = "译", sourceLineCount = 1,
                    vertical = false, alignment = "center", bgType = "none",
                    bgColorHex = "#ffffff", textColorHex = "#000000", strokeColorHex = "#ffffff",
                    fontHeightPercent = 6f,
                    coverSegments = listOf(
                        BrowserSceneBuilder.CoverSegment(
                            pageId = "p1", x = 10f, y = 10f, w = 20f, h = 10f,
                            polygon = listOf(10f, 10f, 30f, 10f, 30f, 20f, 10f, 20f),
                            cleanedSourceBox = floatArrayOf(5f, 5f, 40f, 40f),
                            inBand = true
                        ),
                        BrowserSceneBuilder.CoverSegment(
                            pageId = "p2", x = 60f, y = 60f, w = 20f, h = 10f, inBand = false
                        )
                    )
                )
            )
        )
        val json = BrowserSceneBuilder.buildSceneJson("mt1", scene)
        assertTrue(json.contains("\"page_cleaned_url\":\"https://mtlocal.overlay/page.jpg\""))
        assertTrue(json.contains("\"page_id\":\"p1\""))
        assertTrue(json.contains("\"page_id\":\"p2\""))
        assertTrue(json.contains("\"cleaned_source_box\":{\"x\":5,\"y\":5,\"w\":40,\"h\":40}"))
        assertTrue(json.contains("\"in_band\":true"))
        assertTrue(json.contains("\"in_band\":false"))
        // 分段的四边形只在 4 点时才输出（与气泡 polygon 同一套退化规则）。
        assertTrue(
            json.contains(
                "\"polygon\":[{\"x\":10,\"y\":10},{\"x\":30,\"y\":10},{\"x\":30,\"y\":20},{\"x\":10,\"y\":20}]"
            )
        )
        // 第二段没有四边形 → null，渲染层退回外接框。
        assertTrue(json.contains("\"page_id\":\"p2\",\"x\":60,\"y\":60,\"w\":20,\"h\":10,\"polygon\":null"))
    }

    @Test
    fun `scene without cross page data keeps the legacy shape`() {
        val scene = BrowserSceneBuilder.Scene(
            cleanedUrl = null,
            bubbles = listOf(
                BrowserSceneBuilder.SceneBubble(
                    id = "b0", x = 0f, y = 0f, w = 10f, h = 10f,
                    originalText = "a", translatedText = "b", sourceLineCount = 1,
                    vertical = false, alignment = "center", bgType = "none",
                    bgColorHex = "#ffffff", textColorHex = "#000000", strokeColorHex = "#ffffff",
                    fontHeightPercent = 1f
                )
            )
        )
        val json = BrowserSceneBuilder.buildSceneJson("mt1", scene)
        // 没有分片信息 → 输出 null，渲染层走单块遮罩（节点结构与旧版完全一致）。
        assertTrue(json.contains("\"cover_segments\":null"))
        assertTrue(json.contains("\"page_cleaned_url\":null"))
    }

    @Test
    fun `degenerate cover segments are dropped`() {
        val scene = BrowserSceneBuilder.Scene(
            cleanedUrl = null,
            bubbles = listOf(
                BrowserSceneBuilder.SceneBubble(
                    id = "b0", x = 0f, y = 0f, w = 10f, h = 10f,
                    originalText = "a", translatedText = "b", sourceLineCount = 1,
                    vertical = false, alignment = "center", bgType = "none",
                    bgColorHex = "#ffffff", textColorHex = "#000000", strokeColorHex = "#ffffff",
                    fontHeightPercent = 1f,
                    coverSegments = listOf(
                        BrowserSceneBuilder.CoverSegment(
                            pageId = "p1", x = 1f, y = 2f, w = 3f, h = 4f,
                            polygon = listOf(1f, 2f, 3f, 4f, 5f, 6f)
                        )
                    )
                )
            )
        )
        val json = BrowserSceneBuilder.buildSceneJson("mt1", scene)
        // 3 点四边形非法 → null；分段本身仍保留（外接框可用）。
        assertTrue(json.contains("\"page_id\":\"p1\""))
        assertTrue(json.contains("\"polygon\":null"))
        assertTrue(json.contains("\"cleaned_source_box\":null"))
    }

    @Test
    fun `empty cover segments list serializes as null`() {
        val bubble = BrowserSceneBuilder.SceneBubble(
            id = "b0", x = 0f, y = 0f, w = 10f, h = 10f,
            originalText = "a", translatedText = "b", sourceLineCount = 1,
            vertical = false, alignment = "center", bgType = "none",
            bgColorHex = "#ffffff", textColorHex = "#000000", strokeColorHex = "#ffffff",
            fontHeightPercent = 1f,
            coverSegments = emptyList()
        )
        val json = BrowserSceneBuilder.buildSceneJson(
            "mt1", BrowserSceneBuilder.Scene(null, listOf(bubble))
        )
        assertTrue(json.contains("\"cover_segments\":null"))
    }

    // ------------------------------------------------------------------
    // 实底填充限幅（2026-09-29 用户反馈「覆盖层太大，超出气泡框」，且只在 ML Kit 下）
    // ------------------------------------------------------------------

    @Test
    fun `fill box within limits is kept as is`() {
        val text = floatArrayOf(20f, 20f, 30f, 10f)
        val fill = floatArrayOf(18f, 19f, 34f, 12f)
        val clamped = BrowserSceneBuilder.clampFillBox(text, fill)
        assertEquals(18f, clamped[0], 0.01f)
        assertEquals(19f, clamped[1], 0.01f)
        assertEquals(34f, clamped[2], 0.01f)
        assertEquals(12f, clamped[3], 0.01f)
    }

    @Test
    fun `oversized fill box is clamped to the text box plus margin`() {
        // ML Kit 实测：文字框 43% 宽，填充框却是 1.85 倍（占整幅 80% 宽）→ 必须收回来。
        val text = floatArrayOf(0f, 0f, 43f, 15f)
        val fill = floatArrayOf(-20f, -10f, 80f, 24f)
        val clamped = BrowserSceneBuilder.clampFillBox(text, fill)
        // 每边最多外扩 0.2×边长 → 左 -8.6，右 43+8.6=51.6，宽 60.2
        assertEquals(-8.6f, clamped[0], 0.05f)
        assertEquals(51.6f, clamped[0] + clamped[2], 0.05f)
        assertTrue("填充宽度必须明显小于原来的 80%", clamped[2] < 61f)
    }

    @Test
    fun `fill box never exceeds the configured expansion ratio`() {
        val text = floatArrayOf(10f, 10f, 20f, 8f)
        val clamped = BrowserSceneBuilder.clampFillBox(text, floatArrayOf(-100f, -100f, 300f, 300f))
        val ratio = BrowserSceneBuilder.MAX_FILL_EXPAND_RATIO
        assertEquals(10f - 20f * ratio, clamped[0], 0.01f)
        assertEquals(10f - 8f * ratio, clamped[1], 0.01f)
        assertEquals(30f + 20f * ratio, clamped[0] + clamped[2], 0.01f)
        assertEquals(18f + 8f * ratio, clamped[1] + clamped[3], 0.01f)
    }

    @Test
    fun `missing or inverted fill box falls back to the text box`() {
        val text = floatArrayOf(5f, 6f, 20f, 9f)
        assertEquals(text.toList(), BrowserSceneBuilder.clampFillBox(text, null).toList())
        // 完全落在框外（右/下都小于左/上）→ 退回文字框，绝不输出负尺寸
        val inverted = BrowserSceneBuilder.clampFillBox(text, floatArrayOf(200f, 200f, 5f, 5f))
        assertTrue(inverted[2] > 0f && inverted[3] > 0f)
    }

    // ------------------------------------------------------------------
    // 盖板外扩：ML Kit 路径「只加下沿」（2026-10-01 用户：「译文下面有时候会有一点点原文残留。
    // 所以感觉覆盖层需要扩大一点点。仅对于 ML Kit」）
    //
    // 两条必须守住的边界：① 下沿确实多留了几个像素（残留就露在那儿）；② 左右**一个像素都不能
    // 多**——用户早先投诉过「盖板框的宽度超出文字许多」，横向变宽会把那个问题带回来。
    // ------------------------------------------------------------------

    @Test
    fun `bottom pad only grows the bottom edge`() {
        // 原图 1000x2000，文字框 (100,200,200,100)；均匀 5px、下沿再补到 11px。
        val box = floatArrayOf(100f, 200f, 200f, 100f)
        val expanded = BrowserSceneBuilder.expandPercentBoxByPx(
            box, padPx = 5f, imageWidthPx = 1000, imageHeightPx = 2000, bottomPadPx = 11f
        )
        // 左右各 5px = 0.5%，上边 5px = 0.25%
        assertEquals(99.5f, expanded[0], 0.001f)
        assertEquals(199.75f, expanded[1], 0.001f)
        // 宽只多出左右各 5px；高多出上 5px + 下 11px
        assertEquals(201f, expanded[2], 0.001f)
        assertEquals(100f + 0.25f + 0.55f, expanded[3], 0.001f)
    }

    @Test
    fun `default bottom pad keeps the symmetric behaviour`() {
        val box = floatArrayOf(10f, 10f, 20f, 20f)
        val symmetric = BrowserSceneBuilder.expandPercentBoxByPx(box, 3f, 1000, 1000)
        val explicit = BrowserSceneBuilder.expandPercentBoxByPx(box, 3f, 1000, 1000, bottomPadPx = 3f)
        assertEquals(symmetric.toList(), explicit.toList())
        // 对称口径下：上边与下边各 3px（= 0.3%），宽高各多 6px
        assertEquals(20.6f, symmetric[2], 0.001f)
        assertEquals(20.6f, symmetric[3], 0.001f)
    }

    @Test
    fun `bottom pad never widens the cover horizontally`() {
        val box = floatArrayOf(30f, 40f, 25f, 12f)
        val base = BrowserSceneBuilder.expandPercentBoxByPx(box, 8f, 1080, 2400)
        val padded = BrowserSceneBuilder.expandPercentBoxByPx(box, 8f, 1080, 2400, bottomPadPx = 16f)
        assertEquals("左边不能动", base[0], padded[0], 0.0001f)
        assertEquals("上边不能动", base[1], padded[1], 0.0001f)
        assertEquals("宽度不能变（用户投诉过横向变宽）", base[2], padded[2], 0.0001f)
        assertTrue("只有高度变大", padded[3] > base[3])
        assertTrue("高度增加量正好是下沿多出的像素", padded[3] - base[3] < 0.4f)
    }

    @Test
    fun `degenerate input is returned unchanged`() {
        val box = floatArrayOf(1f, 2f, 3f, 4f)
        assertEquals(box.toList(), BrowserSceneBuilder.expandPercentBoxByPx(box, 0f, 100, 100).toList())
        assertEquals(box.toList(), BrowserSceneBuilder.expandPercentBoxByPx(box, 5f, 0, 100).toList())
        // 不足 4 个数（不是框）：原样返回，绝不越界或补 0。
        val short = floatArrayOf(1f, 2f)
        assertEquals(short.toList(), BrowserSceneBuilder.expandPercentBoxByPx(short, 5f, 100, 100).toList())
    }
}
