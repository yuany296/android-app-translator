package com.manga.translator.translate

/**
 * 源语言脚本检测（2026-10-14「网站语言自动识别」）。
 *
 * 判据对齐参考项目 `C:\homework\translator` 的 `shared/languages.js#resolveSourceLanguage`：
 * 谚文 → ko、假名 → ja、汉字 → zh-cn/zh-tw（按繁体专用字区分）、拉丁 → en；
 * 优先级 **ko > ja > zh > en**（韩文页里偶尔的汉字、日文页里的汉字不抢判）。
 *
 * 用途两处：
 * 1. `EngineSelectingTextDetector`：OCR 首遍结果的语言与配置不符时，换对应模型**重跑一遍**
 *   （Paddle 的 rec 模型按语种分、ML Kit 的识别器也按语种分 —— 配错语言识别质量会很差）；
 * 2. `PageTranslationResolver`：翻译源语言跟随识别结果（句子缓存键也按它走）。
 *
 * 纯 Kotlin、无 Android 依赖，可 JVM 单测。
 */
object SourceScriptDetector {

    /** 识别结果：语言 id（[AppLanguage] 口径）+ 命中该脚本的字符数（证据强度）。 */
    data class Detected(val languageId: String, val scriptChars: Int)

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
     * 从 OCR 文本里检测语言。判不出（无足够脚本证据）返回 null。
     *
     * 全拉丁字母不算「判不出」：纯英文页会返回 `en`，调用方按 [Detected.scriptChars]
     * 决定要不要采信。
     */
    fun detect(texts: List<String>): Detected? {
        if (texts.isEmpty()) return null
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
        return when {
            hangul >= HANGUL_MIN -> Detected("ko", hangul)
            kana >= KANA_MIN -> Detected("ja", kana)
            han >= HAN_MIN -> Detected(if (traditionalOnlySeen) "zh-tw" else "zh-cn", han)
            latin >= LATIN_MIN -> Detected("en", latin)
            else -> null
        }
    }

    /**
     * OCR「识别模型族」：同族 = 换语言重跑不会有本质差别（同一个模型/识别器）。
     *
     * - Paddle：rec 模型只有 ZH/JA/KO 三种（`PaddleModelCatalog.RecModelKey`），
     *   en 走 ZH 模型 —— en 与 zh 同族；
     * - ML Kit：识别器按 en(latin)/ja/ko/zh 分 —— en 单独一族。
     */
    fun recognitionFamily(languageId: String, paddle: Boolean): String {
        return when (languageId.lowercase()) {
            "ko" -> "ko"
            "ja" -> "ja"
            "zh-cn", "zh-tw", "zh" -> "zh"
            else -> if (paddle) "zh" else "en"
        }
    }

    private fun Char.isHangul(): Boolean =
        this in '\uAC00'..'\uD7A3' || this in '\u1100'..'\u11FF' || this in '\u3130'..'\u318F'

    private fun Char.isKana(): Boolean =
        this in '\u3040'..'\u30FF' || this in '\u31F0'..'\u31FF' || this in '\uFF66'..'\uFF9D'

    private fun Char.isHan(): Boolean =
        this in '\u3400'..'\u4DBF' || this in '\u4E00'..'\u9FFF' || this in '\uF900'..'\uFAFF'

    private fun Char.isLatinLetter(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z' || this in '\uFF21'..'\uFF3A' || this in '\uFF41'..'\uFF5A'
}
