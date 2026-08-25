package com.manga.translator.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manga.translator.translate.PaddleOcrLocalTextDetector
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaddleComplexBackgroundsTest {

    @Test
    fun paddleReadsComplexBgScreenshots() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = context.getExternalFilesDir(null)!!
        // 三张"复杂背景 + 文字"截图，参考项目能识别，验证 Android Paddle。
        val inputs = listOf("test1.jpg", "test2.jpg", "test3.jpg")
        val languages = listOf("ko", "ja", "zh")
        inputs.forEachIndexed { imgIdx, name ->
            val f = java.io.File(dir, name)
            if (!f.exists()) {
                println("== IMG[$imgIdx] $name => MISSING ==")
                return@forEachIndexed
            }
            val bitmap = java.io.FileInputStream(f).use { BitmapFactory.decodeStream(it) }
                ?: run {
                    println("== IMG[$imgIdx] $name => DECODE_FAILED ==")
                    return@forEachIndexed
                }
            println("== IMG[$imgIdx] $name size=${bitmap.width}x${bitmap.height} ==")
            languages.forEach { lang ->
                val blocks = runCatching {
                    val detector = PaddleOcrLocalTextDetector(context)
                    detector.detect(bitmap, lang)
                }.getOrElse { err ->
                    println("  lang=$lang ERROR ${err.javaClass.simpleName}: ${err.message?.take(120)}")
                    return@forEach
                }
                println("  lang=$lang blocks=${blocks.size}")
                blocks.forEachIndexed { i, b ->
                    println("    [$lang#$i] text=[${b.text}] box=(${b.left.toInt()},${b.top.toInt()},${b.right.toInt()},${b.bottom.toInt()}) conf=${b.confidence}")
                }
            }
        }
    }
}
