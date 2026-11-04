package com.manga.translator.translate

import java.text.Normalizer

/**
 * 术语原文匹配（2026-11-02）。
 *
 * 只回答「这段文本里有没有出现这个术语」。提示词过滤、句子缓存指纹、确定性术语修复
 * 三处共用同一套判据，避免各写一个 `contains` 后行为漂移。
 *
 * 规则对齐参考项目 `extension/src/shared/glossary.js#matchesSourceTerm`：
 * - 韩文：术语后只允许跟「合法助词/称呼后缀」链（이/가/은/는/을/를/님/씨…），
 *   前面紧挨韩文字母不算命中（`줄` 不命中 `줄다리기`）；
 * - 拉丁：大小写不敏感 + 词边界（`king` 不命中 `kingdom`）；
 * - 其他文字：直接 contains（汉字/假名与目标同形，无法再细分）。
 *
 * 额外补了 NFC 归一化与空白折叠：韩文 OCR 可能给出 NFD，或在名字中间换行。
 */
object GlossaryTermMatch {

    /**
     * 韩文助词 / 称呼后缀。**长 token 必须排在短 token 前面**，
     * `allowedHangulTailLength` 按顺序做链式剥离（例如 `님이`、`씨는`、`라고요`）。
     */
    private val HANGUL_TAIL_TOKENS = listOf(
        // 多字符尾巴（引用/口语/连接）先放，避免被单字抢先吃掉
        "라고요", "다고요", "냐고요", "자고요", "는데요", "군요", "네요",
        "이라도", "이라고", "이었다", "이고", "이며", "이랑", "이나", "이면", "이야",
        "에서", "에게", "한테", "부터", "까지", "마다", "보다", "처럼", "라도", "밖에",
        "조차", "마저", "커녕", "라고", "였다", "으로", "께서", "하고", "이다",
        "는데", "니까", "으니까", "면서", "지만", "려고", "러", "죠", "요", "고",
        // 单字助词 / 称呼后缀
        "이", "가", "은", "는", "을", "를", "의", "에", "께", "로", "와", "과", "도",
        "만", "나", "면", "랑", "야",
        "님", "씨", "군", "양"
    )

    private val HANGUL_RANGE = '\uAC00'..'\uD7AF'
    private val HANGUL_TERM_RE = Regex("^[\\uAC00-\\uD7AF]+$")
    private val WHITESPACE_RE = Regex("\\s+")

    /** NFC + 去首尾空白 + 压缩空白。缓存指纹、匹配、修复都从这里取同一口径。 */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC)
            .trim()
            .replace(WHITESPACE_RE, " ")

    /**
     * 原文里是否出现了术语 [sourceTerm]。
     *
     * 非拉丁术语在原始匹配失败后还会做一次「去掉所有空格」的紧凑匹配，
     * 兜住 OCR 把韩文/日文名字从中间换行的情况；拉丁不紧凑匹配，
     * 否则 `king dom` 会把 `king` 误判成命中。
     */
    fun matchesSourceTerm(sourceText: String, sourceTerm: String): Boolean {
        val text = normalize(sourceText)
        val term = normalize(sourceTerm)
        if (text.isEmpty() || term.isEmpty()) return false
        if (matchesNormalized(text, term)) return true
        if (!isLatinTerm(term) && (text.contains(' ') || term.contains(' '))) {
            return matchesNormalized(text.replace(" ", ""), term.replace(" ", ""))
        }
        return false
    }

    internal fun isHangulTerm(term: String): Boolean = HANGUL_TERM_RE.matches(term)

    internal fun isLatinTerm(term: String): Boolean {
        if (term.isEmpty()) return false
        for (ch in term) {
            if (ch == ' ' || ch == '-' || ch == '\'' || ch == '’') continue
            if (!isLatinLetterOrDigit(ch)) return false
        }
        return true
    }

    internal fun hasLatinLetterOrDigit(text: String): Boolean =
        text.any { isLatinLetterOrDigit(it) }

    internal fun isLatinLetterOrDigit(ch: Char): Boolean =
        ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch in '\u00C0'..'\u024F'

    internal fun isHangul(ch: Char?): Boolean = ch != null && ch in HANGUL_RANGE

    /**
     * 术语后面那段韩文能全部由助词/后缀吃掉时返回长度，否则 -1。
     * 空尾巴返回 0（术语后面直接是标点/空格也合法）。
     */
    internal fun allowedHangulTailLength(tail: String): Int {
        var index = 0
        while (index < tail.length) {
            val token = HANGUL_TAIL_TOKENS.firstOrNull { tail.startsWith(it, index) }
                ?: return -1
            index += token.length
        }
        return index
    }

    private fun matchesNormalized(text: String, term: String): Boolean {
        return when {
            isHangulTerm(term) -> matchesHangul(text, term)
            isLatinTerm(term) -> matchesLatin(text, term)
            else -> text.contains(term, ignoreCase = true)
        }
    }

    private fun matchesHangul(text: String, term: String): Boolean {
        var offset = text.indexOf(term)
        while (offset >= 0) {
            val previous = text.getOrNull(offset - 1)
            if (!isHangul(previous)) {
                var end = offset + term.length
                while (end < text.length && isHangul(text[end])) end += 1
                val tail = text.substring(offset + term.length, end)
                if (allowedHangulTailLength(tail) >= 0) return true
            }
            offset = text.indexOf(term, offset + term.length)
        }
        return false
    }

    private fun matchesLatin(text: String, term: String): Boolean {
        val haystack = text.lowercase()
        val needle = term.lowercase()
        var offset = haystack.indexOf(needle)
        while (offset >= 0) {
            val previous = text.getOrNull(offset - 1)
            val next = text.getOrNull(offset + needle.length)
            if (!isLatinLetterOrDigitSafe(previous) && !isLatinLetterOrDigitSafe(next)) {
                return true
            }
            offset = haystack.indexOf(needle, offset + needle.length)
        }
        return false
    }

    private fun isLatinLetterOrDigitSafe(ch: Char?): Boolean =
        ch != null && isLatinLetterOrDigit(ch)
}
