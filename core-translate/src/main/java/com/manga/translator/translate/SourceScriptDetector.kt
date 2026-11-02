package com.manga.translator.translate

/**
 * 源语言脚本检测（2026-10-14 引入；2026-10-15 收敛；2026-11-01 扩展到页面语言检测）。
 *
 * 判据对齐参考项目 `C:\homework\translator` 的 `shared/languages.js#resolveSourceLanguage`：
 * 谚文 → ko、假名 → ja、汉字 → zh-cn/zh-tw（按繁体专用字区分）、拉丁 → en；
 * 优先级 **ko > ja > zh > en**（韩文页里偶尔的汉字、日文页里的汉字不抢判）。
 *
 * 生产用途：
 * - 网页文字翻译的「页面语言 == 目标语言 → 整页跳过」；
 * - [PageLanguageDetector] 在 ML Kit 候选不可用/低置信时用脚本计数兜底；
 * - 页面语言识别里区分简体/繁体。
 *
 * 2026-11-01 用户重新要求「网页自动检测源语言」：这里不再承担“跟随翻译语言”的决定，
 * 只负责给出脚本证据；实际是否采用由 [PageLanguageDetector.resolveSource] 按
 * 「浏览器源语言设置 + 页面语言 + 首页兜底语言」决定。
 *
 * 纯 Kotlin、无 Android 依赖，可 JVM 单测。
 */
object SourceScriptDetector {

    /** 识别结果：语言 id（[com.manga.translator.store.AppLanguage] 口径）+ 命中该脚本的字符数。 */
    data class Detected(val languageId: String, val scriptChars: Int)

    /** 一次文本扫描的脚本计数；[traditionalOnlySeen] 只用于区分简/繁。 */
    data class ScriptCounts(
        val hangul: Int,
        val kana: Int,
        val han: Int,
        val latin: Int,
        val traditionalOnlySeen: Boolean
    ) {
        val total: Int get() = hangul + kana + han + latin
    }

    /** 谚文出现 ≥ 这个数才判 ko（单字符容易是噪声）。 */
    const val HANGUL_MIN = 2

    /** 假名是日语的强特征，出现 1 个就够。 */
    const val KANA_MIN = 1

    /** 汉字出现 ≥ 这个数才判 zh。 */
    const val HAN_MIN = 2

    /** 拉丁字母噪声多（OCR 常吐 `cYa` 这类），要求更长才判 en。 */
    const val LATIN_MIN = 6

    /** 繁体专用字样本（简体里不出现的常用字）。命中任一 → zh-tw，否则 zh-cn。 */
    private val traditionalOnly = setOf(
        '們', '這', '說', '對', '話', '個', '來', '沒', '麼', '學', '會', '見', '關', '開', '週',
        '頭', '發', '現', '覺', '體', '靈', '聽', '龍', '國', '還', '進', '遠', '過', '兒', '東',
        '車', '馬', '鳥', '頁', '風', '飛', '飯', '錢', '買', '賣', '難', '電', '時', '間', '問',
        '後', '裡', '爲', '當', '從', '讓', '認', '誰', '謝', '轉', '邊', '醫', '藥', '願', '愛'
    )

    /**
     * 从 OCR/网页文本里检测语言。判不出（无足够脚本证据）返回 null。
     *
     * 全拉丁字母不算「判不出」：纯英文页会返回 `en`，调用方按 [Detected.scriptChars]
     * 决定要不要采信。
     */
    fun detect(texts: List<String>): Detected? {
        val counts = countScripts(texts)
        return when {
            counts.hangul >= HANGUL_MIN -> Detected("ko", counts.hangul)
            counts.kana >= KANA_MIN -> Detected("ja", counts.kana)
            counts.han >= HAN_MIN -> Detected(
                if (counts.traditionalOnlySeen) "zh-tw" else "zh-cn",
                counts.han
            )
            counts.latin >= LATIN_MIN -> Detected("en", counts.latin)
            else -> null
        }
    }

    /** 统计一段/多段文本里的脚本字符数；页面语言检测用它做占比判据。 */
    fun countScripts(texts: Iterable<String>): ScriptCounts {
        var hangul = 0
        var kana = 0
        var han = 0
        var latin = 0
        var traditionalOnlySeen = false
        for (text in texts) {
            for (ch in text) {
                when {
                    ch.isHangul() -> hangul += 1
                    ch.isKana() -> kana += 1
                    ch.isHan() -> {
                        han += 1
                        if (ch in traditionalOnly) traditionalOnlySeen = true
                    }
                    ch.isLatinLetter() -> latin += 1
                }
            }
        }
        return ScriptCounts(
            hangul = hangul,
            kana = kana,
            han = han,
            latin = latin,
            traditionalOnlySeen = traditionalOnlySeen
        )
    }

    /** 中文页面的简/繁判定：命中任一繁体专用字即 zh-tw，否则 zh-cn。 */
    fun chineseVariant(text: String): String =
        if (text.any { it in traditionalOnly }) "zh-tw" else "zh-cn"

    fun hasTraditionalOnlyChar(text: String): Boolean = text.any { it in traditionalOnly }

    private fun Char.isHangul(): Boolean =
        this in '\uAC00'..'\uD7A3' || this in '\u1100'..'\u11FF' || this in '\u3130'..'\u318F'

    private fun Char.isKana(): Boolean =
        this in '\u3040'..'\u30FF' || this in '\u31F0'..'\u31FF' || this in '\uFF66'..'\uFF9D'

    private fun Char.isHan(): Boolean =
        this in '\u3400'..'\u4DBF' || this in '\u4E00'..'\u9FFF' || this in '\uF900'..'\uFAFF'

    private fun Char.isLatinLetter(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '\uFF21'..'\uFF3A' || this in '\uFF41'..'\uFF5A'
}
