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
    );

    companion object {
        fun fromId(id: String): AiProvider {
            return entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) } ?: OPENAI
        }
    }
}
