package com.manga.translator.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.manga.translator.translate.MlKitLocalTranslator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MlKitTranslateVerificationTest {

    private val translator = MlKitLocalTranslator()

    @Test
    fun mlKitTranslatesEnToZhCn() {
        assertTrue("ml kit should support en->zh-cn", translator.isSupported("en", "zh-cn"))
        val downloaded = runBlocking { translator.isDownloaded("en", "zh-cn") }
        println("MlKitModelDownloaded: $downloaded")
        if (!downloaded) {
            val error = runBlocking { translator.downloadModel("en", "zh-cn") }
            println("MlKitModelDownloadError: $error")
        }
        assertTrue("model should download", runBlocking { translator.isDownloaded("en", "zh-cn") })

        val result = runBlocking {
            translator.translate(
                listOf("Hello world, this is a test.", "The heroes were born in that age."),
                "en",
                "zh-cn"
            )
        }
        println("MlKitTranslateResult: ${result.joinToString(" | ")}")
        assertNotEquals("first text should be translated", "Hello world, this is a test.", result[0])
        assertTrue("translated text should contain chinese", result[0].any { it.code in 0x4E00..0x9FFF })
        assertNotEquals("second text should be translated", "The heroes were born in that age.", result[1])
    }
}