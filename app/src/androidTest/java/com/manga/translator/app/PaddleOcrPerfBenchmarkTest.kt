package com.manga.translator.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manga.translator.translate.PaddleOcrLocalTextDetector
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PaddleOCR 整页耗时基准测试（手动运行，非 CI 门禁）。
 *
 * 用途：比较「识别阶段串行 vs 并行」等性能改动的实际收益，并同时输出每个文字块的内容，
 * 便于前后对比确认识别结果没有回退。
 *
 * 前置：把整页截图推到被测应用的 external files 目录，文件名固定为
 *   bench_page1.png / bench_page2.png / bench_page3.png
 * adb 命令见 `scripts/` 或 HumanTODO.md；缺图时用例只打印 SKIP，不会失败。
 *
 * 运行：
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.manga.translator.app.PaddleOcrPerfBenchmarkTest
 *
 * 读结果：logcat / 测试报告里过滤 `BENCH`。同时过滤器件的 `PaddleOcr` tag 可看到
 * `paddle_total_ms=... rec_ms=... rec_threads=...` 的分阶段耗时。
 */
@RunWith(AndroidJUnit4::class)
class PaddleOcrPerfBenchmarkTest {

    @Test
    fun benchmarkPageOcr() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = context.getExternalFilesDir(null)!!
        val cases = listOf("bench_page1.png", "bench_page2.png", "bench_page3.png")
        val detector = PaddleOcrLocalTextDetector(context)
        var warmed = false
        var measured = 0
        cases.forEach { name ->
            val file = java.io.File(dir, name)
            if (!file.exists()) {
                println("== BENCH $name SKIP missing=$file ==")
                return@forEach
            }
            val bitmap = runCatching {
                java.io.FileInputStream(file).use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (bitmap == null) {
                println("== BENCH $name DECODE_FAILED ==")
                return@forEach
            }
            println("== BENCH $name size=${bitmap.width}x${bitmap.height} ==")
            // 首次调用含模型加载与会话创建，不计入耗时。
            if (!warmed) {
                val warm = detector.detect(bitmap, SOURCE_LANGUAGE)
                println("BENCH warmup blocks=${warm.size} (excluded from timing)")
                warmed = true
            }
            repeat(RUNS_PER_CASE) { run ->
                val startedAt = System.currentTimeMillis()
                val blocks = runCatching { detector.detect(bitmap, SOURCE_LANGUAGE) }
                    .getOrElse { err ->
                        println("BENCH $name run#$run ERROR ${err.javaClass.simpleName}: ${err.message?.take(120)}")
                        return@repeat
                    }
                val elapsed = System.currentTimeMillis() - startedAt
                measured++
                println("BENCH $name run#$run ms=$elapsed blocks=${blocks.size}")
                blocks.forEach { block ->
                    println("  B $name#$run [${block.text}] conf=${block.confidence}")
                }
            }
        }
        println("BENCH_DONE cases_measured=$measured")
    }

    /**
     * 冷启动模型加载耗时：回答「第一次使用时悬浮球为什么点不动、要等多久」。
     *
     * 分两段量：
     *  - COLD = 删掉 filesDir 下的模型副本后加载 → 等价「刚装完 App 第一次用」（复制 assets + 建 ONNX 会话）
     *  - WARM = 模型副本已在、只新建 detector 实例 → 等价「之后每次服务重启」（只建 ONNX 会话）
     */
    @Test
    fun coldStartModelLoadMs() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelDir = java.io.File(context.filesDir, "paddle_models")
        if (modelDir.exists()) {
            val removed = modelDir.listFiles()?.sumOf { it.length() } ?: 0L
            println("== COLD cleared paddle_models bytes=$removed ==")
            modelDir.deleteRecursively()
        }

        val coldDetector = PaddleOcrLocalTextDetector(context)
        val coldStart = System.currentTimeMillis()
        coldDetector.prewarm(SOURCE_LANGUAGE)
        val coldMs = System.currentTimeMillis() - coldStart
        println("COLD first_use_ms=$coldMs ready=${coldDetector.isReady(SOURCE_LANGUAGE)}")

        val warmDetector = PaddleOcrLocalTextDetector(context)
        val warmStart = System.currentTimeMillis()
        warmDetector.prewarm(SOURCE_LANGUAGE)
        val warmMs = System.currentTimeMillis() - warmStart
        println("COLD later_launch_ms=$warmMs ready=${warmDetector.isReady(SOURCE_LANGUAGE)}")

        val againStart = System.currentTimeMillis()
        warmDetector.prewarm(SOURCE_LANGUAGE)
        println("COLD already_ready_ms=${System.currentTimeMillis() - againStart}")
        println("COLD_DONE first_use_ms=$coldMs later_launch_ms=$warmMs")
    }

    private companion object {
        const val SOURCE_LANGUAGE = "zh"
        const val RUNS_PER_CASE = 5
    }
}


