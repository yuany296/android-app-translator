package com.manga.translator.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 移交文本证据单测（2026-10-03）。
 *
 * 两个用例都来自真机现场：只有它们能把「合法续排」和「移交框侵占本图内容」分开 ——
 * 几何上两者都是「本图检出伸出了移交框」。
 */
class HandoffTextMatchTest {

    @Test
    fun genuineContinuationIsExplainedByHandoff() {
        // 真机夹具 `seam-after-line2.html`：下一张图把同一行的下半截读成 `케이지라고요 !`，
        // 上一张移交的原文是 `케이지리고요!!`（OCR 变体）→ 必须判「同一句」→ 保留移交，
        // 否则上一张已经画好的那半句会被本图再翻一遍（缝上两层译文）。
        val overlap = HandoffTextMatch.overlap("케이지라고요 !", "케이지리고요!!")
        assertTrue("合法续排的重合度应 ≥ 0.7（实际 $overlap）", overlap >= HandoffTextMatch.SAME_SENTENCE_RATIO)
        assertTrue(HandoffTextMatch.explainedByHandoff("케이지라고요 !", "케이지리고요!!"))
    }

    @Test
    fun foreignMixedLineIsNotExplainedByHandoff() {
        // 真机 `[32317270]`：本图检出把条带尾行与本图另一段并成了一行
        // （`방해됐단양이o를 하려면 죽거나`），而上一张移交的原文是 `케이지…두 가지 밖에 없는데`。
        // 字符构成差得远 → 必须判「不是同一句」→ 丢弃移交框，本图自己翻那半行。
        val local = "방해됐단양이o를 하려면 죽거나"
        val handoff = "『케이지』라고요!! 접속종료를 하려면 죽거나 접속게이트로 돌아가거나 두 가지 밖에 없는데!!"
        val overlap = HandoffTextMatch.overlap(local, handoff)
        assertTrue("侵占本图的重合度应 < 0.7（实际 $overlap）", overlap < HandoffTextMatch.SAME_SENTENCE_RATIO)
        assertFalse(HandoffTextMatch.explainedByHandoff(local, handoff))
    }

    @Test
    fun missingHandoffTextKeepsLegacyBehaviour() {
        // 旧数据 / 远程链路没有原文 → 没有文本证据时不下结论（保留既有几何行为）
        assertTrue(HandoffTextMatch.explainedByHandoff("아무 말", ""))
        assertTrue(HandoffTextMatch.explainedByHandoff("아무 말", "   "))
    }

    @Test
    fun punctuationAndWhitespaceAreIgnored() {
        assertEquals(
            1f,
            HandoffTextMatch.overlap("케이지라고요", "케이지 , 라고요 !!"),
            0.0001f
        )
    }

    @Test
    fun blankLocalTextIsTreatedAsExplained() {
        // 本图文本为空（纯符号/无法识别）时不做判定，避免把移交整批丢掉
        assertTrue(HandoffTextMatch.explainedByHandoff("", "어떤 문장"))
        assertTrue(HandoffTextMatch.explainedByHandoff("...", "어떤 문장"))
    }

    @Test
    fun partiallyReadContinuationStillCounts() {
        // 只读到半句（字符更少）→ 覆盖率按**本图文本**归一化，仍然高 → 保留移交
        assertTrue(HandoffTextMatch.explainedByHandoff("죽거나", "접속종료를 하려면 죽거나"))
    }
}
