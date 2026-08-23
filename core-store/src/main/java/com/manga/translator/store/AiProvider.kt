package com.manga.translator.store

enum class AiProvider(
    val id: String,
    val label: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val supportsThinking: Boolean
) {
    OPENAI(
        id = "openai",
        label = "OpenAI",
        defaultBaseUrl = "https://api.openai.com/v1",
        defaultModel = "gpt-4o-mini",
        supportsThinking = false
    ),
    QWEN(
        id = "qwen",
        label = "千问",
        defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        defaultModel = "qwen-vl-max-latest",
        supportsThinking = true
    ),
    GLM(
        id = "glm",
        label = "GLM",
        defaultBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
        defaultModel = "glm-4v-plus",
        supportsThinking = true
    ),
    DEEPSEEK(
        id = "deepseek",
        label = "DeepSeek",
        defaultBaseUrl = "https://api.deepseek.com/v1",
        defaultModel = "deepseek-chat",
        supportsThinking = false
    ),
    KIMI(
        id = "kimi",
        label = "Kimi",
        defaultBaseUrl = "https://api.moonshot.cn/v1",
        defaultModel = "moonshot-v1-8k",
        supportsThinking = false
    ),
    SILICONFLOW(
        id = "siliconflow",
        label = "硅基流动",
        defaultBaseUrl = "https://api.siliconflow.cn/v1",
        defaultModel = "Qwen/Qwen2.5-VL-7B-Instruct",
        supportsThinking = false
    ),
    VOLCES(
        id = "volces",
        label = "火山方舟",
        defaultBaseUrl = "https://ark.cn-beijing.volces.com/api/v3",
        defaultModel = "",
        supportsThinking = false
    ),
    CUSTOM(
        id = "custom",
        label = "自定义（OpenAI 兼容）",
        defaultBaseUrl = "",
        defaultModel = "",
        supportsThinking = false
    ),
    BAIDU(
        id = "baidu",
        label = "百度OCR",
        defaultBaseUrl = "https://aip.baidubce.com",
        defaultModel = "accurate",
        supportsThinking = false
    );

    companion object {
        fun fromId(id: String): AiProvider {
            return entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) } ?: OPENAI
        }
    }
}
