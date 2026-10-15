package com.manga.translator.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「提示用探测」判据（2026-10-15，用户报「配日文翻韩漫看不出语言选错了」）。
 *
 * 根因：Paddle 各语种 rec 词典互不相通（实测 ja 词典 0 谚文、ko 词典 0 假名），
 * 配错语言时首遍输出是模型自己词典里的字形噪声，脚本检测在噪声上永远判「与配置同族」。
 * 这组单测钉住：什么时候值得探测、拿哪些姊妹模型探、探测结果要多少证据才采信。
 */
class ScriptProbePolicyTest {

    // ---- 候选模型 ----

    @Test
    fun probeCandidates_coverTheOtherTwoCjkLanguages() {
        assertEquals(listOf("ko", "zh-cn"), SourceScriptDetector.probeCandidateLanguages("ja"))
        assertEquals(listOf("ja", "zh-cn"), SourceScriptDetector.probeCandidateLanguages("ko"))
        assertEquals(listOf("ja", "ko"), SourceScriptDetector.probeCandidateLanguages("zh-cn"))
        assertEquals(listOf("ja", "ko"), SourceScriptDetector.probeCandidateLanguages("zh-tw"))
        // en 配置没有候选：ZH 模型读拉丁字母哪儿都在，首遍判据够用
        assertTrue(SourceScriptDetector.probeCandidateLanguages("en").isEmpty())
    }

    // ---- 触发判据 ①：配置语言的字符占比过低 ----

    @Test
    fun needsProbe_whenConfiguredScriptCharsNearlyAbsent_jaOnKoreanPage() {
        // 配 ja 读韩文：ja 模型输出里没有假名（词典就没有），全是拉丁/汉字噪声
        assertTrue(
            SourceScriptDetector.needsScriptProbe(
                texts = listOf("nyeo beonjjeokhae georieul georeume", "bareun seonmuri heullin daeumi"),
                configuredLanguageId = "ja",
                avgConfidence = 0.9f
            )
        )
    }

    @Test
    fun noProbe_whenConfiguredScriptWellRepresented() {
        // 真·日文页配 ja：假名占比高，不值得探测
        assertFalse(
            SourceScriptDetector.needsScriptProbe(
                texts = listOf("なんだこれはすごい", "本当にありがとう、またね。"),
                configuredLanguageId = "ja",
                avgConfidence = 0.95f
            )
        )
    }

    @Test
    fun noProbe_whenTextTooShortToJudge() {
        // 两三个字的短页既证明不了「选对」也证明不了「选错」
        assertFalse(
            SourceScriptDetector.needsScriptProbe(
                texts = listOf("ok fine"),
                configuredLanguageId = "ja",
                avgConfidence = 0.3f
            )
        )
    }

    // ---- 触发判据 ②：置信度普遍偏低（覆盖「垃圾字形被硬读成配置语言」的镜像组合） ----

    @Test
    fun needsProbe_whenConfidenceIsSystematicallyLow() {
        // ko 模型读日文会把假名硬读成谚文垃圾 —— 脚本占比看不出来，分数看得出来
        assertTrue(
            SourceScriptDetector.needsScriptProbe(
                texts = listOf("케이지라고요 접속종료를", "하려면 죽거나 돌아가거나"),
                configuredLanguageId = "ko",
                avgConfidence = 0.61f
            )
        )
    }

    @Test
    fun noProbe_whenFamilyCharsStrongAndConfidenceHigh() {
        // 真·韩文页配 ko：谚文占比远超 15%、分数高 —— 两个判据都不触发
        assertFalse(
            SourceScriptDetector.needsScriptProbe(
                texts = listOf(
                    "응 없어 그렇게 말해도 되는 거야",
                    "어디 가야 되는데 지금 상황에서는",
                    "접속 종료를 하려면 죽거나 돌아가거나"
                ),
                configuredLanguageId = "ko",
                avgConfidence = 0.94f
            )
        )
    }

    @Test
    fun noProbe_forEnglishConfig() {
        assertFalse(
            SourceScriptDetector.needsScriptProbe(
                texts = listOf("random latin words without any kana"),
                configuredLanguageId = "en",
                avgConfidence = 0.5f
            )
        )
    }

    // ---- 证据门槛：探测结果要「多到不像噪声」才采信 ----

    @Test
    fun probeThreshold_isHigherThanDetectMinimum() {
        assertTrue(SourceScriptDetector.probeEvidenceThreshold("ko") > SourceScriptDetector.HANGUL_MIN)
        assertTrue(SourceScriptDetector.probeEvidenceThreshold("ja") > SourceScriptDetector.KANA_MIN)
        assertTrue(SourceScriptDetector.probeEvidenceThreshold("zh-cn") > SourceScriptDetector.HAN_MIN)
    }

    @Test
    fun countScript_bucketsByFamily() {
        val counts = SourceScriptDetector.countScript(listOf("한국어 2글자", "にゃあ"))
        assertEquals(5, counts.hangul)
        assertEquals(3, counts.kana)
        assertEquals(8, counts.total)
    }
}
