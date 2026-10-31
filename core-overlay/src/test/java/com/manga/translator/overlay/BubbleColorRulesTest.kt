package com.manga.translator.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 气泡配色规则单测（2026-09-28，对齐 C:\homework\translator `rendering/bubble-colors.js`）。
 *
 * 用户要求「字体颜色尽量和原来一样」：对比度 ≥ 4.5 **或彩色（色度 ≥ 40）** 就保留原墨色，
 * 否则按对比度在黑/白里选。
 */
class BubbleColorRulesTest {

    private val extractor = BubbleColorExtractor()

    @Test
    fun highContrastInkIsKept() {
        // 黑字白底：对比度足够 → 原色
        assertEquals(0xFF000000.toInt(), extractor.chooseReadableTextColor(0xFF000000.toInt(), 0xFFFFFFFF.toInt()))
    }

    @Test
    fun coloredInkIsKeptEvenWhenBrightnessIsClose() {
        // 蓝色字压在中灰蓝底上：亮度接近（对比度很低），但色度足够 → 仍保留原色
        val blue = 0xFF1E6BFF.toInt()
        val greyBlue = 0xFF3A4A66.toInt()
        assertTrue("前提：这组颜色对比度确实低", extractor.colorChroma(blue) >= 40)
        assertEquals(blue, extractor.chooseReadableTextColor(blue, greyBlue))
    }

    @Test
    fun greyInkWithLowContrastFallsBackToBlackOrWhite() {
        // 中灰字压中灰底：无彩色 + 对比度不足 → 按对比度取黑/白
        val result = extractor.chooseReadableTextColor(0xFF808080.toInt(), 0xFF8A8A8A.toInt())
        assertTrue(result == 0xFF000000.toInt() || result == 0xFFFFFFFF.toInt())
    }

    @Test
    fun colorChromaMatchesChannelSpread() {
        assertEquals(0, extractor.colorChroma(0xFF808080.toInt()))
        assertEquals(255, extractor.colorChroma(0xFFFF0000.toInt()))
        assertEquals(100, extractor.colorChroma(0xFF64C864.toInt()))
    }

    // ------------------------------------------------------------------
    // 采样密度（2026-10-31 用户反馈「有的时候颜色会不对」）
    //
    // ⚠️ 历史实现墨色步长是 `面积 / 8192`，实际采样数只有 ≈ 8192²/面积：
    // 640×420 的框只有 ~280 个采样位置、几十个墨色像素，颜色会被抗锯齿/噪声带偏；
    // 外圈采样超上限时直接截断，大框只剩上边一条边。这两条锁住修复后的口径。
    // ------------------------------------------------------------------

    @Test
    fun inkSampleStepKeepsSamplingDenseForLargeBoxes() {
        // 小框逐像素（与历史行为一致）
        assertEquals(1, extractor.inkSampleStep(90, 90))
        // 100×100 刚过上限：步长 2（2500 个采样点，仍远高于旧的「面积/8192」算法）
        assertEquals(2, extractor.inkSampleStep(100, 100))
        // 640×420：sqrt(268800/8192) = 5.73 → 6，采样位置 ≈ 106×70 = 7420（历史是 20×14）
        assertEquals(6, extractor.inkSampleStep(640, 420))
        // 1080×1920（整页长条）：约 16px 一格，仍能覆盖上万像素里的细笔画
        val tall = extractor.inkSampleStep(1080, 1920)
        assertTrue("长条图步长过大：$tall", tall in 1..24)
        assertTrue(
            "大框采样位置应接近上限",
            (1080 / tall) * (1920 / tall) <= 8192 * 2
        )
    }

    @Test
    fun ringSamplingStrideSpreadsOverAllFourEdges() {
        // 640×420 框（8% 环厚 33×12）：环像素 43080，按 6 取 1 → 7180 个样本
        val stride = extractor.ringSampleStride(43080)
        assertEquals(6, stride)
        assertTrue(43080 / stride <= 8192)
        // 小框不抽样，保持逐像素
        assertEquals(1, extractor.ringSampleStride(2000))
    }

    // ------------------------------------------------------------------
    // 实底气泡的容器边带（2026-11-01）：底色不再只从文字框外圈采。
    // ------------------------------------------------------------------

    @Test
    fun solidBackgroundBandExcludesTextBoxAndKeepsMargin() {
        // 容器 100..500，文字框 180..420：内缩 8%+8px = 32px → 边带 132..468；
        // 文字框外扩 2px 后 178..422，边带仍有足够面积。
        val boxes = extractor.solidBackgroundBandBoxes(
            contentLeft = 180, contentTop = 180, contentRight = 420, contentBottom = 420,
            regionLeft = 100, regionTop = 100, regionRight = 500, regionBottom = 500
        )
        assertTrue(boxes != null)
        val b = boxes!!
        assertEquals(132, b[0])
        assertEquals(132, b[1])
        assertEquals(468, b[2])
        assertEquals(468, b[3])
        assertEquals(178, b[4])
        assertEquals(178, b[5])
        assertEquals(422, b[6])
        assertEquals(422, b[7])
    }

    @Test
    fun solidBackgroundBandFallsBackWhenTextBoxFillsContainer() {
        // 文字框几乎填满容器（只看得到 8px 内缩）：没有可靠边带 → null，调用方回退文字框环。
        val boxes = extractor.solidBackgroundBandBoxes(
            contentLeft = 100, contentTop = 100, contentRight = 300, contentBottom = 300,
            regionLeft = 100, regionTop = 100, regionRight = 300, regionBottom = 300
        )
        assertEquals(null, boxes)
    }

    @Test
    fun saturatedColorKeepsOriginalEvenOnWhiteBackground() {
        // 白底上的蓝字：对比度本就够 → 原色
        val blue = 0xFF2563EB.toInt()
        assertEquals(blue, extractor.chooseReadableTextColor(blue, 0xFFFFFFFF.toInt()))
    }

    // ------------------------------------------------------------------
    // 墨色量化桶值域（2026-09-29 用户反馈「粉红色的字没有变红」的回归锁）
    //
    // 历史 bug：桶数组只开了 16384 个格子（应是 64³ = 262144），配合越界守卫等于
    // 丢弃所有红通道 >= 16 的像素 —— 彩色墨色永远统计不到，一律退化成 #181818。
    // 这里的断言保证「任何 RGB 都落在桶数组范围内」，把这类静默截断钉死。
    // ------------------------------------------------------------------

    @Test
    fun everyRgbMapsInsideTheInkBucketArray() {
        val bucketCount = INK_BUCKET_COUNT
        assertEquals(64 * 64 * 64, bucketCount)
        for (r in 0..255 step 17) {
            for (g in 0..255 step 17) {
                for (b in 0..255 step 17) {
                    val index = extractor.inkBucketIndex(r, g, b)
                    assertTrue(
                        "RGB($r,$g,$b) 的桶下标 $index 越界（桶总数 $bucketCount）",
                        index >= 0 && index < bucketCount
                    )
                }
            }
        }
    }

    @Test
    fun saturatedColorsGetTheirOwnBuckets() {
        // 粉字（用户反馈的那个颜色）与深蓝字必须落在不同的桶里，且都不是「近黑桶」。
        val pink = extractor.inkBucketIndex(0xE0, 0x44, 0x7C)
        val darkBlue = extractor.inkBucketIndex(0x14, 0x3C, 0x6E)
        val black = extractor.inkBucketIndex(0x02, 0x02, 0x02)
        assertTrue("粉字桶下标不能是近黑桶", pink != black)
        assertTrue("深蓝字桶下标不能是近黑桶", darkBlue != black)
        assertTrue("粉字必须落在红通道高位的桶区", pink > 16384)
    }

    // ------------------------------------------------------------------
    // 原文自带描边的识别（2026-09-30）：用户要求「除了背景复杂，还有就是原文有的时候，
    // 也需要黑边白边」——译文风格必须跟原文一致，不能被「背景恰好单色」吞掉。
    // ------------------------------------------------------------------

    @Test
    fun plainDeepColoredTextHasNoOutline() {
        // 真机素材实测值（斜排韩文旁白，深蓝 #163E6E 压在浅蓝渐变上）：
        // 墨迹 3808 个像素里近白 0、近黑 0 → 原文没有描边，译文也不该有。
        val sampling = BubbleColorExtractor.InkSampling(
            color = 0xFF163E6E.toInt(),
            inkPixels = 3808,
            nearWhitePixels = 0,
            nearBlackPixels = 0
        )
        assertTrue(
            "纯深色字一个近白像素都没有，不能判成有白边",
            !extractor.originalHadOutline(sampling, 0xFF163E6E.toInt())
        )
    }

    @Test
    fun darkTextWithWhiteHaloCountsAsOutline() {
        val sampling = BubbleColorExtractor.InkSampling(
            color = 0xFF163E6E.toInt(),
            inkPixels = 1000,
            nearWhitePixels = 250,   // 白边环占墨迹 25%
            nearBlackPixels = 0
        )
        assertTrue(
            "白边环占比两成以上 → 原文有白边",
            extractor.originalHadOutline(sampling, 0xFF163E6E.toInt())
        )
    }

    @Test
    fun pureWhiteTextIsNotMistakenForWhiteOutline() {
        // 白字本身也是「近白一大批」，但那是字身不是描边 —— 用「文字色是不是近白」排除。
        val sampling = BubbleColorExtractor.InkSampling(
            color = 0xFFFFFFFF.toInt(),
            inkPixels = 1000,
            nearWhitePixels = 900,
            nearBlackPixels = 0
        )
        assertTrue(
            "白字不能因为「近白多」被当成有白边",
            !extractor.originalHadOutline(sampling, 0xFFFFFFFF.toInt())
        )
    }

    @Test
    fun whiteTextWithBlackOutlineCountsAsOutline() {
        // 漫画旁白常见组合：白字 + 黑描边。白字那一侧被排除，但黑边那一侧必须命中。
        val sampling = BubbleColorExtractor.InkSampling(
            color = 0xFFFFFFFF.toInt(),
            inkPixels = 1000,
            nearWhitePixels = 700,
            nearBlackPixels = 250
        )
        assertTrue(
            "白字 + 黑描边要能被识别出来（否则译文的黑边会被吞掉）",
            extractor.originalHadOutline(sampling, 0xFFFFFFFF.toInt())
        )
    }

    @Test
    fun blackTextWithWhiteOutlineCountsAsOutline() {
        val sampling = BubbleColorExtractor.InkSampling(
            color = 0xFF111111.toInt(),
            inkPixels = 1000,
            nearWhitePixels = 300,
            nearBlackPixels = 600
        )
        assertTrue(
            "黑字 + 白描边：白边侧命中",
            extractor.originalHadOutline(sampling, 0xFF111111.toInt())
        )
    }

    @Test
    fun outlineNeedsEnoughInkToJudge() {
        // 墨迹样本太少时不下「有描边」的结论（免得几个噪点就把描边打开）。
        val sampling = BubbleColorExtractor.InkSampling(
            color = 0xFF163E6E.toInt(),
            inkPixels = 4,
            nearWhitePixels = 4,
            nearBlackPixels = 0
        )
        assertTrue(!extractor.originalHadOutline(sampling, 0xFF163E6E.toInt()))
    }

    @Test
    fun deepColoredTextIsNotNearBlack() {
        // ⚠️ 深蓝字 #163E6E（max 通道 110）绝不能被算成「近黑」，否则会被当成黑描边。
        assertTrue(!extractor.isNearBlackPixel(0x16, 0x3E, 0x6E))
        // 中性深灰才是近黑
        assertTrue(extractor.isNearBlackPixel(0x20, 0x20, 0x20))
        // 深红也不该算近黑（色度高）
        assertTrue(!extractor.isNearBlackPixel(0x60, 0x10, 0x10))
    }

    @Test
    fun nearWhiteUsesChannelFloorAndChroma() {
        assertTrue(extractor.isNearWhitePixel(0xFF, 0xFF, 0xFF))
        assertTrue(extractor.isNearWhitePixel(0xD8, 0xE0, 0xE4))
        // 灰到 180 就不算近了
        assertTrue(!extractor.isNearWhitePixel(0xB4, 0xB4, 0xB4))
        // 够亮但有色（浅蓝底）也不算
        assertTrue(!extractor.isNearWhitePixel(0xC8, 0xE0, 0xFF))
    }
}
