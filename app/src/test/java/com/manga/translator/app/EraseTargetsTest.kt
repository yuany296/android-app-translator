package com.manga.translator.app

import com.manga.translator.translate.Bubble
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「读不出就别擦」的判据测试。
 *
 * 依据用户 2026-09-30 的反馈与实测（`logs/quality-20260930/outline/r4-seam-none.logcat`）：
 * 夹具页右缘竖排日文在源语言=韩文时读不出内容，却照样被擦 → 白块。判据必须**不做语言假设**。
 */
class EraseTargetsTest {

    private fun bubble(text: String) = Bubble(
        x = 0f,
        y = 0f,
        w = 20f,
        h = 40f,
        bgType = "none",
        originalText = text,
        translatedText = text
    )

    @Test
    fun normalKoreanTextIsWorthErasing() {
        assertTrue(isWorthErasing(bubble("기울어진 텍스트")))
    }

    @Test
    fun japaneseTextIsWorthErasing() {
        assertTrue(isWorthErasing(bubble("これは縦書きです")))
    }

    @Test
    fun singleHangulSyllableIsWorthErasing() {
        assertTrue("单个谚文字是真实内容，不能当噪声", isWorthErasing(bubble("괜")))
    }

    @Test
    fun singleKanaIsWorthErasing() {
        assertTrue("单个假名是真实内容", isWorthErasing(bubble("は")))
    }

    @Test
    fun singleLatinLetterIsWorthErasing() {
        assertTrue("单字母属于「识别到了内容」，不是零识别", isWorthErasing(bubble("M")))
    }

    @Test
    fun digitsAreWorthErasing() {
        assertTrue(isWorthErasing(bubble("12")))
    }

    @Test
    fun emptyTextIsNotWorthErasing() {
        assertFalse("零识别区域擦了只会留白块", isWorthErasing(bubble("")))
    }

    @Test
    fun blankTextIsNotWorthErasing() {
        assertFalse(isWorthErasing(bubble("   \n ")))
    }

    @Test
    fun singlePipeIsNotWorthErasing() {
        assertFalse("`|` 是实测出现过的 OCR 噪声形态", isWorthErasing(bubble("|")))
    }

    @Test
    fun punctuationNoiseIsNotWorthErasing() {
        assertFalse(isWorthErasing(bubble("。")))
        assertFalse(isWorthErasing(bubble("!!")))
        assertFalse(isWorthErasing(bubble("..")))
    }

    @Test
    fun prolongedSoundMarkCountsAsContent() {
        // 「ー」(U+30FC) 在 Unicode 里是字母类（Lm），而且它可能是「コーヒー」这类真实内容被切碎的
        // 产物 —— 保守起见按内容处理（擦），避免把真实原文判成噪声而留下未覆盖的残字。
        assertTrue(isWorthErasing(bubble("ー")))
    }

    @Test
    fun longerSymbolRunIsTreatedAsContent() {
        assertTrue("超过 2 个字符的符号串可能是真实标点内容，保守起见仍然擦", isWorthErasing(bubble("!!!")))
    }

    @Test
    fun mixedScriptShortFragmentIsNotWorthErasing() {
        // 真机证据（2026-09-30，traceId 79409133）：右缘竖排日文在韩文模型下被读成
        // `J유t,`（拉丁+谚文混合碎片）—— 用户预判的「也有可能不是日文」形态。
        // 擦掉它只会把原文换成垃圾，所以判为「不值得擦」，原样保留。
        assertTrue(isFragmentNoise("J유t,"))
        assertFalse("混合碎片必须整块跳过（旗舰反例）", isWorthErasing(bubble("J유t,")))
        assertFalse("正常韩文台词不受影响", isFragmentNoise("기울어진 텍스트"))
    }

    @Test
    fun mixedScriptFragmentBelowLengthCapOnly() {
        // 长度上限内才拦：真实内容里合法的混排长句照常擦。
        assertFalse("长度超过上限的混排不算碎片", isFragmentNoise("아이폰 15 Pro 최고"))
        assertTrue("5 字符以内的跨系统混排按碎片处理", isFragmentNoise("A가b"))
    }

    @Test
    fun singleScriptShortTextIsStillWorthErasing() {
        // 单一文字系统的短句是真实内容：OK!（拉丁）、하!（谚文）、読む（假名+汉字同属 CJK 一家）。
        assertTrue(isWorthErasing(bubble("OK!")))
        assertTrue(isWorthErasing(bubble("하!")))
        assertTrue("假名+汉字合法混用，不能算两种文字系统", isWorthErasing(bubble("読む")))
        assertFalse(isFragmentNoise("OK!"))
        assertFalse(isFragmentNoise("読む"))
    }

    @Test
    fun diagCountsSkippedTargets() {
        val bubbles = listOf(bubble("기울어진 텍스트"), bubble(""), bubble("|"))
        val diag = eraseTargetsDiag(bubbles)
        assertEquals(
            "total=3 erasable=1 skipped=2 skipped_text=[] [|]",
            diag
        )
    }

    @Test
    fun diagReportsNoneWhenEverythingIsErasable() {
        val diag = eraseTargetsDiag(listOf(bubble("안녕"), bubble("世界")))
        assertEquals("total=2 erasable=2 skipped=0 skipped_text=none", diag)
    }

    @Test
    fun detailDiagReportsTextCodesAndVerdict() {
        // 逐区域诊断存在的唯一理由：真机实测里 `erase_targets` 打的是 skipped=0，看不出任何区域
        // 的原文是什么。这条测试锁住「无条件打印每个区域的原文 + 码点 + 判定」这个契约。
        val diag = eraseTargetDetailDiag(1, bubble("|"))
        assertEquals("idx=1 keep=N bg=none box=20x40@0,0 len=1 codes=[U+007C] text=[|]", diag)
    }

    @Test
    fun detailDiagMarksErasableRegionAsKeep() {
        val diag = eraseTargetDetailDiag(0, bubble("괜"))
        assertEquals("idx=0 keep=Y bg=none box=20x40@0,0 len=1 codes=[U+AD1C] text=[괜]", diag)
    }

    @Test
    fun detailDiagEscapesNewlinesSoOneRegionStaysOneLogLine() {
        val diag = eraseTargetDetailDiag(2, bubble("가\n나"))
        assertTrue("换行必须转义，否则一条 logcat 会被拆成多行", diag.endsWith("len=3 codes=[U+AC00,U+000A,U+B098] text=[가\\n나]"))
    }

    @Test
    fun detailDiagCapsCodePointListing() {
        val diag = eraseTargetDetailDiag(0, bubble("abcdefghijklmnopqrstuvwxyz0123"))
        val codes = diag.substringAfter("codes=[").substringBefore("]")
        assertEquals("码点列表要截断，避免超长文本把 logcat 撑爆", 24, codes.split(",").size)
    }
}
