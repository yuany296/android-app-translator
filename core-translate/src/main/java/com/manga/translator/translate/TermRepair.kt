package com.manga.translator.translate

/**
 * 确定性术语修复（2026-11-02，对齐参考项目 `extension/src/shared/term-repair.js`）。
 *
 * 只修一类**字符串事实**：原文里出现了术语，译文里却原样留着术语的原文拼写。
 * 这说明模型这一段没翻。语境判断（该不该用术语）仍然只归模型，这里不碰。
 *
 * 保守边界（宁可少替换，也不要改坏译文）：
 * - 原文里没有该术语 → 一个字都不动；
 * - 韩文术语只吃「合法的助词/称呼后缀尾巴」，尾巴不是助词说明它是更长单词的一部分；
 * - 拉丁术语要求词边界（`king` 不碰 `kingdom`），大小写不敏感；
 * - 译名本身包含原文拼写（`Leo → Leonardo`）无法区分照抄，整条跳过；
 * - 只处理韩文与拉丁原文：汉字/假名原文与译名同形，无从判断。
 */
object TermRepair {

    data class Result(
        val text: String,
        val changes: List<Pair<String, String>>
    )

    fun repair(original: String, translated: String, glossary: List<Pair<String, String>>): String =
        repairWithChanges(original, translated, glossary).text

    fun repairWithChanges(
        original: String,
        translated: String,
        glossary: List<Pair<String, String>>
    ): Result {
        if (original.isBlank() || translated.isBlank() || glossary.isEmpty()) {
            return Result(translated, emptyList())
        }
        val normalizedOriginal = GlossaryTermMatch.normalize(original)
        var text = GlossaryTermMatch.normalize(translated)
        val changes = mutableListOf<Pair<String, String>>()

        val repairable = glossary
            .map { (source, target) ->
                GlossaryTermMatch.normalize(source) to GlossaryTermMatch.normalize(target)
            }
            .filter { (source, target) -> isRepairable(source, target) }
            .distinct()
            // 长词优先，否则短词会先吃掉长词的一部分。
            .sortedByDescending { it.first.length }

        for ((source, target) in repairable) {
            if (!GlossaryTermMatch.matchesSourceTerm(normalizedOriginal, source)) continue
            val next = replaceSpelling(text, source, target)
            if (next != text) {
                text = next
                changes += source to target
            }
        }
        return if (changes.isEmpty()) Result(translated, emptyList()) else Result(text, changes)
    }

    private fun isRepairable(source: String, target: String): Boolean {
        if (source.isEmpty() || target.isEmpty()) return false
        if (source.equals(target, ignoreCase = true)) return false
        // 译名本身包含原文拼写（Leo → Leonardo）：无法区分「照抄」和「本来就该这么写」。
        if (target.contains(source, ignoreCase = true)) return false
        return true
    }

    private fun replaceSpelling(text: String, source: String, target: String): String {
        if (GlossaryTermMatch.isHangulTerm(source)) return replaceHangul(text, source, target)
        if (!GlossaryTermMatch.hasLatinLetterOrDigit(source)) return text
        return replaceLatin(text, source, target)
    }

    /** 韩文术语：整词替换，并连同后面的助词/称呼后缀一起吃掉（中文没有这些成分）。 */
    private fun replaceHangul(text: String, source: String, target: String): String {
        val output = StringBuilder(text.length)
        var index = 0
        var changed = false
        while (index < text.length) {
            val at = text.indexOf(source, index)
            if (at < 0) {
                output.append(text, index, text.length)
                break
            }
            if (GlossaryTermMatch.isHangul(text.getOrNull(at - 1))) {
                // 前面紧挨着韩文字母：只是更长单词的后半截。
                output.append(text, index, at + source.length)
                index = at + source.length
                continue
            }
            var runEnd = at + source.length
            while (runEnd < text.length && GlossaryTermMatch.isHangul(text[runEnd])) runEnd += 1
            val tailLength = GlossaryTermMatch.allowedHangulTailLength(
                text.substring(at + source.length, runEnd)
            )
            if (tailLength < 0) {
                output.append(text, index, at + source.length)
                index = at + source.length
                continue
            }
            output.append(text, index, at).append(target)
            changed = true
            index = skipSpacing(text, runEnd)
        }
        return if (changed) output.toString() else text
    }

    /** 拉丁术语：词边界替换，大小写不敏感。 */
    private fun replaceLatin(text: String, source: String, target: String): String {
        val haystack = text.lowercase()
        val needle = source.lowercase()
        if (needle.isEmpty()) return text
        val output = StringBuilder(text.length)
        var index = 0
        var changed = false
        while (index < text.length) {
            val at = haystack.indexOf(needle, index)
            if (at < 0) {
                output.append(text, index, text.length)
                break
            }
            val previous = text.getOrNull(at - 1)
            val next = text.getOrNull(at + source.length)
            if ((previous != null && GlossaryTermMatch.isLatinLetterOrDigit(previous)) ||
                (next != null && GlossaryTermMatch.isLatinLetterOrDigit(next))
            ) {
                output.append(text, index, at + source.length)
                index = at + source.length
                continue
            }
            output.append(text, index, at).append(target)
            changed = true
            index = skipSpacing(text, at + source.length)
        }
        return if (changed) output.toString() else text
    }

    /**
     * 替换后如果紧接着一个空格、而空格后面不是拉丁字母/数字，就把这个空格吃掉：
     * `레오나르도가 说道。` → `莱昂纳多说道。` 而不是 `莱昂纳多 说道。`
     * （拉丁词之间的空格要留着：`莱昂纳多 Smith`。）
     */
    private fun skipSpacing(text: String, index: Int): Int {
        if (index >= text.length || text[index] != ' ') return index
        val next = text.getOrNull(index + 1)
        return if (next == null || !GlossaryTermMatch.isLatinLetterOrDigit(next)) index + 1 else index
    }
}
