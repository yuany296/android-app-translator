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
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

class MlKitLocalTextDetector : LocalTextDetector {
    /**
     * 按语言复用 ML Kit recognizer（2026-11-02，P3）。
     *
     * 旧实现每次 detect 都 `getClient(...)` 然后在 finally 里 `close()`：长页每张图
     * 都要重新初始化识别器，ML Kit 的模型/执行器初始化就落在每张图的 OCR 时间里。
     * 识别器本身支持同一 client 并发提交（分块检测已经这样用），所以这里只按语言
     * 缓存，不再每张图创建/销毁。
     */
    private val recognizers = ConcurrentHashMap<String, TextRecognizer>()

    override fun detect(bitmap: Bitmap, sourceLanguageId: String): List<TextBlock> {
        val recognizer = recognizerFor(sourceLanguageId)
        // 长条图分块检测（2026-10-16）：§54-B 只接了 Paddle，ML Kit 仍把整图缩到
        // 长边 1280 —— 600×2544 的切图缩到 300×1272，单字 chip / 句尾符号直接落进
        // 检测阈下（用户在 ML Kit 模式下持续看到「有的字没被处理」）。闸门与切块
        // 复用 [PaddleDetectionTiling]（普通切图不满足闸门 → 单趟，行为不变）。
        return if (PaddleDetectionTiling.shouldTile(bitmap.width, bitmap.height, MAX_EDGE.toFloat())) {
            detectTiled(recognizer, bitmap)
        } else {
            // 单趟也过一遍同一个合并器（2026-11-01）：短图没有分块，但同一句仍可能
            // 被 ML Kit 拆成「完整多行块 + 近似单行块」；不合并就会在后续段落合并里
            // 拼成两遍（真机 pageHash ca9603cd…）。
            mergeSinglePass(detectScaled(recognizer, bitmap, offsetX = 0f, offsetY = 0f))
        }
    }

    /** 释放所有语言的 recognizer；仅在应用真正结束识别器生命周期时调用。 */
    fun close() {
        recognizers.values.forEach { recognizer -> runCatching { recognizer.close() } }
        recognizers.clear()
    }

    override fun detect(bitmap: Bitmap, sourceLanguageId: String, seamTopPx: Int, seamBottomPx: Int): List<TextBlock> {
        val blocks = detect(bitmap, sourceLanguageId)
        val seams = buildList {
            if (seamTopPx in 1 until bitmap.height) add(seamTopPx.toFloat())
            if (seamBottomPx in 1 until bitmap.height) add(seamBottomPx.toFloat())
        }
        // ML Kit 段落块可能同时包含缝上/缝下两行：按 lineBoxes 拆开，不重新 OCR。
        return MlKitSeamSplitter.split(blocks, seams)
    }

    /**
     * 单趟检测结果也走 [MlKitTiledBlockMerger]：短图没有分块，但 ML Kit 仍可能把同一句
     * 拆成两个近似块；合并器只动「同框/近同框」的块，普通独立气泡不受影响。
     * 没发生合并时原样返回，保留原 id/顺序。
     */
    private fun mergeSinglePass(blocks: List<TextBlock>): List<TextBlock> {
        if (blocks.size <= 1) return blocks
        val candidates = blocks.map { block ->
            MlKitTiledBlockMerger.Candidate(
                text = block.text,
                left = block.left,
                top = block.top,
                right = block.right,
                bottom = block.bottom,
                lineCount = block.lineCount,
                lineBoxes = block.lineBoxes,
                tileIndex = 0,
                splitGroupId = block.splitGroupId
            )
        }
        val merged = MlKitTiledBlockMerger.merge(candidates)
        if (merged.size == blocks.size) return blocks
        return merged.mapIndexed { index, candidate ->
            TextBlock(
                id = index,
                text = candidate.text,
                left = candidate.left,
                top = candidate.top,
                right = candidate.right,
                bottom = candidate.bottom,
                confidence = 0f,
                lineCount = candidate.lineCount,
                lineBoxes = candidate.lineBoxes,
                splitGroupId = candidate.splitGroupId
            )
        }
    }

    /**
     * 长条图分块：沿长边切 2~3 块，逐块缩放检测后把框映射回整图坐标并按 IoU 去重。
     * 块的长边 ≈ 检测上限 → 块内文字按**接近原分辨率**检测（整图单趟时只有 ~25%）。
     *
     * 分块检测并行化（2026-10-22 用户反馈「分块后翻译明显变慢」）：块与块相互独立，
     * 串行跑 3 块 = 检测耗时 ×3。ML Kit 的 `process()` 是 Task 异步接口、同一 client
     * 支持并发提交，这里每块一个线程各自裁图/缩放/await；结果**按块序**收齐后再做
     * 映射 + IoU 去重 —— 与串行版输出逐位一致。
     */
    private fun detectTiled(recognizer: TextRecognizer, bitmap: Bitmap): List<TextBlock> {
        val horizontal = bitmap.width > bitmap.height
        val length = if (horizontal) bitmap.width else bitmap.height
        val ranges = PaddleDetectionTiling.tiles(length = length, detResizeLong = MAX_EDGE.toFloat())
        if (ranges.size <= 1) {
            return detectScaled(recognizer, bitmap, offsetX = 0f, offsetY = 0f)
        }
        val tileStartedAt = System.currentTimeMillis()
        val pool = Executors.newFixedThreadPool(min(ranges.size, MAX_TILE_THREADS)) { runnable ->
            Thread(runnable, "mlkit-det-tile").apply { isDaemon = true }
        }
        val tileFutures = ranges.mapIndexed { _, range ->
            val tile = if (horizontal) {
                Bitmap.createBitmap(bitmap, range.start, 0, range.length, bitmap.height)
            } else {
                Bitmap.createBitmap(bitmap, 0, range.start, bitmap.width, range.length)
            }
            pool.submit(
                Callable<Pair<List<TextBlock>, Long>> {
                    val passStart = System.currentTimeMillis()
                    try {
                        detectScaled(
                            recognizer,
                            tile,
                            offsetX = if (horizontal) range.start.toFloat() else 0f,
                            offsetY = if (horizontal) 0f else range.start.toFloat()
                        ) to (System.currentTimeMillis() - passStart)
                    } finally {
                        tile.recycle()
                    }
                }
            )
        }
        val tileResults = tileFutures.mapIndexed { index, future ->
            runCatching { future.get(TILE_AWAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
                .getOrElse { err ->
                    Log.w(TAG, "mlkit_det_tile_fail tile=$index reason=${err.message?.take(80)}")
                    emptyList<TextBlock>() to 0L
                }
        }
        pool.shutdown()
        val candidates = mutableListOf<MlKitTiledBlockMerger.Candidate>()
        var rawCount = 0
        for (index in ranges.indices) {
            val blocks = tileResults[index].first
            rawCount += blocks.size
            for (block in blocks) {
                candidates += MlKitTiledBlockMerger.Candidate(
                    text = block.text,
                    left = block.left,
                    top = block.top,
                    right = block.right,
                    bottom = block.bottom,
                    lineCount = block.lineCount,
                    lineBoxes = block.lineBoxes,
                    tileIndex = index,
                    splitGroupId = block.splitGroupId
                )
            }
        }
        val merged = MlKitTiledBlockMerger.merge(candidates)
        Log.d(
            TAG,
            "mlkit_det_tiled tiles=${ranges.size} axis=${if (horizontal) "x" else "y"} " +
                "ranges=${ranges.joinToString(",") { "${it.start}-${it.endExclusive}" }} " +
                "raw=$rawCount blocks=${merged.size} " +
                "tileBlocks=${tileResults.joinToString("/") { it.first.size.toString() }} " +
                "img=${bitmap.width}x${bitmap.height} " +
                "totalMs=${System.currentTimeMillis() - tileStartedAt} " +
                "tileMs=${tileResults.joinToString("/") { it.second.toString() }}"
        )
        return merged.mapIndexed { index, candidate ->
            TextBlock(
                id = index,
                text = candidate.text,
                left = candidate.left,
                top = candidate.top,
                right = candidate.right,
                bottom = candidate.bottom,
                confidence = 0f,
                lineCount = candidate.lineCount,
                lineBoxes = candidate.lineBoxes,
                splitGroupId = candidate.splitGroupId
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
                // 15s：分块并行后多块共享 CPU，单块可能比串行慢几倍；8s 在低端机上会把
                // 本来能出的结果误判超时（超时 = 该块整块丢字，比慢更伤）。
                val result = Tasks.await(recognizer.process(image), 15, TimeUnit.SECONDS)
                // 先在**检测图坐标系**（work）里建块、收紧元素、拆散相距很远的元素；
                // 最后统一除回缩放 + 加块偏移，避免 Splitter 依赖 Bitmap/Android。
                val workBlocks = result.textBlocks.mapIndexedNotNull { index, block ->
                    val box = block.boundingBox ?: return@mapIndexedNotNull null
                    val text = block.text.trim()
                    if (text.isBlank()) return@mapIndexedNotNull null
                    val lineBoxes = block.lines.mapNotNull { line ->
                        line.boundingBox?.let { lineBox ->
                            floatArrayOf(
                                lineBox.left.toFloat(),
                                lineBox.top.toFloat(),
                                lineBox.right.toFloat(),
                                lineBox.bottom.toFloat()
                            )
                        }
                    }.takeIf { it.isNotEmpty() }
                    val elements = block.lines.flatMapIndexed { lineIndex, line ->
                        line.elements.mapNotNull { element ->
                            val elementBox = element.boundingBox ?: return@mapNotNull null
                            if (element.text.isBlank()) return@mapNotNull null
                            TextElement(
                                text = element.text,
                                left = elementBox.left.toFloat(),
                                top = elementBox.top.toFloat(),
                                right = elementBox.right.toFloat(),
                                bottom = elementBox.bottom.toFloat(),
                                lineIndex = lineIndex
                            )
                        }
                    }.takeIf { it.isNotEmpty() }
                    TextBlock(
                        id = index,
                        text = text,
                        left = box.left.toFloat(),
                        top = box.top.toFloat(),
                        right = box.right.toFloat(),
                        bottom = box.bottom.toFloat(),
                        confidence = 0f,
                        // ML Kit 的 `block` 是**段落级**多行块：行数必须带出来，否则
                        // 「行高 = 框高 ÷ 行数」会退化成「整块高度」→ 合并阈值放大数倍
                        // （2026-10-03 两个独立气泡被并成一句的真机事故）。
                        lineCount = lineBoxes?.size?.coerceAtLeast(1) ?: 1,
                        // 逐行框（检测图坐标）：行高中位数 = 原文行高基线、
                        // 行框并集 = 真实字形区。
                        lineBoxes = lineBoxes,
                        elements = elements
                    )
                }
                val splitWorkBlocks = MlKitDetachedClusterSplitter.split(workBlocks) { element ->
                    val tight = TextInkBounds.findInBitmap(
                        work,
                        element.left.toInt(),
                        element.top.toInt(),
                        element.right.toInt(),
                        element.bottom.toInt()
                    )
                    tight?.let {
                        element.copy(
                            left = it[0].toFloat(),
                            top = it[1].toFloat(),
                            right = it[2].toFloat(),
                            bottom = it[3].toFloat()
                        )
                    }
                }
                if (splitWorkBlocks.map { it.text } != workBlocks.map { it.text }) {
                    val before = workBlocks.map { it.text.replace('\n', '⏎').take(12) }
                    val after = splitWorkBlocks.map { it.text.replace('\n', '⏎').take(12) }
                    Log.d(TAG, "mlkit_cluster_split from=${before.size} to=${after.size} before=$before after=$after")
                }
                splitWorkBlocks.map { block ->
                    TextBlock(
                        id = block.id,
                        text = block.text,
                        left = offsetX + block.left / scale,
                        top = offsetY + block.top / scale,
                        right = offsetX + block.right / scale,
                        bottom = offsetY + block.bottom / scale,
                        confidence = block.confidence,
                        lineCount = block.lineCount,
                        lineBoxes = block.lineBoxes?.map { line ->
                            floatArrayOf(
                                offsetX + line[0] / scale,
                                offsetY + line[1] / scale,
                                offsetX + line[2] / scale,
                                offsetY + line[3] / scale
                            )
                        },
                        elements = block.elements?.map { element ->
                            element.copy(
                                left = offsetX + element.left / scale,
                                top = offsetY + element.top / scale,
                                right = offsetX + element.right / scale,
                                bottom = offsetY + element.bottom / scale
                            )
                        },
                        splitGroupId = block.splitGroupId
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

    private fun recognizerFor(sourceLanguageId: String): TextRecognizer {
        val key = sourceLanguageId.trim().lowercase().ifBlank { "en" }
        return recognizers.computeIfAbsent(key) { createRecognizer(it) }
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

        /** 分块检测的最大并行线程数（块数上限即 3，线程数不超过它）。 */
        const val MAX_TILE_THREADS = 3

        /** 单个分块等待上限：并行时多块共享 CPU，单块比串行长；超时只丢该块、不阻塞整页。 */
        const val TILE_AWAIT_TIMEOUT_MS = 30_000L
    }
}
