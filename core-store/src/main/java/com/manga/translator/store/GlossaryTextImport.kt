package com.manga.translator.store

import org.json.JSONArray
import org.json.JSONObject

/** 术语表导入格式（文件与粘贴文本共用）。 */
enum class GlossaryImportFormat {
    JSON,
    CSV,
    MARKDOWN,
    UNKNOWN
}

/**
 * 术语表文本的格式识别与解析（2026-10-27 用户反馈「导入时不知道要放什么」）。
 *
 * 支持三种常见来源：
 * 1. JSON：导出的 `{"glossary":[...]}`、裸数组、`{"原文":"译文"}` 键值对；
 * 2. Markdown 表格：`| 原文 | 译文 |` + `| --- | --- |`；
 * 3. CSV：带或不带表头，首列原文、次列译文。
 *
 * 识别顺序是硬的：JSON → Markdown → CSV。Markdown 必须存在表格分隔行或
 * 表头关键字，避免把「值里带竖线」的 CSV 误判成表格。
 */
object GlossaryTextImport {

    private val csvParser = GlossaryRepository.GlossaryCsv

    fun detect(raw: String): GlossaryImportFormat {
        val text = clean(raw)
        if (text.isEmpty()) return GlossaryImportFormat.UNKNOWN
        if (text.startsWith("{") || text.startsWith("[")) {
            return if (parseJson(text)?.isNotEmpty() == true) {
                GlossaryImportFormat.JSON
            } else {
                GlossaryImportFormat.UNKNOWN
            }
        }
        if (GlossaryMarkdown.looksLikeTable(text) && GlossaryMarkdown.parse(text).isNotEmpty()) {
            return GlossaryImportFormat.MARKDOWN
        }
        return if (csvParser.looksLikeGlossaryCsv(text)) {
            GlossaryImportFormat.CSV
        } else {
            GlossaryImportFormat.UNKNOWN
        }
    }

    /** 预检：能解析出多少条术语（0 = 没识别到，可用于导入前的即时提示）。 */
    fun previewCount(raw: String): Int = when (detect(raw)) {
        GlossaryImportFormat.JSON -> parseJson(raw)?.size ?: 0
        GlossaryImportFormat.MARKDOWN -> GlossaryMarkdown.parse(raw).size
        GlossaryImportFormat.CSV -> csvParser.parse(raw).size
        GlossaryImportFormat.UNKNOWN -> 0
    }

    /**
     * 解析 JSON 术语。返回 null = 不是可识别的术语 JSON（而不是「解析成功但 0 条」，
     * 后者会返回空列表，交给调用方决定提示）。
     */
    internal fun parseJson(raw: String): List<Pair<String, String>>? {
        val text = clean(raw)
        if (text.isEmpty()) return null

        val obj = runCatching { JSONObject(text) }.getOrNull()
        if (obj != null) {
            val array = obj.optJSONArray("glossary")
                ?: obj.optJSONArray("entries")
                ?: obj.optJSONArray("terms")
            if (array != null) return pairsFromArray(array)
            // 明确是术语容器但结构不对（比如 entries 不是数组）时，别退化成键值对导进奇怪的东西。
            if (obj.has("glossary") || obj.has("entries") || obj.has("terms")) return null

            // 单条：{"source":"Eren","target":"艾伦"}
            val singleSource = firstString(obj, SOURCE_KEYS)
            val singleTarget = firstString(obj, TARGET_KEYS)
            if (singleSource != null || singleTarget != null) {
                return if (singleSource != null && singleTarget != null) {
                    listOf(singleSource to singleTarget)
                } else {
                    null
                }
            }

            // 键值对：{"Eren":"艾伦","Levi":"利威尔"}
            val keys = obj.keys()
            val pairs = mutableListOf<Pair<String, String>>()
            while (keys.hasNext()) {
                val key = keys.next().trim()
                val value = obj.opt(key)
                if (value !is String) return null
                val target = value.trim()
                if (key.isNotEmpty() && target.isNotEmpty()) pairs += key to target
            }
            return pairs.ifEmpty { null }
        }

        val array = runCatching { JSONArray(text) }.getOrNull() ?: return null
        return pairsFromArray(array)
    }

    internal fun parseMarkdown(raw: String): List<Pair<String, String>> = GlossaryMarkdown.parse(clean(raw))

    private fun clean(raw: String): String = raw.trim().removePrefix("\uFEFF")

    private fun pairsFromArray(array: JSONArray): List<Pair<String, String>>? {
        val pairs = mutableListOf<Pair<String, String>>()
        for (index in 0 until array.length()) {
            when (val item = array.opt(index)) {
                is JSONObject -> {
                    val source = firstString(item, SOURCE_KEYS)
                    val target = firstString(item, TARGET_KEYS)
                    if (source != null && target != null) pairs += source to target
                }
                is JSONArray -> {
                    val source = item.optString(0, "").trim()
                    val target = item.optString(1, "").trim()
                    if (source.isNotEmpty() && target.isNotEmpty()) pairs += source to target
                }
            }
        }
        return pairs.ifEmpty { null }
    }

    private fun firstString(obj: JSONObject, keys: Array<String>): String? {
        for (key in keys) {
            val value = obj.opt(key)
            if (value is String && value.trim().isNotEmpty()) return value.trim()
        }
        return null
    }

    private val SOURCE_KEYS = arrayOf("source", "src", "term", "original", "原文", "原词")
    private val TARGET_KEYS = arrayOf("target", "tgt", "translation", "translated", "译文", "译词")
}

/**
 * Markdown 表格解析：首列原文、次列译文。
 *
 * - 表头 = 分隔行（`| --- | --- |`）的上一行，按 Markdown 语法直接丢弃；
 * - 没有分隔行时，只有首行命中「原文/译文、source/target」等关键字才当表头；
 * - 跳过 ``` / ~~~ 围栏标记本身，围栏里的表格照常解析（方便直接粘贴文档里的示例）；
 * - 支持 `\|` 转义竖线；多于两列时只取前两列。
 */
internal object GlossaryMarkdown {

    private val separatorCell = Regex("^:?-+:?$")
    private val sourceHeaders = setOf("source", "src", "from", "term", "original", "原文", "原词", "术语")
    private val targetHeaders = setOf("target", "tgt", "to", "translation", "translated", "译文", "译词", "翻译")

    fun looksLikeTable(raw: String): Boolean {
        val lines = tableLines(raw)
        if (lines.isEmpty()) return false
        if (lines.any { isSeparator(splitCells(it)) }) return true
        // 无分隔行时，至少要有表头样式的两行，避免把单行「A | B」误判成表格。
        return lines.size >= 2 && looksLikeHeader(splitCells(lines.first()))
    }

    fun parse(raw: String): List<Pair<String, String>> {
        val rows = mutableListOf<List<String>>()
        for (line in tableLines(raw)) {
            val cells = splitCells(line)
            if (isSeparator(cells)) {
                // Markdown 表格的表头是分隔行的上一行。
                if (rows.isNotEmpty()) rows.removeAt(rows.lastIndex)
                continue
            }
            rows += cells
        }
        // 没有分隔行时，按关键字识别表头。
        if (rows.isNotEmpty() && looksLikeHeader(rows.first())) rows.removeAt(0)
        return rows.mapNotNull { cells ->
            val source = cells.getOrNull(0)?.trim().orEmpty()
            val target = cells.getOrNull(1)?.trim().orEmpty()
            if (source.isEmpty() || target.isEmpty()) null else source to target
        }
    }

    private fun tableLines(raw: String): List<String> {
        val lines = mutableListOf<String>()
        raw.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            // 只跳过围栏标记本身；围栏里的表格要能直接粘贴。
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) return@forEach
            if (trimmed.contains('|')) lines += trimmed
        }
        return lines
    }

    private fun splitCells(line: String): List<String> {
        var body = line.trim()
        if (body.startsWith("|")) body = body.substring(1)
        if (body.endsWith("|") && !body.endsWith("\\|")) body = body.dropLast(1)
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var index = 0
        while (index < body.length) {
            val char = body[index]
            if (char == '\\' && index + 1 < body.length && body[index + 1] == '|') {
                current.append('|')
                index += 2
                continue
            }
            if (char == '|') {
                cells += current.toString()
                current.setLength(0)
            } else {
                current.append(char)
            }
            index++
        }
        cells += current.toString()
        return cells.map { it.trim() }
    }

    private fun isSeparator(cells: List<String>): Boolean {
        if (cells.size < 2) return false
        return cells.all { separatorCell.matches(it.trim()) }
    }

    private fun looksLikeHeader(cells: List<String>): Boolean {
        if (cells.size < 2) return false
        val source = cells[0].trim().lowercase()
        val target = cells[1].trim().lowercase()
        return source in sourceHeaders && target in targetHeaders
    }
}
