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
        val counts = countScript(texts)
        return when {
            counts.hangul >= HANGUL_MIN -> Detected("ko", counts.hangul)
            counts.kana >= KANA_MIN -> Detected("ja", counts.kana)
            counts.han >= HAN_MIN -> Detected(if (counts.traditionalOnlySeen) "zh-tw" else "zh-cn", counts.han)
            counts.latin >= LATIN_MIN -> Detected("en", counts.latin)
            else -> null
        }
    }

    /** 各脚本字符的计数（[detect] 与「要不要探测」共用同一套口径）。 */
    data class ScriptCounts(
        val hangul: Int,
        val kana: Int,
        val han: Int,
        val latin: Int,
        val traditionalOnlySeen: Boolean = false
    ) {
        val total: Int get() = hangul + kana + han + latin
    }

    fun countScript(texts: List<String>): ScriptCounts {
        if (texts.isEmpty()) return ScriptCounts(0, 0, 0, 0)
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
        return ScriptCounts(hangul, kana, han, latin, traditionalOnlySeen)
    }

    /**
     * 「提示用探测」的候选模型语言（2026-10-15）。
     *
     * 背景：Paddle 的 rec 模型按语种分家、词典互不相通 —— ja 词典 **0 个谚文**、ko 词典
     * **0 个假名**。配错语言时首遍输出全是「模型自己词典里的字形噪声」（配 ja 读韩文 →
     * 假名/汉字垃圾），[detect] 在这份垃圾上永远判出「与配置同族」，提示永远不出现
     * （用户实测：配日文翻韩漫，毫无提示）。
     *
     * 探测 = 用姊妹 rec 模型把同一张图重读一遍，**结果只喂提示**，不动 OCR 结果与翻译。
     * 只给 CJK 配置发候选：en 配置读拉丁字母哪儿都在，首遍判据够用。
     */
    fun probeCandidateLanguages(configuredLanguageId: String): List<String> =
        when (configuredLanguageId.lowercase()) {
            "ja" -> listOf("ko", "zh-cn")
            "ko" -> listOf("ja", "zh-cn")
            "zh-cn", "zh-tw", "zh" -> listOf("ja", "ko")
            else -> emptyList()
        }

    /** 探测结果被采信的最低证据（比 [detect] 的门槛高：垃圾字形偶尔也能凑出 1-2 个字符）。 */
    fun probeEvidenceThreshold(languageId: String): Int = when (languageId.lowercase()) {
        "ko" -> 8
        "ja" -> 3
        else -> 8
    }

    /** 首遍输出里「配置语言的字符」低于这个占比（且有足够文本量）= 模型读的多半不是配置语言。 */
    private const val PROBE_FAMILY_RATIO = 0.15f

    /** 文本量低于这个数时不探测：几句话既证明不了「选对」也证明不了「选错」。 */
    private const val PROBE_MIN_EVIDENCE_CHARS = 20

    /** 首遍平均置信度低于这个值也值得探测（模型读「不是自己语言」的字时分数系统性偏低）。 */
    const val PROBE_WEAK_CONFIDENCE = 0.75f

    /**
     * 要不要做「提示用探测」：两条判据任一成立 ——
     * ① 首遍输出里配置语言的字符占比 < 15%（覆盖 ja↔ko：词典里根本没有对方字符，
     *    输出要么是噪声、要么干脆没有配置语言的证据）；
     * ② 平均置信度 < [PROBE_WEAK_CONFIDENCE]（覆盖反向组合：ko 词典虽无假名，但模型会把
     *    假名字形**硬读成**谚文垃圾 —— 脚本占比看不出来，分数看得出来）。
     *
     * 判不出置信度（无分数）时按「分数充足」处理，只走判据 ①。
     */
    fun needsScriptProbe(
        texts: List<String>,
        configuredLanguageId: String,
        avgConfidence: Float
    ): Boolean {
        if (probeCandidateLanguages(configuredLanguageId).isEmpty()) return false
        val counts = countScript(texts)
        if (counts.total < PROBE_MIN_EVIDENCE_CHARS) return false
        val familyChars = when (configuredLanguageId.lowercase()) {
            "ja" -> counts.kana
            "ko" -> counts.hangul
            "zh-cn", "zh-tw", "zh" -> counts.han
            else -> return false
        }
        if (familyChars < (counts.total * PROBE_FAMILY_RATIO)) return true
        if (avgConfidence > 0f && avgConfidence < PROBE_WEAK_CONFIDENCE) return true
        return false
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
