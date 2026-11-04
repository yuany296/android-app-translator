package com.manga.translator.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlossaryFingerprintTest {

    @Test
    fun emptyEntries_haveEmptyFingerprint() {
        assertEquals("", GlossaryFingerprint.active(emptyList()))
        assertEquals("", GlossaryFingerprint.active(listOf(" " to " ")))
        assertEquals("", GlossaryFingerprint.relevant("아무 문장", emptyList()))
    }

    @Test
    fun active_isStableAcrossOrder() {
        val first = listOf("Eren" to "艾伦", "Levi" to "利威尔")
        val second = listOf("Levi" to "利威尔", "Eren" to "艾伦")
        assertEquals(GlossaryFingerprint.active(first), GlossaryFingerprint.active(second))
    }

    @Test
    fun relevant_changesWhenMatchedTargetChanges() {
        val before = GlossaryFingerprint.relevant(
            "레오나르도가 말했다",
            listOf("레오나르도" to "莱昂纳多")
        )
        val after = GlossaryFingerprint.relevant(
            "레오나르도가 말했다",
            listOf("레오나르도" to "里奥纳多")
        )
        assertTrue(before.isNotEmpty())
        assertNotEquals(before, after)
    }

    @Test
    fun relevant_ignoresUnrelatedEntryChanges() {
        val text = "레오나르도가 말했다"
        val before = GlossaryFingerprint.relevant(
            text,
            listOf("레오나르도" to "莱昂纳多", "미수" to "未遂")
        )
        val after = GlossaryFingerprint.relevant(
            text,
            listOf("레오나르도" to "莱昂纳多", "미수" to "未遂罪")
        )
        assertEquals(before, after)
        assertNotEquals(
            GlossaryFingerprint.active(listOf("레오나르도" to "莱昂纳多", "미수" to "未遂")),
            GlossaryFingerprint.active(listOf("레오나르도" to "莱昂纳多", "미수" to "未遂罪"))
        )
    }

    @Test
    fun relevant_emptyWhenTermDoesNotAppear() {
        assertEquals(
            "",
            GlossaryFingerprint.relevant("다른 문장", listOf("레오나르도" to "莱昂纳多"))
        )
    }

    @Test
    fun relevant_matchesKoreanParticleVariant() {
        assertTrue(
            GlossaryFingerprint.relevant(
                "레오나르도는 웃었다",
                listOf("레오나르도" to "莱昂纳多")
            ).isNotEmpty()
        )
    }

    @Test
    fun relevant_removingTermChangesFingerprint() {
        val text = "레오나르도가 말했다"
        val withTerm = GlossaryFingerprint.relevant(text, listOf("레오나르도" to "莱昂纳多"))
        val withoutTerm = GlossaryFingerprint.relevant(text, emptyList())
        assertNotEquals(withTerm, withoutTerm)
    }
}
