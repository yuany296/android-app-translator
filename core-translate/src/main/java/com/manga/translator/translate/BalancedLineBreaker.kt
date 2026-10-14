package com.manga.translator.translate

/**
 * 像素感知均衡断行（2026-10-14，与 `app/src/main/assets/mt-overlay-renderer.js` 的
 * `breakBalancedLines` **同权重**）。
 *
 * ## 为什么
 *
 * 用户反馈两类排版问题：
 * 1. **有些行只有一两个字** —— 贪心换行的经典毛病（末行孤字）；
 * 2. **断点落在词中间** —— 字符数均衡把「讨｜厌它」劈成两行。
 *
 * 这一层用「分词（ICU 词典分词）+ 实测宽度 + 动态规划」替换贪心：断点只在词边界，
 * 标点优先/禁则（行首不出现收尾标点、行尾不出现开引号），每行宽度尽量不超过可用宽度，
 * 行与行按**渲染宽度**均衡。
 *
 * ## 三条渲染链路共用
 *
 * - 浏览器 DOM（JS 同名函数）；
 * - 悬浮球覆盖层 `OverlayController`（TextView + StaticLayout）；
 * - 覆盖层关闭时的整图 PNG `WebtoonImageTranslator.drawText`。
 *
 * 三条链路的权重必须一致，否则同一次翻译在不同链路下断行不同。
 *
 * ## 约束「行数不变」
 *
 * 调用方都先用原字号/字号拟合拿到**贪心行数**，再调这里断成同样的行数 ——
 * 行数变化会让文字块高度变化、刚拟合好的字号失效。断不开（无解/已含换行/行数≤1）
 * 返回 null，调用方保持原文本。
 */
object BalancedLineBreaker {

    /** 分词单元：`sepAfter` = 段后空白（换行时行尾空格会被折叠，对齐时按保守处理）。 */
    data class Unit(
        val text: String,
        val sepAfter: String = "",
        val wordLike: Boolean = true
    )

    /** 行首不能出现的收尾标点（会挂在前一行末尾）。 */
    private val CLOSING_PUNCT = Regex("[。，、！？；：）］｝」』〉》’”…‥・,!?;:)\\]}]")

    /** 行尾不能出现的开引/开括号（会挂到下一行开头）。 */
    private val OPENING_PUNCT = Regex("[（［｛「『〈《“‘〔【(\\[{]")

    /** CJK 字符（含假名/谚文）：超宽单元拆字用。 */
    private val CJK_CHAR = Regex("[\\u3400-\\u9fff\\u3040-\\u30ff\\uac00-\\ud7af]")

    /** 断点落在标点之后：折扣（优先在标点后断行）。 */
    const val PUNCT_BONUS = 60f

    /** 行首收尾标点 / 行尾开括号：重罚（几乎禁止）。 */
    const val FORBIDDEN_PENALTY = 600f

    /** 单单元成行（一个词占一行）：丑。 */
    const val SINGLE_UNIT_PENALTY = 120f

    /** 末行过短（≤2 单元）：丑。 */
    const val SHORT_LAST_PENALTY = 45f

    /** 超宽：尽量别发生；无解时允许（字号拟合/CSS 兜底）。 */
    const val OVERFLOW_WEIGHT = 8f
    const val OVERFLOW_FLAT = 400f

    /** 均衡断行最多支持几行（与渲染层 JS 的上限一致）。 */
    const val MAX_LINES = 8

    /**
     * 动态规划断行：把 [text] 断成恰好 [lineCount] 行。
     *
     * @param measure 文本宽度测量（像素）。
     * @param tokenize 分词（默认 ICU 词典分词；测试可注入）。
     * @return 断好的行列表；无解（单元数不够/行数非法）返回 null。
     */
    fun breakLines(
        text: String,
        lineCount: Int,
        maxWidthPx: Float,
        measure: (String) -> Float,
        tokenize: (String) -> List<Unit> = { segmentWithIcu(it) }
    ): List<String>? {
        val raw = text.replace(Regex("\\s+"), " ").trim()
        val lines = lineCount.coerceIn(1, MAX_LINES)
        if (raw.isEmpty() || lines <= 1 || raw.indexOf('\n') >= 0 || maxWidthPx <= 0f) return null
        var units = tokenize(raw)
        if (units.size <= lines) return null
        units = splitOverwideUnits(units, maxWidthPx, measure)
        val widths = units.map { (measure(unitText(it))).coerceAtLeast(0f) }
        return plan(units, widths, lines, maxWidthPx)
    }

    /** 单元有效宽度 = 文本宽 + 段后空白宽。 */
    private fun unitText(unit: Unit): String = unit.text + unit.sepAfter

    /** 贪心换行需要几行（调用方用它拿「当前行数」，保证重排前后行数一致）。 */
    fun countGreedyLines(
        text: String,
        maxWidthPx: Float,
        measure: (String) -> Float,
        tokenize: (String) -> List<Unit> = { segmentWithIcu(it) }
    ): Int {
        if (text.isEmpty() || maxWidthPx <= 0f) return 0
        var total = 0
        text.split('\n').forEach { segment ->
            val trimmed = segment.trim()
            if (trimmed.isEmpty()) return@forEach
            val units = tokenize(trimmed)
            if (units.isEmpty()) return@forEach
            val widths = units.map { measure(unitText(it)).coerceAtLeast(0f) }
            var lines = 1
            var used = 0f
            widths.forEach { width ->
                if (used > 0f && used + width > maxWidthPx) {
                    lines += 1
                    used = width
                } else {
                    used += width
                }
            }
            total += lines
        }
        return total
    }

    /** 单个 CJK 单元比一行还宽（长词/无标点长串）时拆成单字，否则 DP 只能让它超宽。 */
    internal fun splitOverwideUnits(
        units: List<Unit>,
        maxWidthPx: Float,
        measure: (String) -> Float
    ): List<Unit> {
        val result = ArrayList<Unit>(units.size)
        units.forEach { unit ->
            val width = measure(unitText(unit))
            if (width <= maxWidthPx || !CJK_CHAR.containsMatchIn(unit.text)) {
                result += unit
                return@forEach
            }
            val chars = unit.text.toList()
            chars.forEachIndexed { index, ch ->
                result += Unit(
                    text = ch.toString(),
                    sepAfter = if (index == chars.size - 1) unit.sepAfter else "",
                    wordLike = false
                )
            }
        }
        return result
    }

    /**
     * DP 主体（与 JS 同权重）：
     * 代价 = Σ(行宽 − 目标行宽)² + 单单元成行罚 + 末行过短罚 + 超宽重罚
     *        − 标点后断行折扣（≥0）+ 行首收尾标点/行尾开括号重罚。
     */
    private fun plan(units: List<Unit>, widths: List<Float>, lines: Int, maxWidthPx: Float): List<String>? {
        val n = units.size
        if (n < lines) return null
        val prefix = FloatArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + widths[i]
        val target = prefix[n] / lines.toFloat()
        val inf = Float.MAX_VALUE / 8f

        val cost = Array(lines + 1) { FloatArray(n + 1) { inf } }
        val back = Array(lines + 1) { IntArray(n + 1) { -1 } }
        cost[0][0] = 0f
        for (line in 1..lines) {
            for (end in line..(n - (lines - line))) {
                var best = inf
                var bestStart = -1
                for (start in (line - 1) until end) {
                    val previous = cost[line - 1][start]
                    if (previous >= inf) continue
                    val width = prefix[end] - prefix[start]
                    var lineCost = previous + (width - target) * (width - target)
                    if (width > maxWidthPx) {
                        val over = width - maxWidthPx
                        lineCost += over * over * OVERFLOW_WEIGHT + OVERFLOW_FLAT
                    }
                    val unitsOnLine = end - start
                    val firstText = units[start].text
                    val lastText = units[end - 1].text
                    if (unitsOnLine == 1) lineCost += SINGLE_UNIT_PENALTY
                    if (end == n && unitsOnLine <= 2) lineCost += SHORT_LAST_PENALTY
                    if (firstText.isNotEmpty() && CLOSING_PUNCT.matches(firstText.take(1))) {
                        lineCost += FORBIDDEN_PENALTY
                    }
                    if (lastText.isNotEmpty() && OPENING_PUNCT.matches(lastText.takeLast(1))) {
                        lineCost += FORBIDDEN_PENALTY
                    }
                    if (lastText.isNotEmpty() && CLOSING_PUNCT.matches(lastText.takeLast(1))) {
                        lineCost -= PUNCT_BONUS
                    }
                    if (lineCost < best) {
                        best = lineCost
                        bestStart = start
                    }
                }
                cost[line][end] = best
                back[line][end] = bestStart
            }
        }
        if (cost[lines][n] >= inf) return null
        val rows = arrayOfNulls<String>(lines)
        var cursor = n
        for (line in lines downTo 1) {
            val start = back[line][cursor]
            if (start < 0) return null
            val builder = StringBuilder()
            for (index in start until cursor) {
                builder.append(units[index].text).append(units[index].sepAfter)
            }
            rows[line - 1] = builder.toString().trimEnd()
            cursor = start
        }
        return rows.map { it ?: "" }
    }

    /**
     * ICU 词典分词（`android.icu.text.BreakIterator`）：CJK 按词切（「讨厌」不被劈开），
     * 标点/空白各自成段（空白挂到前一段的 `sepAfter`）。
     *
     * 纯 JVM 单测里 `android.icu` 不可用（not mocked）→ 捕获后回退到
     * [fallbackSegment]（拉丁按空格、CJK 逐字）；单测请直接用假分词器注入。
     */
    internal fun segmentWithIcu(text: String): List<Unit> {
        return runCatching {
            val iterator = android.icu.text.BreakIterator.getWordInstance(java.util.Locale.getDefault())
            iterator.setText(text)
            val units = ArrayList<Unit>(text.length / 2 + 2)
            var start = iterator.first()
            var end = iterator.next()
            while (end != android.icu.text.BreakIterator.DONE) {
                val segment = text.substring(start, end)
                if (segment.isBlank()) {
                    if (units.isNotEmpty()) {
                        val last = units[units.size - 1]
                        units[units.size - 1] = last.copy(sepAfter = " ")
                    }
                } else {
                    units += Unit(
                        text = segment,
                        sepAfter = "",
                        wordLike = segment.firstOrNull()?.isLetterOrDigit() == true
                    )
                }
                start = end
                end = iterator.next()
            }
            units
        }.getOrElse { fallbackSegment(text) }
    }

    /** 无 ICU 时的退化分词：拉丁按空格切词；CJK/标点逐字。 */
    internal fun fallbackSegment(text: String): List<Unit> {
        val units = ArrayList<Unit>(text.length)
        val buffer = StringBuilder()
        fun flush() {
            if (buffer.isNotEmpty()) {
                units += Unit(text = buffer.toString(), sepAfter = "", wordLike = true)
                buffer.setLength(0)
            }
        }
        text.forEach { ch ->
            when {
                ch.isWhitespace() -> {
                    flush()
                    if (units.isNotEmpty()) {
                        val last = units[units.size - 1]
                        if (last.sepAfter.isEmpty()) units[units.size - 1] = last.copy(sepAfter = " ")
                    }
                }
                else -> {
                    val single = ch.toString()
                    if (CJK_CHAR.matches(single) ||
                        CLOSING_PUNCT.matches(single) ||
                        OPENING_PUNCT.matches(single)
                    ) {
                        flush()
                        units += Unit(text = single, sepAfter = "", wordLike = CJK_CHAR.matches(single))
                    } else {
                        buffer.append(ch)
                    }
                }
            }
        }
        flush()
        return units
    }
}
