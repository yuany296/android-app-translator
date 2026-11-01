package com.manga.translator.app

import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manga.translator.overlay.BubbleColorExtractor
import com.manga.translator.translate.Bubble
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 纯色气泡颜色夹具（2026-11-01，P1）。
 *
 * 用一张程序生成的 400×400 PNG（灰页 + 白气泡 + 黑字）把 `BubbleColorExtractor`
 * 的真实 Bitmap 路径跑一遍：容器边带应采到白色、墨色应为黑、样本数应足够。
 * 之前只有 `BubbleColorRulesTest` 的纯函数测试，Bitmap 采样链没有夹具。
 */
@RunWith(AndroidJUnit4::class)
class BubbleColorFixtureTest {

    @Test
    fun solidWhiteBubble_samplesContainerBandAndBlackInk() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val bitmap = context.assets.open("fixtures/solid_bubble.png").use { input ->
            BitmapFactory.decodeStream(input)
        }
        requireNotNull(bitmap) { "solid_bubble.png 解码失败" }

        val bubble = Bubble(
            x = 10f,
            y = 10f,
            w = 80f,
            h = 80f,
            bgType = "solid",
            originalText = "테스트\n말풍선",
            translatedText = "",
            contentX = 25f,
            contentY = 35f,
            contentW = 50f,
            contentH = 30f,
            sourceLineCount = 2
        )

        val colors = BubbleColorExtractor().extract(bitmap, bubble)

        assertEquals("白气泡的底色应采到纯白", Color.WHITE, colors.bgColor)
        assertTrue(
            "黑字的文字色应接近黑色，实际=${Integer.toHexString(colors.textColor)}",
            Color.red(colors.textColor) < 12 &&
                Color.green(colors.textColor) < 12 &&
                Color.blue(colors.textColor) < 12
        )
        assertTrue("容器边带应有足够样本，实际=${colors.bgSamples}", colors.bgSamples >= 32)
        assertTrue("墨色应有足够样本，实际=${colors.inkSamples}", colors.inkSamples >= 16)
        assertTrue("白底应判定为平底色，coverage=${colors.bgCoverage}", colors.flatBackground)
    }
}
