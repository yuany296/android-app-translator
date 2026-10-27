package com.manga.translator.store

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 术语表文本导入：格式识别 + JSON / Markdown / CSV 解析。
 *
 * 这张表是「用户到底能粘贴什么」的契约：新增格式先在这里落一条，
 * 避免导入弹窗里的示例与实际解析能力漂移。
 */
class GlossaryTextImportTest {

    // ---------- 格式识别 ----------

    @Test
    fun detect_jsonArray() {
        val raw = """[{"source":"Eren","target":"艾伦"}]"""
        assertEquals(GlossaryImportFormat.JSON, GlossaryTextImport.detect(raw))
    }

    @Test
    fun detect_jsonObjectWithGlossary() {
        val raw = """{"glossary":[{"source":"Eren","target":"艾伦"}]}"""
        assertEquals(GlossaryImportFormat.JSON, GlossaryTextImport.detect(raw))
    }

    @Test
    fun detect_jsonKeyValueObject() {
        val raw = """{"Eren":"艾伦","Levi":"利威尔"}"""
        assertEquals(GlossaryImportFormat.JSON, GlossaryTextImport.detect(raw))
    }

    @Test
    fun detect_jsonSingleEntryObject() {
        val raw = """{"source":"Eren","target":"艾伦"}"""
        assertEquals(GlossaryImportFormat.JSON, GlossaryTextImport.detect(raw))
    }

    @Test
    fun detect_markdownTable() {
        val raw = """
            | 原文 | 译文 |
            | --- | --- |
            | Eren | 艾伦 |
        """.trimIndent()
        assertEquals(GlossaryImportFormat.MARKDOWN, GlossaryTextImport.detect(raw))
    }

    @Test
    fun detect_fencedMarkdownTable() {
        val raw = """
            ```markdown
            | 原文 | 译文 |
            | --- | --- |
            | Eren | 艾伦 |
            ```
        """.trimIndent()
        assertEquals(GlossaryImportFormat.MARKDOWN, GlossaryTextImport.detect(raw))
    }

    @Test
    fun detect_csv() {
        assertEquals(GlossaryImportFormat.CSV, GlossaryTextImport.detect("source,target\nEren,艾伦"))
    }

    @Test
    fun detect_csvWithHeaderSingleRow() {
        assertEquals(GlossaryImportFormat.CSV, GlossaryTextImport.detect("source,target\nEren,艾伦"))
    }

    @Test
    fun detect_singleCsvRowWithoutHeaderIsUnknown() {
        // 没有表头、只有一行时太像自然语言（"a, b"），不自动当成 CSV。
        assertEquals(GlossaryImportFormat.UNKNOWN, GlossaryTextImport.detect("Eren,艾伦"))
    }

    @Test
    fun detect_proseWithCommaIsUnknown() {
        assertEquals(
            GlossaryImportFormat.UNKNOWN,
            GlossaryTextImport.detect("just a plain note, no glossary here")
        )
    }

    @Test
    fun detect_plainProseIsUnknown() {
        assertEquals(GlossaryImportFormat.UNKNOWN, GlossaryTextImport.detect("这里只是说明文字，没有术语。"))
    }

    @Test
    fun detect_invalidJsonIsUnknown() {
        assertEquals(GlossaryImportFormat.UNKNOWN, GlossaryTextImport.detect("""{"glossary":["""))
    }

    @Test
    fun detect_bomPrefixedJson() {
        assertEquals(
            GlossaryImportFormat.JSON,
            GlossaryTextImport.detect("\uFEFF[{\"source\":\"Eren\",\"target\":\"艾伦\"}]")
        )
    }

    @Test
    fun detect_jsonWithoutPairsIsUnknown() {
        assertEquals(GlossaryImportFormat.UNKNOWN, GlossaryTextImport.detect("[]"))
    }

    /** CSV 引号里的竖线不能被抢占成 Markdown。 */
    @Test
    fun detect_csvWithPipesInsideQuotes() {
        val raw = "\"Eren|Yeager\",艾伦\n\"Levi|Ackerman\",利威尔"
        assertEquals(GlossaryImportFormat.CSV, GlossaryTextImport.detect(raw))
        assertEquals(
            listOf("Eren|Yeager" to "艾伦", "Levi|Ackerman" to "利威尔"),
            GlossaryRepository.GlossaryCsv.parse(raw)
        )
    }

    // ---------- JSON 解析 ----------

    @Test
    fun json_sourceTargetArray() {
        val raw = """
            [
              {"source":"Eren","target":"艾伦"},
              {"source":"Levi","target":"利威尔"}
            ]
        """.trimIndent()
        assertEquals(
            listOf("Eren" to "艾伦", "Levi" to "利威尔"),
            GlossaryTextImport.parseJson(raw)
        )
    }

    @Test
    fun json_glossaryEntriesAndTermsAliases() {
        val entries = """{"entries":[{"source":"Eren","target":"艾伦"}]}"""
        val terms = """{"terms":[{"source":"Levi","target":"利威尔"}]}"""
        assertEquals(listOf("Eren" to "艾伦"), GlossaryTextImport.parseJson(entries))
        assertEquals(listOf("Levi" to "利威尔"), GlossaryTextImport.parseJson(terms))
    }

    @Test
    fun json_fieldAliases() {
        val raw = """[{"term":"Eren","translation":"艾伦"},{"原文":"Levi","译文":"利威尔"}]"""
        assertEquals(
            listOf("Eren" to "艾伦", "Levi" to "利威尔"),
            GlossaryTextImport.parseJson(raw)
        )
    }

    @Test
    fun json_arrayOfArrays() {
        val raw = """[["Eren","艾伦"],["Levi","利威尔"]]"""
        assertEquals(
            listOf("Eren" to "艾伦", "Levi" to "利威尔"),
            GlossaryTextImport.parseJson(raw)
        )
    }

    @Test
    fun json_keyValueObject() {
        val parsed = GlossaryTextImport.parseJson("""{"Eren":"艾伦","Levi":"利威尔"}""").orEmpty().toMap()
        assertEquals(mapOf("Eren" to "艾伦", "Levi" to "利威尔"), parsed)
    }

    @Test
    fun json_nonStringValueObjectIsRejected() {
        assertEquals(null, GlossaryTextImport.parseJson("""{"version":1}"""))
    }

    // ---------- Markdown 解析 ----------

    @Test
    fun markdown_basicTableDropsHeaderAndSeparator() {
        val raw = """
            | 原文 | 译文 |
            | --- | --- |
            | Eren | 艾伦 |
            | Levi | 利威尔 |
        """.trimIndent()
        assertEquals(
            listOf("Eren" to "艾伦", "Levi" to "利威尔"),
            GlossaryTextImport.parseMarkdown(raw)
        )
    }

    @Test
    fun markdown_alignmentSeparatorVariants() {
        assertEquals(
            listOf("Eren" to "艾伦"),
            GlossaryTextImport.parseMarkdown("| source | target |\n|:---|--:|\n| Eren | 艾伦 |")
        )
        assertEquals(
            listOf("Eren" to "艾伦"),
            GlossaryTextImport.parseMarkdown("| source | target |\n| :---: | :---: |\n| Eren | 艾伦 |")
        )
    }

    @Test
    fun markdown_headerWithoutSeparator() {
        val raw = "原文 | 译文\nEren | 艾伦\nLevi | 利威尔"
        assertEquals(
            listOf("Eren" to "艾伦", "Levi" to "利威尔"),
            GlossaryTextImport.parseMarkdown(raw)
        )
    }

    @Test
    fun markdown_rowBeforeSeparatorIsTreatedAsHeader() {
        val raw = "| Eren | 艾伦 |\n| --- | --- |\n| Levi | 利威尔 |"
        // 分隔行上一行按 Markdown 语法就是表头，这里的第一行会被当成表头丢弃。
        assertEquals(listOf("Levi" to "利威尔"), GlossaryTextImport.parseMarkdown(raw))
    }

    @Test
    fun markdown_escapedPipe() {
        val raw = "| 原文 | 译文 |\n| --- | --- |\n| Eren \\| Yeager | 艾伦 |"
        assertEquals(
            listOf("Eren | Yeager" to "艾伦"),
            GlossaryTextImport.parseMarkdown(raw)
        )
    }

    @Test
    fun markdown_extraColumnsIgnored() {
        val raw = "| 原文 | 译文 | 备注 |\n| --- | --- | --- |\n| Eren | 艾伦 | 主角 |"
        assertEquals(
            listOf("Eren" to "艾伦"),
            GlossaryTextImport.parseMarkdown(raw)
        )
    }

    @Test
    fun markdown_skipsEmptyCells() {
        val raw = "| 原文 | 译文 |\n| --- | --- |\n| Eren | 艾伦 |\n|  | 空 |\n| Levi |  |"
        assertEquals(
            listOf("Eren" to "艾伦"),
            GlossaryTextImport.parseMarkdown(raw)
        )
    }

    @Test
    fun markdown_multipleTables() {
        val raw = """
            | 原文 | 译文 |
            | --- | --- |
            | Eren | 艾伦 |

            | 原文 | 译文 |
            | --- | --- |
            | Levi | 利威尔 |
        """.trimIndent()
        assertEquals(
            listOf("Eren" to "艾伦", "Levi" to "利威尔"),
            GlossaryTextImport.parseMarkdown(raw)
        )
    }

    // ---------- 预检 ----------

    @Test
    fun previewCount_matchesParsedRows() {
        assertEquals(2, GlossaryTextImport.previewCount("""[{"source":"A","target":"甲"},{"source":"B","target":"乙"}]"""))
        assertEquals(2, GlossaryTextImport.previewCount("source,target\nA,甲\nB,乙"))
        assertEquals(2, GlossaryTextImport.previewCount("| 原文 | 译文 |\n| --- | --- |\n| A | 甲 |\n| B | 乙 |"))
        assertEquals(0, GlossaryTextImport.previewCount("没有术语"))
    }

    @Test
    fun detect_doesNotTreatHeaderlessPipeRowsAsMarkdown() {
        val raw = "Eren | 艾伦\nLevi | 利威尔"
        // 没有分隔行/表头关键字时保持保守，不误判成 Markdown。
        assertEquals(GlossaryImportFormat.UNKNOWN, GlossaryTextImport.detect(raw))
    }
}
