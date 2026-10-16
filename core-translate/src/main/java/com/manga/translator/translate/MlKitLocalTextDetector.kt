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
        return try {
            // 长条图分块检测（2026-10-16）：§54-B 只接了 Paddle，ML Kit 仍把整图缩到
            // 长边 1280 —— 600×2544 的切图缩到 300×1272，单字 chip / 句尾符号直接落进
            // 检测阈下（用户在 ML Kit 模式下持续看到「有的字没被处理」）。闸门与切块
            // 复用 [PaddleDetectionTiling]（普通切图不满足闸门 → 单趟，行为不变）。
            if (PaddleDetectionTiling.shouldTile(bitmap.width, bitmap.height, MAX_EDGE.toFloat())) {
                detectTiled(recognizer, bitmap)
            } else {
                detectScaled(recognizer, bitmap, offsetX = 0f, offsetY = 0f)
            }
        } finally {
            runCatching { recognizer.close() }
        }
    }

    /**
     * 长条图分块：沿长边切 2~3 块，逐块缩放检测后把框映射回整图坐标并按 IoU 去重。
     * 块的长边 ≈ 检测上限 → 块内文字按**接近原分辨率**检测（整图单趟时只有 ~25%）。
     */
    private fun detectTiled(recognizer: TextRecognizer, bitmap: Bitmap): List<TextBlock> {
        val horizontal = bitmap.width > bitmap.height
        val length = if (horizontal) bitmap.width else bitmap.height
        val ranges = PaddleDetectionTiling.tiles(length = length, detResizeLong = MAX_EDGE.toFloat())
        if (ranges.size <= 1) {
            return detectScaled(recognizer, bitmap, offsetX = 0f, offsetY = 0f)
        }
        val candidates = mutableListOf<MlKitTiledBlockMerger.Candidate>()
        var added = 0
        for ((index, range) in ranges.withIndex()) {
            val tile = if (horizontal) {
                Bitmap.createBitmap(bitmap, range.start, 0, range.length, bitmap.height)
            } else {
                Bitmap.createBitmap(bitmap, 0, range.start, bitmap.width, range.length)
            }
            val blocks = try {
                detectScaled(
                    recognizer,
                    tile,
                    offsetX = if (horizontal) range.start.toFloat() else 0f,
                    offsetY = if (horizontal) 0f else range.start.toFloat()
                )
            } finally {
                tile.recycle()
            }
            for (block in blocks) {
                val candidate = MlKitTiledBlockMerger.Candidate(
                    text = block.text,
                    left = block.left,
                    top = block.top,
                    right = block.right,
                    bottom = block.bottom,
                    lineCount = block.lineCount,
                    lineBoxes = block.lineBoxes
                )
                if (candidates.none { existing ->
                        MlKitTiledBlockMerger.boxIoU(existing, candidate) >=
                            MlKitTiledBlockMerger.MERGE_IOU_THRESHOLD
                    }
                ) {
                    candidates += candidate
                    added += 1
                }
            }
        }
        Log.d(
            TAG,
            "mlkit_det_tiled tiles=${ranges.size} axis=${if (horizontal) "x" else "y"} " +
                "ranges=${ranges.joinToString(",") { "${it.start}-${it.endExclusive}" }} " +
                "blocks=$added img=${bitmap.width}x${bitmap.height}"
        )
        return candidates.mapIndexed { index, candidate ->
            TextBlock(
                id = index,
                text = candidate.text,
                left = candidate.left,
                top = candidate.top,
                right = candidate.right,
                bottom = candidate.bottom,
                confidence = 0f,
                lineCount = candidate.lineCount,
                lineBoxes = candidate.lineBoxes
            )
        }
    }

    /**
     * 单趟检测：整图（或一个块）等比缩到长边 [MAX_EDGE] 以内，检出后把块级框与
     * 逐行框除回缩放、加 [offsetX]/[offsetY]（分块路径传块在整图里的起点）映射回源坐标。
     */
    private fun detectScaled(
        recognizer: TextRecognizer,
        bitmap: Bitmap,
        offsetX: Float,
        offsetY: Float
    ): List<TextBlock> {
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
                        left = offsetX + box.left / scale,
                        top = offsetY + box.top / scale,
                        right = offsetX + box.right / scale,
                        bottom = offsetY + box.bottom / scale,
                        confidence = 0f,
                        // ML Kit 的 `block` 是**段落级**多行块：行数必须带出来，否则
                        // 「行高 = 框高 ÷ 行数」会退化成「整块高度」→ 合并阈值放大数倍
                        // （2026-10-03 两个独立气泡被并成一句的真机事故）。
                        lineCount = block.lines.count { it.text.isNotBlank() }.coerceAtLeast(1),
                        // 逐行框（除回缩放 + 块偏移，与块框同坐标系）：行高中位数 = 原文行高
                        // 基线、行框并集 = 真实字形区。块级框偏松且会粘到气泡描边（OTSU
                        // 收不回），「框高 ÷ 行数」系统性放大字号（2026-10-14 用户截图：
                        // 译文比原文大 1/3、白盖板戳出气泡下沿）。
                        lineBoxes = block.lines.mapNotNull { line ->
                            line.boundingBox?.let { lineBox ->
                                floatArrayOf(
                                    offsetX + lineBox.left / scale,
                                    offsetY + lineBox.top / scale,
                                    offsetX + lineBox.right / scale,
                                    offsetY + lineBox.bottom / scale
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
