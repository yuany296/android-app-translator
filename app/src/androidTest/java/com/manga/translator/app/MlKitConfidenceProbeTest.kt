package com.manga.translator.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * ML Kit 置信度探针。
 *
 * 目的：确认 bundled 版 Text Recognition v2（`com.google.mlkit:text-recognition*`）
 * 在 拉丁 / 日文 / 韩文 / 中文 四种识别器下，各层级 block / line / element / symbol
 * 的 `getConfidence()` 究竟返回什么。
 *
 * 为什么必须实测：官方 API 参考只写了一条限制 ——「unbundled 版 + Google Play 服务
 * 低于 22.30.XX 时返回 0」，对 CJK 脚本是否可用只字未提；而社区反馈普遍是
 * 「on-device 拿不到置信度」。文档不足以定论，只能跑一次看真值。
 *
 * 过滤方式：`adb logcat -s TestRunner` 或直接看 connectedAndroidTest 输出里的
 * `CONF_PROBE` 前缀行。
 */
@RunWith(AndroidJUnit4::class)
class MlKitConfidenceProbeTest {

    @Test
    fun latin() {
        probe(
            "latin",
            renderHorizontal("Hello confidence probe"),
            TextRecognition.getClient(TextRecognizerOptions.Builder().build())
        )
    }

    @Test
    fun japaneseHorizontal() {
        probe(
            "ja-horizontal",
            renderHorizontal("こんにちは世界"),
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        )
    }

    @Test
    fun japaneseVertical() {
        probe(
            "ja-vertical",
            renderVertical("こんにちは"),
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        )
    }

    @Test
    fun koreanHorizontal() {
        probe(
            "ko-horizontal",
            renderHorizontal("안녕하세요 세계"),
            TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        )
    }

    @Test
    fun chineseHorizontal() {
        probe(
            "zh-horizontal",
            renderHorizontal("你好世界"),
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        )
    }

    private fun probe(name: String, bitmap: Bitmap, recognizer: TextRecognizer) {
        try {
            val result = Tasks.await(
                recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                20,
                TimeUnit.SECONDS
            )
            val blocks = result.textBlocks
            val lineConfs = blocks.flatMap { it.lines }.map { it.confidence }
            val elemConfs = blocks.flatMap { it.lines }.flatMap { it.elements }.map { it.confidence }
            val symConfs = blocks.flatMap { it.lines }.flatMap { it.elements }
                .flatMap { it.symbols }.map { it.confidence }
            println(
                "CONF_PROBE_SUMMARY $name blocks=${blocks.size} lines=${lineConfs.size} " +
                    "elems=${elemConfs.size} syms=${symConfs.size} " +
                    "lineNonZero=${lineConfs.count { it > 0f }} " +
                    "elemNonZero=${elemConfs.count { it > 0f }} " +
                    "symNonZero=${symConfs.count { it > 0f }} " +
                    "lineRange=${lineConfs.minOrNull()}..${lineConfs.maxOrNull()} " +
                    "elemRange=${elemConfs.minOrNull()}..${elemConfs.maxOrNull()}"
            )
            blocks.forEach { block ->
                println(
                    "CONF_PROBE $name block lang=${block.recognizedLanguage} text=[${block.text}]"
                )
                block.lines.forEach { line ->
                    println(
                        "CONF_PROBE $name   line conf=${line.confidence} " +
                            "lang=${line.recognizedLanguage} text=[${line.text}]"
                    )
                    line.elements.forEach { element ->
                        println(
                            "CONF_PROBE $name     elem conf=${element.confidence} " +
                                "text=[${element.text}]"
                        )
                        element.symbols.forEach { symbol ->
                            println(
                                "CONF_PROBE $name       sym conf=${symbol.confidence} " +
                                    "text=[${symbol.text}]"
                            )
                        }
                    }
                }
            }
        } catch (err: Exception) {
            println("CONF_PROBE_FAIL $name ${err.javaClass.simpleName}: ${err.message}")
        } finally {
            runCatching { recognizer.close() }
        }
    }

    private fun renderHorizontal(text: String): Bitmap {
        val width = 900
        val height = 240
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 84f
            isAntiAlias = true
        }
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            drawText(text, 40f, height / 2f + 28f, paint)
        }
        return bitmap
    }

    private fun renderVertical(text: String): Bitmap {
        val width = 200
        val height = 640
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 72f
            isAntiAlias = true
        }
        Canvas(bitmap).apply {
            drawColor(Color.WHITE)
            var y = 90f
            for (ch in text) {
                drawText(ch.toString(), width / 2f - 36f, y, paint)
                y += 90f
            }
        }
        return bitmap
    }
}
