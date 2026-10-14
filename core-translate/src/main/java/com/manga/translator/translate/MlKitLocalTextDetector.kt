package com.manga.translator.translate

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class MlKitLocalTextDetector : LocalTextDetector {
    override fun detect(bitmap: Bitmap, sourceLanguageId: String): List<TextBlock> {
        val recognizer = createRecognizer(sourceLanguageId)
        val scale = min(1f, MAX_EDGE.toFloat() / max(bitmap.width, bitmap.height))
        val work = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                max(1, (bitmap.width * scale).toInt()),
                max(1, (bitmap.height * scale).toInt()),
                false
            )
        } else {
            bitmap
        }
        return try {
            runCatching {
                val image = InputImage.fromBitmap(work, 0)
                val result = Tasks.await(recognizer.process(image), 8, TimeUnit.SECONDS)
                result.textBlocks.mapIndexedNotNull { index, block ->
                    val box = block.boundingBox ?: return@mapIndexedNotNull null
                    val text = block.text.trim()
                    if (text.isBlank()) return@mapIndexedNotNull null
                    TextBlock(
                        id = index,
                        text = text,
                        left = (box.left / scale).toFloat(),
                        top = (box.top / scale).toFloat(),
                        right = (box.right / scale).toFloat(),
                        bottom = (box.bottom / scale).toFloat(),
                        confidence = 0f,
                        // ML Kit 的 `block` 是**段落级**多行块：行数必须带出来，否则
                        // 「行高 = 框高 ÷ 行数」会退化成「整块高度」→ 合并阈值放大数倍
                        // （2026-10-03 两个独立气泡被并成一句的真机事故）。
                        lineCount = block.lines.count { it.text.isNotBlank() }.coerceAtLeast(1),
                        // 逐行框（除回缩放，与块框同坐标系）：行高中位数 = 原文行高基线、
                        // 行框并集 = 真实字形区。块级框偏松且会粘到气泡描边（OTSU 收不回），
                        // 「框高 ÷ 行数」系统性放大字号（2026-10-14 用户截图：译文比原文
                        // 大 1/3、白盖板戳出气泡下沿）。
                        lineBoxes = block.lines.mapNotNull { line ->
                            line.boundingBox?.let { box ->
                                floatArrayOf(
                                    box.left / scale,
                                    box.top / scale,
                                    box.right / scale,
                                    box.bottom / scale
                                )
                            }
                        }.takeIf { it.isNotEmpty() }
                    )
                }
            }.getOrElse { err ->
                Log.w(TAG, "mlkit detect failed: ${err.message}")
                emptyList()
            }
        } finally {
            if (work !== bitmap && !work.isRecycled) {
                work.recycle()
            }
            runCatching { recognizer.close() }
        }
    }

    private fun createRecognizer(sourceLanguageId: String): TextRecognizer {
        return when (sourceLanguageId.lowercase()) {
            "ja", "jpn", "japanese", "日文" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            "ko", "kor", "korean", "韩文" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            "zh-cn", "zh-tw", "zh", "chinese", "简体中文", "繁体中文", "中文" -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.Builder().build())
        }
    }

    private companion object {
        const val TAG = "MlKitLocalDetector"
        const val MAX_EDGE = 1280
    }
}
