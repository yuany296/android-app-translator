package com.manga.translator.translate

/**
 * 判断一段 OCR 文本是否「语言中立、无需翻译」。
 *
 * 仅排除无论源语言/目标语言如何、原样展示即为正确结果的内容：
 * 纯数字、数字时钟/日期、纯符号/emoji、URL/域名/邮箱、字母数字编码、全同字符噪声。
 * 这类文本不调用翻译引擎（省时省钱），直接显示原文，且不写入句子缓存。
 *
 * 注意：含 CJK 的时间/数量词（午前8時、第3話、100万円）仍需翻译，不在此列。
 */
object NonTranslatableText {

    fun isNonTranslatable(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true
        val compact = trimmed.filterNot { it.isWhitespace() }.lowercase()
        if (compact.isEmpty()) return true

        // URL / 域名 / 邮箱
        if (compact.contains("http") || compact.contains("www.") ||
            compact.contains(".com") || compact.contains(".net") || compact.contains(".org") ||
            compact.contains("@")
        ) {
            return true
        }
        // 全同字符噪声（IIII、!!!! 等 OCR 噪声/占位）；
        // CJK/假名重复字符是拟声词（ドドド、ああああ），仍需翻译，不在此列。
        if (compact.length <= 4 && compact.all { it == compact[0] } && !isCjkCharacter(compact[0])) return true
        // 纯符号 / emoji（不含任何字母与数字）
        if (compact.none { it.isLetterOrDigit() }) return true
        // 纯数字（含千分位/小数点/百分号/正负号/全角数字）
        if (DIGIT_ONLY.matches(trimmed)) return true
        // 数字时钟（3:30、12:00、9:30:15、3:30pm）
        if (CLOCK_TIME.matches(trimmed)) return true
        // 数字日期（2024/1/1、2024-01-01、12.25）
        if (DIGIT_DATE.matches(trimmed)) return true
        // 字母数字编码（No.5、B-12、Vol.3、3D、4K）
        return ALNUM_CODE.matches(compact)
    }

    private val DIGIT_ONLY = Regex(
        "^[+\uFF0B\\-\uFF0D]?(\\d|[\uFF10-\uFF19])[\\d\uFF10-\uFF19,\uFF0C.\uFF0E\\s]*[%％‰]?$"
    )

    private val CLOCK_TIME = Regex(
        "^\\d{1,2}[:：]\\d{2}([:：]\\d{2})?\\s*(?:am|pm|a\\.?m\\.?|p\\.?m\\.?)?$",
        RegexOption.IGNORE_CASE
    )

    private val DIGIT_DATE = Regex(
        "^\\d{1,4}[-/.／][\\d\uFF10-\uFF19]{1,2}([-/.／][\\d\uFF10-\uFF19]{1,2})?$"
    )

    private val ALNUM_CODE = Regex(
        "^[a-z]{0,4}\\.?[-]?\\d{1,6}$|^\\d{1,4}[a-z]{1,3}$"
    )

    /**
     * 平假名/片假名/汉字/谚文等 CJK 字符：重复出现多为拟声词，不视为噪声。
     */
    private fun isCjkCharacter(c: Char): Boolean {
        val code = c.code
        return code in 0x3040..0x30FA ||
            code in 0x3400..0x4DBF ||
            code in 0x4E00..0x9FFF ||
            code in 0xAC00..0xD7AF ||
            code in 0xFF66..0xFF9F
    }
}
