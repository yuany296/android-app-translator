package com.manga.translator.app

import com.manga.translator.store.AiProvider
import com.manga.translator.translate.ThinkingSupport
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 厂商「是否支持思考开关」的两处门控必须一致（2026-10-02）。
 *
 * - UI 门控：新建/编辑配置表单用 `AiProvider.supportsThinking` 决定开关能不能点；
 * - 请求门控：`ThinkingSupport.providerSupportsThinking` 决定请求体发不发思考参数。
 *
 * 两边各自硬编码一份厂商名单，任何一边漏改都会造成「开关能点但请求不带参数」
 * 或「参数发了但 UI 不让开」这类静默失效，所以在这里钉死。
 */
class ThinkingProviderConsistencyTest {
    @Test
    fun providerSupportMatchesClientGate() {
        AiProvider.entries.forEach { provider ->
            assertEquals(
                "厂商 ${provider.id} 的思考能力在 AiProvider 与 ThinkingSupport 中不一致",
                provider.supportsThinking,
                ThinkingSupport.providerSupportsThinking(provider.id)
            )
        }
    }
}
