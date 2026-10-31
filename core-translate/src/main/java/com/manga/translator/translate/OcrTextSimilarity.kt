package com.manga.translator.translate

import kotlin.math.abs
import kotlin.math.max

/**
 * OCR 文本近似判据（2026-11-01）。
 *
 * 同一句在两个检测块/两次识别里可能读成两版（真机 `pageHash ca9603cd…`：
 * `지금 물어야겠어?\n조용히 따라오기나 해.` vs `지금 물어아겼어? 조리히 따리오기나 해.`），
 * 两版都留下就会被后续段落合并拼成一句 → 译文重复。
 *
 * 归一化：只保留字母/数字并转小写（去掉空格、标点、换行）。
 * 相似度：Levenshtein 距离折算到 0..1；长度差太大直接判不相似，避免长文本 DP 开销。
 *
 * 纯函数，无 Android 依赖，可 JVM 单测。
 */
internal object OcrTextSimilarity {

    /** 近似变体相似度门槛（现场 0.8125）。 */
    const val NEAR_DUPLICATE_SIMILARITY = 0.80f

    /** 太短的文本不做近似去重（避免「嗯」「？」被误吞）。 */
    const val NEAR_DUPLICATE_MIN_CHARS = 4

    /** 归一化：只保留字母/数字，转小写。 */
    fun normalize(text: String): String =
        text.filter { it.isLetterOrDigit() }.lowercase()

    /**
     * 两段**已归一化**文本是否属于「同一句的 OCR 近似变体」。
     *
     * 长度门槛/长度差先挡掉大部分无关对，再做 Levenshtein。
     */
    fun isNearDuplicate(aText: String, bText: String): Boolean {
        if (aText.length < NEAR_DUPLICATE_MIN_CHARS || bText.length < NEAR_DUPLICATE_MIN_CHARS) {
            return false
        }
        val maxLen = max(aText.length, bText.length)
        if (abs(aText.length - bText.length) > maxLen * 0.30f) return false
        return similarity(aText, bText) >= NEAR_DUPLICATE_SIMILARITY
    }

    /** 两行 DP 的 Levenshtein 相似度（0..1）；文本已在调用前归一化。 */
    fun similarity(a: String, b: String): Float {
        val maxLen = max(a.length, b.length)
        if (maxLen == 0) return 0f
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + cost
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return 1f - previous[b.length].toFloat() / maxLen.toFloat()
    }
}
