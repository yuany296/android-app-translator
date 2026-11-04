package com.manga.translator.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer

class GlossaryTermMatchTest {

    @Test
    fun hangulTerm_matchesCommonParticleTails() {
        assertTrue(GlossaryTermMatch.matchesSourceTerm("레오나르도가 말했다", "레오나르도"))
        assertTrue(GlossaryTermMatch.matchesSourceTerm("레오나르도는", "레오나르도"))
        assertTrue(GlossaryTermMatch.matchesSourceTerm("레오나르도님", "레오나르도"))
        assertTrue(GlossaryTermMatch.matchesSourceTerm("레오나르도씨는", "레오나르도"))
    }

    @Test
    fun hangulTerm_matchesQuotativeTailFromRealDevice() {
        // 2026-11-02 真机词条：术语「케이지」出现在「케이지라고요!!」里。
        assertTrue(GlossaryTermMatch.matchesSourceTerm("케이지라고요!!", "케이지"))
    }

    @Test
    fun hangulTerm_doesNotMatchLongerWord() {
        assertFalse(GlossaryTermMatch.matchesSourceTerm("줄다리기", "줄"))
        assertFalse(GlossaryTermMatch.matchesSourceTerm("봄날", "봄"))
    }

    @Test
    fun hangulTerm_withParticleInSource_doesNotMatchAnotherParticle() {
        // AI 提取如果存成带助词的形式，别的助词形态就匹配不上。
        // 这不是匹配器要猜的：入库前应该剥掉助词（调用方保证 source 是原形）。
        assertFalse(GlossaryTermMatch.matchesSourceTerm("레오나르도는", "레오나르도가"))
    }

    @Test
    fun latinTerm_requiresWordBoundary() {
        assertFalse(GlossaryTermMatch.matchesSourceTerm("kingdom", "king"))
        assertTrue(GlossaryTermMatch.matchesSourceTerm("the king rules", "king"))
        assertTrue(GlossaryTermMatch.matchesSourceTerm("King's landing", "king"))
    }

    @Test
    fun latinTerm_isCaseInsensitive() {
        assertTrue(GlossaryTermMatch.matchesSourceTerm("EREN is here", "eren"))
        assertTrue(GlossaryTermMatch.matchesSourceTerm("eren is here", "EREN"))
    }

    @Test
    fun nfdKoreanText_matchesNfcTerm() {
        val nfd = Normalizer.normalize("레오나르도가", Normalizer.Form.NFD)
        assertTrue(GlossaryTermMatch.matchesSourceTerm(nfd, "레오나르도"))
    }

    @Test
    fun ocrLineBreakInsideKoreanName_matches() {
        // OCR 把名字从中间换行：归一化后是「레오나 르도가」，紧凑回退要能命中。
        assertTrue(GlossaryTermMatch.matchesSourceTerm("레오나\n르도가", "레오나르도"))
    }

    @Test
    fun blankInputs_doNotMatch() {
        assertFalse(GlossaryTermMatch.matchesSourceTerm("", "레오나르도"))
        assertFalse(GlossaryTermMatch.matchesSourceTerm("레오나르도", ""))
    }
}
