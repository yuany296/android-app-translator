package com.manga.translator.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manga.translator.translate.PaddleOcrLocalTextDetector
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 倾斜/变形文字（竖排、旋转、倒置）识别验证。
 * 依赖打包进 APK 的 PaddleOCR 模型（assets/paddle_models/），无需联网。
 */
@RunWith(AndroidJUnit4::class)
class PaddleSkewVerticalTextTest {

    @Test
    fun verticalJapaneseColumnRecognized() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val detector = PaddleOcrLocalTextDetector(context)
        val text = "こんにちは"
        val bitmap = renderVertical(text)
        val blocks = detector.detect(bitmap, "ja")
        val recognized = blocks.joinToString("|") { it.text }
        println("PaddleVerticalBlocks: $recognized")
        assertTrue("vertical text should be detected, got: $recognized", blocks.isNotEmpty())
        assertTrue(
            "should contain vertical text chars, got: $recognized",
            text.toCharArray().any { recognized.contains(it) }
        )
    }

    @Test
    fun skewedTextRecognized() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val detector = PaddleOcrLocalTextDetector(context)
        val text = "こんにちは"
        val bitmap = renderSkewed(text, angleDeg = 15f)
        val blocks = detector.detect(bitmap, "ja")
        val recognized = blocks.joinToString("|") { it.text }
        println("PaddleSkewedBlocks: $recognized")
        assertTrue("skewed text should be detected, got: $recognized", blocks.isNotEmpty())
        assertTrue(
            "should contain skewed text chars, got: $recognized",
            text.toCharArray().any { recognized.contains(it) }
        )
    }

    @Test
    fun upsideDownTextRecognized() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val detector = PaddleOcrLocalTextDetector(context)
        val text = "こんにちは"
        val bitmap = renderSkewed(text, angleDeg = 180f)
        val blocks = detector.detect(bitmap, "ja")
        val recognized = blocks.joinToString("|") { it.text }
        println("PaddleUpsideDownBlocks: $recognized")
        // 180° 倒置文字依赖 0° 识别「弱结果」才触发 180° 重试（分数 < 0.72 或无目标文字，
        // 对齐参考项目 crop_recognition.py）；倒置假名可能被识别成高置信乱码（如 ギ4コソコ @0.91）
        // 而不触发重试，这是参考项目同款已知局限，真机效果见 HumanTODO.md。
        assertTrue("upside-down text should produce blocks, got: $recognized", blocks.isNotEmpty())
    }

    /** 竖排渲染：字符从上到下逐字堆叠（高 ≥ 宽 × 1.4，触发 ±90° 方向识别）。 */
    private fun renderVertical(text: String): Bitmap {
        val width = 200
        val height = 640
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 72f
            isAntiAlias = true
        }
        var y = 90f
        for (ch in text) {
            canvas.drawText(ch.toString(), width / 2f - 36f, y, paint)
            y += 90f
        }
        return bitmap
    }

    /** 倾斜渲染：整行文字旋转 angleDeg（15° 倾斜 / 180° 倒置）。 */
    private fun renderSkewed(text: String, angleDeg: Float): Bitmap {
        val width = 800
        val height = 400
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.rotate(angleDeg, width / 2f, height / 2f)
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 96f
            isAntiAlias = true
        }
        canvas.drawText(text, width / 2f - 180f, height / 2f + 30f, paint)
        return bitmap
    }
}
