package com.manga.translator.translate

import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenCode Go 接入约束（2026-10-30）：
 * - 只把 Go 专用头发给 opencode.ai（含旧 zen 路径），不能泄漏给其它厂商；
 * - 模型列表地址要从 chat base URL 改写（Go 的 `/models` 少一段 `/openai`）；
 * - 只保留 Chat Completions 模型，避免选到 Responses / Messages 专属模型。
 */
class OpenCodeGoSupportTest {
    @Test
    fun isOpenCodeHost_matchesOnlyOpencodeAi() {
        listOf(
            "https://opencode.ai/inference/go/openai/v1",
            "https://opencode.ai/inference/go/openai/v1/chat/completions",
            "https://opencode.ai/zen/go/v1/chat/completions",
            "https://opencode.ai/inference/go/v1/models",
            "HTTPS://OPENCODE.AI/zen/go/v1",
            " https://opencode.ai "
        ).forEach { url ->
            assertTrue("应识别为 OpenCode 主机：$url", OpenCodeGoSupport.isOpenCodeHost(url))
        }
        listOf(
            "https://api.deepseek.com/v1",
            "https://opencode.ai.evil.example/v1",
            "https://evil.example/opencode.ai/v1",
            "https://notopencode.ai/v1",
            ""
        ).forEach { url ->
            assertFalse("不应识别为 OpenCode 主机：$url", OpenCodeGoSupport.isOpenCodeHost(url))
        }
    }

    @Test
    fun modelsEndpoint_rewritesGoBaseAndKeepsOtherProviders() {
        assertEquals(
            OpenCodeGoSupport.MODELS_ENDPOINT,
            OpenCodeGoSupport.modelsEndpoint("https://opencode.ai/inference/go/openai/v1")
        )
        assertEquals(
            OpenCodeGoSupport.MODELS_ENDPOINT,
            OpenCodeGoSupport.modelsEndpoint("https://opencode.ai/zen/go/v1")
        )
        assertEquals(
            "https://api.deepseek.com/v1/models",
            OpenCodeGoSupport.modelsEndpoint("https://api.deepseek.com/v1")
        )
        assertEquals(
            "https://api.deepseek.com/v1/models",
            OpenCodeGoSupport.modelsEndpoint("https://api.deepseek.com/v1/")
        )
        // 同主机的非 Go 套餐（Zen 按量付费）不能错配 Go 的模型列表。
        assertEquals(
            "https://opencode.ai/inference/openai/v1/models",
            OpenCodeGoSupport.modelsEndpoint("https://opencode.ai/inference/openai/v1")
        )
    }

    @Test
    fun isGoEndpoint_matchesOnlyGoPaths() {
        listOf(
            "https://opencode.ai/inference/go/openai/v1",
            "https://opencode.ai/inference/go/v1/models",
            "https://opencode.ai/zen/go/v1/chat/completions",
            "https://opencode.ai/zen/go"
        ).forEach { url ->
            assertTrue("应识别为 Go 路径：$url", OpenCodeGoSupport.isGoEndpoint(url))
        }
        listOf(
            "https://opencode.ai/inference/openai/v1",
            "https://opencode.ai/zen/v1",
            "https://api.deepseek.com/v1",
            ""
        ).forEach { url ->
            assertFalse("不应识别为 Go 路径：$url", OpenCodeGoSupport.isGoEndpoint(url))
        }
    }

    @Test
    fun applyHeaders_addsGoHeadersOnlyForOpencode() {
        val go = OpenCodeGoSupport.applyHeaders(
            Request.Builder().url("https://opencode.ai/inference/go/openai/v1/chat/completions").get(),
            "https://opencode.ai/inference/go/openai/v1/chat/completions"
        ).build()

        assertEquals(OpenCodeGoSupport.USER_AGENT, go.header("User-Agent"))
        assertEquals(OpenCodeGoSupport.SESSION_ID, go.header("x-opencode-session"))

        val other = OpenCodeGoSupport.applyHeaders(
            Request.Builder().url("https://api.deepseek.com/v1/chat/completions").get(),
            "https://api.deepseek.com/v1/chat/completions"
        ).build()

        assertNull(other.header("User-Agent"))
        assertNull(other.header("x-opencode-session"))
    }

    @Test
    fun sessionId_isStableAndPrefixed() {
        assertTrue(OpenCodeGoSupport.SESSION_ID.startsWith("ses_"))
        assertTrue(OpenCodeGoSupport.SESSION_ID.length > "ses_".length)
        assertEquals(OpenCodeGoSupport.SESSION_ID, OpenCodeGoSupport.SESSION_ID)
    }

    @Test
    fun isChatCompletionsModel_filtersResponsesAndMessagesModels() {
        assertTrue(
            OpenCodeGoSupport.isChatCompletionsModel(
                JSONObject("""{"id":"deepseek-v4.1-flash","api":{"chat-completions":{}}}""")
            )
        )
        assertFalse(
            OpenCodeGoSupport.isChatCompletionsModel(
                JSONObject("""{"id":"gpt-6-luna","api":{"responses":{}}}""")
            )
        )
        assertFalse(
            OpenCodeGoSupport.isChatCompletionsModel(
                JSONObject("""{"id":"qwen3.8-max","api":{"messages":{}}}""")
            )
        )
        assertFalse(
            OpenCodeGoSupport.isChatCompletionsModel(
                JSONObject("""{"id":"gemini-4-argon","api":{"generate-content":{}}}""")
            )
        )
        // 普通 OpenAI 兼容厂商没有 api 字段：保持「全部返回」旧行为。
        assertTrue(OpenCodeGoSupport.isChatCompletionsModel(JSONObject("""{"id":"gpt-4o-mini"}""")))
    }
}
