package com.manga.translator.app

import com.manga.translator.store.AiProvider
import com.manga.translator.translate.OpenCodeGoSupport
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * OpenCode Go 预设的两处常量必须一致（2026-10-30）：
 * - 配置表单的默认 Base URL 来自 `AiProvider.OPENCODE_GO.defaultBaseUrl`；
 * - 模型列表改写 / 请求头识别在 `OpenCodeGoSupport` 里按主机判断。
 *
 * Base URL 漂移会让新配置直接打到错误端点（或模型列表 404），所以钉死。
 */
class OpenCodeGoProviderConsistencyTest {
    @Test
    fun presetMatchesSupportConstants() {
        val provider = AiProvider.fromId(OpenCodeGoSupport.PROVIDER_ID)
        assertEquals(AiProvider.OPENCODE_GO, provider)
        assertEquals("OpenCode Go", provider.label)
        assertEquals(OpenCodeGoSupport.DEFAULT_BASE_URL, provider.defaultBaseUrl)
        assertEquals("deepseek-v4.1-flash", provider.defaultModel)
    }
}
