package com.manga.translator.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** OCR 低质量闸（2026-10-14「低质量的识别进行多模态模型识别」）。 */
class OcrQualityGateTest {

    @Test
    fun emptyResultNeedsFallback() {
        assertTrue(OcrQualityGate.needsVisionFallback(emptyList(), "ko"))
        assertTrue(OcrQualityGate.needsVisionFallback(listOf(block("  ", 0.9f)), "ko"))
    }

    @Test
    fun lowAverageConfidenceNeedsFallback() {
        val blocks = listOf(
            block("블록 하나", 0.55f),
            block("블록 둘", 0.58f),
            block("블록 셋", 0.60f)
        )
        assertTrue(OcrQualityGate.needsVisionFallback(blocks, "ko"))
    }

    @Test
    fun manyWeakBlocksNeedFallback() {
        // 平均分过线但一半以上是弱分块（< 0.72）→ 大概率读坏了一半。
        val blocks = listOf(
            block("블록 하나", 0.95f),
            block("garble", 0.60f),
            block("garble2", 0.62f),
            block("garble3", 0.61f)
        )
        assertTrue(OcrQualityGate.needsVisionFallback(blocks, "ko"))
    }

    @Test
    fun healthyKoreanResultDoesNotNeedFallback() {
        val blocks = listOf(
            block("케이지라고요!!", 0.95f),
            block("접속종료를 하려면 죽거나", 0.93f)
        )
        assertFalse(OcrQualityGate.needsVisionFallback(blocks, "ko"))
    }

    @Test
    fun missingExpectedScriptNeedsFallback() {
        // 配的韩文，读出来的全是拉丁噪声（ML Kit 无分数，只能靠脚本判断）。
        val blocks = listOf(block("cYa S O caal", 0f))
        assertTrue(OcrQualityGate.needsVisionFallback(blocks, "ko"))
    }

    @Test
    fun englishPageSkipsScriptCheck() {
        // en 不做脚本检查（拉丁字母哪儿都有，必误报），只有分数能触发。
        val blocks = listOf(block("SFX BANG", 0f))
        assertFalse(OcrQualityGate.needsVisionFallback(blocks, "en"))
    }

    @Test
    fun japaneseResultCheckedAgainstKanaOrHan() {
        // 日文允许汉字兜底（假名漏检时还有汉字）。
        val blocks = listOf(block("田村さん", 0f))
        assertFalse(OcrQualityGate.needsVisionFallback(blocks, "ja"))
        val hangulOnly = listOf(block("케이지라고요", 0f))
        assertTrue(OcrQualityGate.needsVisionFallback(hangulOnly, "ja"))
    }

    @Test
    fun visionReplacesLocalOnlyWithMoreTargetScript() {
        val local = listOf(block("cYa garbage", 0f))
        val vision = listOf(block("케이지라고요!!", 0f), block("접속종료를 하려면", 0f))
        assertTrue(OcrQualityGate.visionBeatsLocal(vision, local, "ko"))
        assertFalse(OcrQualityGate.visionBeatsLocal(emptyList(), local, "ko"))
        // 多模态读得比本地还少 → 不替换。
        assertFalse(OcrQualityGate.visionBeatsLocal(listOf(block("케이지", 0f)), vision, "ko"))
        // 本地为空时，多模态只要有产出就替换。
        assertTrue(OcrQualityGate.visionBeatsLocal(vision, emptyList(), "ko"))
    }

    private fun block(text: String, confidence: Float): TextBlock = TextBlock(
        id = 0,
        text = text,
        left = 0f,
        top = 0f,
        right = 100f,
        bottom = 40f,
        confidence = confidence
    )
}
