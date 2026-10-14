package com.manga.translator.translate

/**
 * 本地 OCR 结果的「质量闸」：判断要不要把整张图交给**多模态模型**重读
 * （2026-10-14「低质量的识别进行多模态模型识别」）。
 *
 * 判据对齐参考项目 `C:\homework\translator` 的低质量词修复
 * （`background/modules/vision-ocr.js#shouldRepairLocalPaddleWordWithVision`）：
 * 置信度低、或读出来的文字里几乎没有目标语言字符 → 大概率读坏了。
 * 参考项目按「词」裁小图修复，我们按「整页」回退（本地没有逐词分数时可用的信号更少，
 * 页级一次请求也简单可控）。
 *
 * 纯 Kotlin、无 Android 依赖，可 JVM 单测。
 */
object OcrQualityGate {

    /** Paddle 平均置信度低于它 → 读坏了。 */
    const val AVG_CONFIDENCE_MIN = 0.62f

    /** 低分块（< 0.72，Paddle 的 isWeakResult 口径）占比超过它 → 读坏了。 */
    const val WEAK_RATIO_MAX = 0.5f

    /** Paddle 的弱分线（与 `PaddleQuadGeometry.WEAK_RECOGNITION_SCORE` 同值同义）。 */
    const val WEAK_SCORE = 0.72f

    /**
     * 要不要触发多模态重读。
     *
     * @param blocks 本地 OCR 结果（可空 = 本地一无所获）。
     * @param expectedLanguageId 期望的源语言（配置值或自动识别值）。en 不做脚本检查
     *   （拉丁字母哪儿都有，检查必然误报）。
     */
    fun needsVisionFallback(blocks: List<TextBlock>, expectedLanguageId: String): Boolean {
        if (blocks.isEmpty()) return true
        val text = blocks.joinToString(" ") { it.text }
        if (text.isBlank()) return true

        // 信号 1：分数（Paddle 有真实置信度；ML Kit 恒 0 —— 全 0 时跳过这条）。
        val scores = blocks.map { it.confidence }.filter { it > 0f }
        if (scores.size >= maxOf(1, blocks.size / 2)) {
            val avg = scores.average().toFloat()
            val weakRatio = scores.count { it < WEAK_SCORE }.toFloat() / scores.size
            if (avg < AVG_CONFIDENCE_MIN || weakRatio > WEAK_RATIO_MAX) return true
        }

        // 信号 2：脚本。读出来的文字里连一个期望语言的字符都没有 → 识别器大概率不认这种字。
        val expected = expectedLanguageId.lowercase()
        if (expected != "en" && text.length >= 4) {
            val hasExpectedScript = when (expected) {
                "ko" -> text.any { it.isHangulChar() }
                "ja" -> text.any { it.isKanaChar() || it.isHanChar() }
                "zh-cn", "zh-tw", "zh" -> text.any { it.isHanChar() }
                else -> true
            }
            if (!hasExpectedScript) return true
        }
        return false
    }

    /**
     * 多模态结果是否值得替换本地结果：目标脚本字符更多才算更好
     * （多模态也可能整页读空/读坏，不能盲目替换）。
     */
    fun visionBeatsLocal(vision: List<TextBlock>, local: List<TextBlock>, expectedLanguageId: String): Boolean {
        if (vision.isEmpty()) return false
        if (local.isEmpty()) return true
        return scriptWeight(vision, expectedLanguageId) > scriptWeight(local, expectedLanguageId)
    }

    private fun scriptWeight(blocks: List<TextBlock>, expectedLanguageId: String): Int {
        val expected = expectedLanguageId.lowercase()
        return blocks.sumOf { block ->
            // 只数期望脚本本身的字符：拟声词/噪声里的拉丁字母不说明「读得更好」。
            block.text.count { ch ->
                when (expected) {
                    "ko" -> ch.isHangulChar()
                    "ja" -> ch.isKanaChar() || ch.isHanChar()
                    "zh-cn", "zh-tw", "zh" -> ch.isHanChar()
                    else -> ch.isLatinLetterChar()
                }
            }
        }
    }

    private fun Char.isHangulChar(): Boolean =
        this in '\uAC00'..'\uD7A3' || this in '\u1100'..'\u11FF' || this in '\u3130'..'\u318F'

    private fun Char.isKanaChar(): Boolean =
        this in '\u3040'..'\u30FF' || this in '\u31F0'..'\u31FF' || this in '\uFF66'..'\uFF9D'

    private fun Char.isHanChar(): Boolean =
        this in '\u3400'..'\u4DBF' || this in '\u4E00'..'\u9FFF' || this in '\uF900'..'\uFAFF'

    private fun Char.isLatinLetterChar(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '\uFF21'..'\uFF3A' || this in '\uFF41'..'\uFF5A'
}
