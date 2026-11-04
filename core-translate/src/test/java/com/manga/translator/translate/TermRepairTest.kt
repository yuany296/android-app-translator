package com.manga.translator.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TermRepairTest {

    @Test
    fun hangulLeftover_isReplacedAndParticleConsumed() {
        val repaired = TermRepair.repair(
            original = "레오나르도가 말했다",
            translated = "레오나르도가 说道。",
            glossary = listOf("레오나르도" to "莱昂纳多")
        )
        assertEquals("莱昂纳多说道。", repaired)
    }

    @Test
    fun hangulLeftover_consumesQuotativeTail() {
        val repaired = TermRepair.repair(
            original = "케이지라고요!!",
            translated = "케이지라고요!!",
            glossary = listOf("케이지" to "笼子")
        )
        assertEquals("笼子!!", repaired)
    }

    @Test
    fun latinLeftover_keepsSpaceBeforeLatinWord() {
        val repaired = TermRepair.repair(
            original = "Eren is here",
            translated = "Eren is here",
            glossary = listOf("Eren" to "艾伦")
        )
        assertEquals("艾伦 is here", repaired)
    }

    @Test
    fun latinLeftover_eatsSpaceBeforeCjk() {
        val repaired = TermRepair.repair(
            original = "Eren 加入了调查兵团",
            translated = "Eren 加入了调查兵团",
            glossary = listOf("Eren" to "艾伦")
        )
        assertEquals("艾伦加入了调查兵团", repaired)
    }

    @Test
    fun latinTerm_doesNotTouchLongerWord() {
        val repaired = TermRepair.repair(
            original = "king rules",
            translated = "kingdom rules",
            glossary = listOf("king" to "国王")
        )
        assertEquals("kingdom rules", repaired)
    }

    @Test
    fun targetContainingSource_isSkipped() {
        val repaired = TermRepair.repair(
            original = "Leo is here",
            translated = "Leo is here",
            glossary = listOf("Leo" to "Leonardo")
        )
        assertEquals("Leo is here", repaired)
    }

    @Test
    fun sourceNotInOriginal_isNotTouched() {
        val repaired = TermRepair.repair(
            original = "안녕하세요",
            translated = "Eren is here",
            glossary = listOf("Eren" to "艾伦")
        )
        assertEquals("Eren is here", repaired)
    }

    @Test
    fun longestSourceWins() {
        val repaired = TermRepair.repair(
            original = "레온하르트가 왔다",
            translated = "레온하르트가 来了",
            glossary = listOf(
                "레온" to "莱昂",
                "레온하르트" to "莱昂哈特"
            )
        )
        assertEquals("莱昂哈特来了", repaired)
    }

    @Test
    fun repairWithChanges_reportsWhatWasChanged() {
        val result = TermRepair.repairWithChanges(
            original = "Eren is here",
            translated = "Eren is here",
            glossary = listOf("Eren" to "艾伦")
        )
        assertEquals("艾伦 is here", result.text)
        assertTrue(result.changes.contains("Eren" to "艾伦"))
    }

    @Test
    fun noChange_returnsOriginalTextUntouched() {
        val result = TermRepair.repairWithChanges(
            original = "Eren is here",
            translated = "艾伦在这里",
            glossary = listOf("Eren" to "艾伦")
        )
        assertEquals("艾伦在这里", result.text)
        assertTrue(result.changes.isEmpty())
    }
}
