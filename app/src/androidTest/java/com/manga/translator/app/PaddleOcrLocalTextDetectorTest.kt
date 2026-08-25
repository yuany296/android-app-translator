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

@RunWith(AndroidJUnit4::class)
class PaddleOcrLocalTextDetectorTest {

    @Test
    fun paddleDetectsJapaneseText() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val detector = PaddleOcrLocalTextDetector(context)

        val bitmap = renderText(
            "こんにちは世界",
            "こんにちは世界"
        )
        val blocks = detector.detect(bitmap, "ja")
        val recognized = blocks.joinToString("|") { it.text }
        println("PaddleOcrBlocks: $recognized")
        assertTrue(
            "paddle should detect japanese text, got: $recognized",
            blocks.isNotEmpty()
        )
        assertTrue(
            "should contain こんにちは, got: $recognized",
            recognized.contains("こんにちは")
        )
    }

    private fun renderText(text: String, hint: String): Bitmap {
        val width = 640
        val height = 120
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 64f
            isAntiAlias = true
        }
        canvas.drawText(text, 20f, 85f, paint)
        return bitmap
    }
}