package com.manga.translator.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manga.translator.translate.PaddleOcrLocalTextDetector
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaddleOcrKoreanMangaTest {

    @Test
    fun paddleReadsKoreanMangaScreenshot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testFile = java.io.File(context.getExternalFilesDir(null), "test_manga.jpg")
        val bitmap = java.io.FileInputStream(testFile).use {
            BitmapFactory.decodeStream(it)
        }!!
        val detector = PaddleOcrLocalTextDetector(context)
        val blocks = detector.detect(bitmap, "ko")
        blocks.forEachIndexed { i, b ->
            println("PaddleBlock#$i text=[${b.text}] box=(${b.left.toInt()},${b.top.toInt()},${b.right.toInt()},${b.bottom.toInt()}) conf=${b.confidence}")
        }
        val recognized = blocks.joinToString("|") { it.text }
        assertTrue("paddle should detect blocks, got: $recognized", blocks.isNotEmpty())
        assertTrue(
            "should merge whole sentence, got: $recognized",
            blocks.any { it.text.contains("뭔가") && it.text.contains("있는가") }
        )
        assertTrue(
            "should read 단순히, got: $recognized",
            blocks.any { it.text.contains("단순히") }
        )
        assertTrue(
            "should read bottom colorful text 이거/재밌네, got: $recognized",
            blocks.any { it.text.contains("이거") && it.text.contains("재밌네") }
        )
    }
}