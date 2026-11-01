package com.manga.translator.app

import com.manga.translator.translate.BubbleRegion
import com.manga.translator.translate.BubbleRegionType
import com.manga.translator.translate.PaddleOcrBlockMerger
import com.manga.translator.translate.TextBlock
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OCR → 气泡归组的夹具回归（2026-11-01，P1）。
 *
 * 为什么要有这层：单个 planner/merger 的单测能守住各自的阈值，但真机问题是
 * 「OCR 合并 + 区域归属 + 切段」串起来才出现的。这里用一组可读的 JSON 夹具
 * 把整条纯逻辑链跑一遍，之后改任一环都能在同一个场景上看到结果。
 *
 * 夹具目录：`app/src/test/resources/fixtures/ocr_pipeline/`
 * 新增场景：加 JSON + 在 `index.txt` 补一行；不需要改测试代码。
 */
class OcrPipelineFixtureTest {

    private val planner = BubbleFirstRegionPlanner()

    @Test
    fun allFixtures_matchExpectedPipeline() {
        val names = readIndex()
        assertTrue("夹具索引不应为空", names.size >= 5)
        names.forEach { name ->
            try {
                runFixture(name)
            } catch (err: Throwable) {
                throw AssertionError("夹具失败：$name（${err.message}）", err)
            }
        }
    }

    private fun runFixture(fileName: String) {
        val json = loadJson(fileName)
        val width = json.getInt("imageWidth")
        val height = json.getInt("imageHeight")
        val seamTop = json.optInt("seamTopPx", 0)
        val seamBottom = json.optInt("seamBottomPx", 0)
        val blocks = readBlocks(json)
        val merged = if (json.optBoolean("mergeWithPaddle", false)) {
            PaddleOcrBlockMerger.mergeBlocks(blocks, width, height, seamTop, seamBottom)
        } else {
            blocks
        }
        val expected = json.getJSONObject("expected")

        if (expected.has("mergedTextCount")) {
            assertEquals(
                "$fileName / mergedTextCount",
                expected.getInt("mergedTextCount"),
                merged.size
            )
        }
        val mergedText = merged.joinToString("\n") { it.text }
        expected.optJSONArray("mergedTextContains")?.let { array ->
            for (i in 0 until array.length()) {
                val part = array.getString(i)
                assertTrue("$fileName / 合并文本缺少：$part；实际=$mergedText", mergedText.contains(part))
            }
        }
        expected.optJSONArray("mergedTextNotContains")?.let { array ->
            for (i in 0 until array.length()) {
                val part = array.getString(i)
                assertTrue("$fileName / 合并文本不应包含：$part；实际=$mergedText", !mergedText.contains(part))
            }
        }

        val regions = readRegions(json)
        if (regions.isEmpty()) return

        val routing = planner.route(regions, merged, width, height)
        val bubbles = routing.bubbleAssignments.flatMap { assignment ->
            planner.buildBubblesFromAssignment(assignment, width, height)
        }
        if (expected.has("bubbleCount")) {
            assertEquals("$fileName / bubbleCount", expected.getInt("bubbleCount"), bubbles.size)
        }
        expected.optJSONArray("bubbleTexts")?.let { array ->
            val want = (0 until array.length()).map { array.getString(it) }.toSet()
            val actual = bubbles.map { it.originalText }.toSet()
            assertEquals("$fileName / bubbleTexts", want, actual)
        }
        if (expected.has("outsideTexts")) {
            val array = expected.getJSONArray("outsideTexts")
            val want = (0 until array.length()).map { array.getString(it) }.toSet()
            val actual = routing.outsideBlocks.map { it.text }.toSet()
            assertEquals("$fileName / outsideTexts", want, actual)
        }
    }

    private fun readBlocks(json: JSONObject): List<TextBlock> {
        val array = json.getJSONArray("blocks")
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            TextBlock(
                id = item.getInt("id"),
                text = item.getString("text"),
                left = item.getDouble("left").toFloat(),
                top = item.getDouble("top").toFloat(),
                right = item.getDouble("right").toFloat(),
                bottom = item.getDouble("bottom").toFloat(),
                confidence = item.optDouble("confidence", 0.0).toFloat(),
                lineCount = item.optInt("lineCount", 1)
            )
        }
    }

    private fun readRegions(json: JSONObject): List<BubbleRegion> {
        val array = json.optJSONArray("regions") ?: return emptyList()
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            BubbleRegion(
                id = item.getInt("id"),
                left = item.getDouble("left").toFloat(),
                top = item.getDouble("top").toFloat(),
                right = item.getDouble("right").toFloat(),
                bottom = item.getDouble("bottom").toFloat(),
                regionType = BubbleRegionType.valueOf(item.getString("type")),
                confidence = item.optDouble("confidence", 0.0).toFloat(),
                source = item.optString("source", "fixture"),
                parentId = if (item.has("parentId")) item.getInt("parentId") else null
            )
        }
    }

    private fun loadJson(fileName: String): JSONObject {
        val text = readResource("fixtures/ocr_pipeline/$fileName")
        return JSONObject(text)
    }

    private fun readIndex(): List<String> {
        return readResource("fixtures/ocr_pipeline/index.txt")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
    }

    private fun readResource(path: String): String {
        val stream = javaClass.classLoader?.getResourceAsStream(path)
            ?: error("找不到夹具：$path")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}
