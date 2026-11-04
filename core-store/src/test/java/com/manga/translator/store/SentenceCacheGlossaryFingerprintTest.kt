package com.manga.translator.store

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceCacheGlossaryFingerprintTest {

    @Test
    fun nullCurrent_isAlwaysFresh() {
        // 老入口/测试不传术语指纹时保持旧行为。
        assertTrue(SentenceTranslationRepository.isGlossaryFingerprintFresh("g1-abc", null))
        assertTrue(SentenceTranslationRepository.isGlossaryFingerprintFresh("", null))
    }

    @Test
    fun manualFingerprint_isAlwaysFresh() {
        assertTrue(
            SentenceTranslationRepository.isGlossaryFingerprintFresh(
                SentenceTranslationRepository.MANUAL_FINGERPRINT,
                "g1-something-new"
            )
        )
        assertTrue(
            SentenceTranslationRepository.isGlossaryFingerprintFresh(
                SentenceTranslationRepository.MANUAL_FINGERPRINT,
                ""
            )
        )
    }

    @Test
    fun matchingFingerprint_isFresh() {
        assertTrue(SentenceTranslationRepository.isGlossaryFingerprintFresh("g1-abc", "g1-abc"))
        assertTrue(SentenceTranslationRepository.isGlossaryFingerprintFresh("", ""))
    }

    @Test
    fun changedFingerprint_isStale() {
        assertFalse(SentenceTranslationRepository.isGlossaryFingerprintFresh("g1-abc", "g1-def"))
        assertFalse(SentenceTranslationRepository.isGlossaryFingerprintFresh("", "g1-def"))
        assertFalse(SentenceTranslationRepository.isGlossaryFingerprintFresh("g1-abc", ""))
    }
}
