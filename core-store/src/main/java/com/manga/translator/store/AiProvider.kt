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
        defaultModel = "",
        supportsThinking = false
    ),
    QWEN(
        id = "qwen",
        label = "千问",
        defaultBaseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        defaultModel = "",
        supportsThinking = true
    ),
    GLM(
        id = "glm",
        label = "GLM",
        defaultBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
        defaultModel = "",
        supportsThinking = true
    ),
    DEEPSEEK(
        id = "deepseek",
        label = "DeepSeek",
        defaultBaseUrl = "https://api.deepseek.com/v1",
        defaultModel = "",
        // 2026-10-12 放开：官方端点默认开思考，字段是 `thinking:{"type":"enabled"/"disabled"}`
        // （不是千问的 enable_thinking，字段方言见 ThinkingSupport）。
        supportsThinking = true
    ),
    OPENCODE_GO(
        id = "opencode-go",
        label = "OpenCode Go",
        // Go 订阅的 OpenAI Chat Completions 端点（Console 文档 /console/harnesses#base-urls）：
        //   https://opencode.ai/inference/go + /openai/v1/chat/completions
        // 模型列表在 .../inference/go/v1/models（比 base URL 少一段 /openai），
        // 由 core-translate 的 OpenCodeGoSupport 统一改写；不要手填 /chat/completions。
        defaultBaseUrl = "https://opencode.ai/inference/go/openai/v1",
        // 翻译 + 视觉 OCR 都够用：1M 上下文、支持图片输入、Go 月额度最高的一档。
        // 其它 Chat Completions 模型可在「获取模型列表」里选（Responses/Messages 专属模型会被过滤）。
        defaultModel = "deepseek-v4.1-flash",
        // Go 的 DeepSeek 系默认会思考；关掉时发 `thinking:{"type":"disabled"}`（与官方 DeepSeek 同方言）。
        supportsThinking = true
    ),
    KIMI(
        id = "kimi",
        label = "Kimi",
        defaultBaseUrl = "https://api.moonshot.cn/v1",
        defaultModel = "",
        supportsThinking = false
    ),
    SILICONFLOW(
        id = "siliconflow",
        label = "硅基流动",
        defaultBaseUrl = "https://api.siliconflow.cn/v1",
        defaultModel = "",
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
    );

    companion object {
        fun fromId(id: String): AiProvider {
            return entries.firstOrNull { it.id.equals(id.trim(), ignoreCase = true) } ?: OPENAI
        }
    }
}
