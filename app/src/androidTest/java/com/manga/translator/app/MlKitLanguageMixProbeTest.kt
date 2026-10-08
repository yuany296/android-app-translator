package com.manga.translator.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.manga.translator.translate.PaddleOcrLocalTextDetector
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * 「韩文页面里混着日文」的实测探针。
 *
 * 素材 `syn1b.png`（夹具站 v-seam-none.html 用的同一张，来自 .verify/web/）里
 * 同时有：韩文粉色旁白、韩文斜排文字，以及**右缘一段竖排日文「これは縦書きです」**。
 *
 * 要回答的问题：源语言设成「韩文」时，这段日文分别会被
 *   - ML Kit 韩文识别器
 *   - ML Kit 日文识别器
 *   - PaddleOCR 韩文模型
 *   - PaddleOCR 日文模型
 * 识别成什么。这决定了「混在韩漫里的日文该不该单独处理」。
 */
@RunWith(AndroidJUnit4::class)
class MlKitLanguageMixProbeTest {

    private fun loadAsset(name: String): Bitmap {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        return assets.open(name).use { BitmapFactory.decodeStream(it) }
    }

    private fun mlkit(tag: String, bitmap: Bitmap, recognizer: TextRecognizer) {
        try {
            val result = Tasks.await(
                recognizer.process(InputImage.fromBitmap(bitmap, 0)),
                25,
                TimeUnit.SECONDS
            )
            println("MIX_PROBE [$tag] blockCount=${result.textBlocks.size}")
            result.textBlocks.forEachIndexed { index, block ->
                val text = block.text.replace("\n", "\\n")
                val confs = block.lines.map { "%.3f".format(it.confidence) }
                println(
                    "MIX_PROBE [$tag] block#$index box=${block.boundingBox} " +
                        "conf(${confs.size})=$confs text=[$text]"
                )
            }
        } catch (err: Exception) {
            println("MIX_PROBE_FAIL [$tag] ${err.javaClass.simpleName}: ${err.message}")
        } finally {
            runCatching { recognizer.close() }
        }
    }

    @Test
    fun mlKitKoreanRecognizerOnMixedPage() {
        mlkit(
            "mlkit-ko",
            loadAsset("syn1b.png"),
            TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        )
    }

    @Test
    fun mlKitJapaneseRecognizerOnMixedPage() {
        mlkit(
            "mlkit-ja",
            loadAsset("syn1b.png"),
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        )
    }

    /**
     * 按真实链路的做法：把右缘那段竖排日文单独裁出来再识别。
     * 裁剪框来自 [mlKitJapaneseRecognizerOnMixedPage] 实测到的 box(751,134 - 792,521) 外扩 30px。
     */
    @Test
    fun croppedVerticalJapaneseRegion() {
        val full = loadAsset("syn1b.png")
        val crop = Bitmap.createBitmap(full, 721, 104, 101, 447)
        mlkit(
            "crop-ko",
            crop,
            TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        )
        mlkit(
            "crop-ja",
            crop,
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        )
        val scaled = Bitmap.createScaledBitmap(crop, crop.width * 3, crop.height * 3, true)
        mlkit(
            "crop3x-ko",
            scaled,
            TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        )
        mlkit(
            "crop3x-ja",
            scaled,
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        )
    }

    @Test
    fun paddleOnMixedPage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = loadAsset("syn1b.png")
        val detector = PaddleOcrLocalTextDetector(context)
        val korean = runCatching { detector.detect(bitmap, "ko") }
            .getOrElse { emptyList() }
        println(
            "MIX_PROBE [paddle-ko] blocks=" +
                korean.joinToString(" | ") {
                    "[${it.text}]@${"%.3f".format(it.confidence)}" +
                        " box=(${it.left.toInt()},${it.top.toInt()},${it.right.toInt()},${it.bottom.toInt()})"
                }
        )
        val japanese = runCatching { detector.detect(bitmap, "ja") }
            .getOrElse { emptyList() }
        println(
            "MIX_PROBE [paddle-ja] blocks=" +
                japanese.joinToString(" | ") {
                    "[${it.text}]@${"%.3f".format(it.confidence)}" +
                        " box=(${it.left.toInt()},${it.top.toInt()},${it.right.toInt()},${it.bottom.toInt()})"
                }
        )
    }
}
