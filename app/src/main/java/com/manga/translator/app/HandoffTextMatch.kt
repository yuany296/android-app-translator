package com.manga.translator.app

/**
 * 「本图的这段文字，能不能用上一张图移交过来的原文解释」—— 跨切片移交的**文本证据**。
 *
 * ## 为什么需要（2026-10-03 真机）
 *
 * 移交气泡（[CrossBubblePart]）要不要在本图重画，光靠几何分不开两种形态：
 *
 * | 形态 | 本图检出 | 移交原文 | 几何表现 | 正确处置 |
 * |---|---|---|---|---|
 * | 合法续排 | `케이지라고요 !`（同一行的残缺读法） | `케이지리고요!!` | 伸出移交框 | **保留**移交（同一句只画一次） |
 * | 侵占本图 | `방해됐단양이…를 하려면 죽거나`（条带尾行 + 本图另一段被并成一行） | 上一张那半句 | 伸出移交框 | **丢弃**移交（本图自己翻） |
 *
 * 两者的几何几乎一样（都「伸出移交框」），但**字符构成**分得很开：合法续排 0.83，
 * 侵占本图 0.54。所以用「字符多重集覆盖率」而不是子串包含 —— OCR 变体（`가`↔`리`、
 * `!`↔`?`）会让包含判定直接失败。
 *
 * 纯逻辑、无 Android 依赖，可 JVM 单测。
 */
internal object HandoffTextMatch {

    /**
     * 「同一句」的重合度下限。
     *
     * 取值依据（真机两种形态实测）：合法续排 0.83，侵占本图 0.54 —— 0.7 落在两者中间，
     * 两侧各有 0.13 的余量。
     */
    const val SAME_SENTENCE_RATIO = 0.7f

    /**
     * 本图文本能否用移交原文解释。
     *
     * 移交没带原文（旧数据 / 远程链路）时返回 true —— **没有文本证据就不改变既有行为**。
     */
    fun explainedByHandoff(localText: String, handoffOriginalText: String): Boolean {
        if (handoffOriginalText.isBlank()) return true
        val local = normalize(localText)
        if (local.isEmpty()) return true
        return overlap(localText, handoffOriginalText) >= SAME_SENTENCE_RATIO
    }

    /** 字符多重集覆盖率：本图文本里有多少比例的字符能在移交原文里找到（0~1）。 */
    fun overlap(localText: String, handoffOriginalText: String): Float {
        val local = normalize(localText)
        if (local.isEmpty()) return 1f
        val pool = HashMap<Char, Int>()
        normalize(handoffOriginalText).forEach { ch -> pool[ch] = (pool[ch] ?: 0) + 1 }
        var matched = 0
        local.forEach { ch ->
            val count = pool[ch] ?: 0
            if (count > 0) {
                pool[ch] = count - 1
                matched += 1
            }
        }
        return matched.toFloat() / local.length.toFloat()
    }

    /** 比对用归一化：只留字母/数字（去空白、标点、换行），拉丁转小写。 */
    fun normalize(text: String): String = text.filter { it.isLetterOrDigit() }.lowercase()
}
