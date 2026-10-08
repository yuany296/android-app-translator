package com.manga.translator.translate

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 思考参数三态单测（2026-10-02）。
 *
 * 背景：用户在「新建配置」里要求加一个「是否开启思考功能」的参数，并明确
 * 「有思考功能会慢很多」。这里锁定三件事：
 * 1. 只有千问 / GLM 走思考参数（与 `AiProvider.supportsThinking` 一致）；
 * 2. 关闭时**显式发** `enable_thinking=false`（否则 Qwen3 这类默认开思考的模型不受控）；
 * 3. 不支持的厂商保持旧行为（完全不发该字段）。
 */
class ThinkingSupportTest {
    private val maxOutputTokens = 2048

    @Test
    fun providerSupportsThinking_onlyQwenAndGlm() {
        listOf("qwen", "glm", " Qwen ", "GLM\n", "Qwen").forEach { id ->
            assertTrue("应支持思考参数：$id", ThinkingSupport.providerSupportsThinking(id))
        }
        listOf("openai", "deepseek", "kimi", "siliconflow", "volces", "custom", "baidu", "")
            .forEach { id ->
                assertFalse("不应支持思考参数：$id", ThinkingSupport.providerSupportsThinking(id))
            }
    }

    @Test
    fun initialMode_unsupportedProviderAlwaysOmits() {
        assertEquals(
            ThinkingMode.OMIT,
            ThinkingSupport.initialMode(providerSupportsThinking = false, enableThinking = true)
        )
        assertEquals(
            ThinkingMode.OMIT,
            ThinkingSupport.initialMode(providerSupportsThinking = false, enableThinking = false)
        )
    }

    @Test
    fun initialMode_supportedProviderDefaultsToExplicitDisabled() {
        assertEquals(
            ThinkingMode.ENABLED,
            ThinkingSupport.initialMode(providerSupportsThinking = true, enableThinking = true)
        )
        assertEquals(
            ThinkingMode.DISABLED_EXPLICIT,
            ThinkingSupport.initialMode(providerSupportsThinking = true, enableThinking = false)
        )
    }

    @Test
    fun nextDegrade_walksEnabledToExplicitDisabledToOmit() {
        assertEquals(
            ThinkingMode.DISABLED_EXPLICIT,
            ThinkingSupport.nextDegrade(ThinkingMode.ENABLED)
        )
        assertEquals(ThinkingMode.OMIT, ThinkingSupport.nextDegrade(ThinkingMode.DISABLED_EXPLICIT))
        assertNull(ThinkingSupport.nextDegrade(ThinkingMode.OMIT))
    }

    @Test
    fun applyTo_enabledSendsThinkingFieldsWithoutMaxTokens() {
        val body = JSONObject()

        ThinkingSupport.applyTo(body, ThinkingMode.ENABLED, budget = 8192, maxOutputTokens = maxOutputTokens)

        assertTrue(body.getBoolean("enable_thinking"))
        assertEquals(8192, body.getInt("thinking_budget"))
        assertFalse(body.has("max_tokens"))
    }

    @Test
    fun applyTo_explicitDisabledSendsFalseAndMaxTokens() {
        val body = JSONObject()

        ThinkingSupport.applyTo(
            body,
            ThinkingMode.DISABLED_EXPLICIT,
            budget = 8192,
            maxOutputTokens = maxOutputTokens
        )

        assertFalse(body.getBoolean("enable_thinking"))
        assertFalse("显式关闭时不该再带思考预算", body.has("thinking_budget"))
        assertEquals(maxOutputTokens, body.getInt("max_tokens"))
    }

    @Test
    fun applyTo_omitSendsNoThinkingField() {
        val body = JSONObject()

        ThinkingSupport.applyTo(body, ThinkingMode.OMIT, budget = 8192, maxOutputTokens = maxOutputTokens)

        assertFalse(body.has("enable_thinking"))
        assertFalse(body.has("thinking_budget"))
        assertEquals(maxOutputTokens, body.getInt("max_tokens"))
    }

    @Test
    fun applyTo_clampsThinkingBudget() {
        val tooSmall = JSONObject()
        ThinkingSupport.applyTo(tooSmall, ThinkingMode.ENABLED, budget = 100, maxOutputTokens = maxOutputTokens)
        assertEquals(1024, tooSmall.getInt("thinking_budget"))

        val tooBig = JSONObject()
        ThinkingSupport.applyTo(tooBig, ThinkingMode.ENABLED, budget = 999_999, maxOutputTokens = maxOutputTokens)
        assertEquals(131072, tooBig.getInt("thinking_budget"))
    }
}
