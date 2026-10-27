package com.manga.translator.store

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Test

class GlossaryTextDecodingTest {

    @Test
    fun decode_plainUtf8() {
        val text = "原文,译文\nEren,艾伦"
        assertEquals(text, GlossaryTextDecoding.decode(text.toByteArray(StandardCharsets.UTF_8)))
    }

    @Test
    fun decode_stripsUtf8Bom() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val body = "source,target\nEren,艾伦".toByteArray(StandardCharsets.UTF_8)
        assertEquals("source,target\nEren,艾伦", GlossaryTextDecoding.decode(bom + body))
    }

    @Test
    fun decode_gbkFallback() {
        val text = "原文,译文\nEren,艾伦"
        assertEquals(text, GlossaryTextDecoding.decode(text.toByteArray(charset("GBK"))))
    }

    @Test
    fun decode_empty() {
        assertEquals("", GlossaryTextDecoding.decode(ByteArray(0)))
    }
}
