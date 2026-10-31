package com.manga.translator.translate

import okhttp3.Request
import org.json.JSONObject
import java.util.UUID

/**
 * OpenCode Go 订阅的接入口径（2026-10-30）。
 *
 * Go 不是普通的 OpenAI 兼容端点，除 Bearer Key 外还有两条硬要求
 * （见 https://opencode.ai/console/go「Where can I use it?」）：
 * 1. 客户端用自己的 User-Agent，不能是 `okhttp/x.y.z` 这类通用库名；
 * 2. 每个会话带稳定的 `x-opencode-session`，否则路由/提示词缓存无法优化。
 *
 * 另外它的模型列表不在 chat base URL 下面：
 * - chat:   https://opencode.ai/inference/go/openai/v1/chat/completions
 * - models: https://opencode.ai/inference/go/v1/models
 *
 * 旧的 `https://opencode.ai/zen/go/v1` 也仍然可用；两者都按 opencode.ai 主机识别，
 * 统一改写模型列表地址，避免「获取模型列表」404。
 *
 * 纯 Kotlin + OkHttp，可直接 JVM 单测。
 */
object OpenCodeGoSupport {
    const val PROVIDER_ID = "opencode-go"
    const val DEFAULT_BASE_URL = "https://opencode.ai/inference/go/openai/v1"
    const val MODELS_ENDPOINT = "https://opencode.ai/inference/go/v1/models"

    /** 自有 UA：Go 明确不建议使用通用 HTTP 库的默认 UA。 */
    const val USER_AGENT = "manga-translator/1.0"

    /**
     * 进程级稳定会话 ID（`ses_` 前缀 + 无连字符 UUID）。
     *
     * App 同一时间只跑一条翻译会话，因此一个进程一个 ID 足够稳定；
     * 不按请求生成 —— 那样每次请求都是新会话，反而失去路由/缓存优化。
     */
    val SESSION_ID: String = "ses_" + UUID.randomUUID().toString().replace("-", "")

    /** 只认 opencode.ai 本身（含旧 zen 路径），避免把会话头发给其它厂商。 */
    fun isOpenCodeHost(url: String): Boolean {
        return OPENCODE_HOST_REGEX.containsMatchIn(url.trim())
    }

    /**
     * 是否是 Go 的路径（`/inference/go/...` 或旧 `/zen/go/...`）。
     *
     * 模型列表只有 Go 的地址需要改写；opencode.ai 的其它产品（如 Zen 按量付费）
     * 仍按 `<baseUrl>/models` 走，避免把 Go 的模型列表错配给别的套餐。
     */
    fun isGoEndpoint(url: String): Boolean {
        return GO_PATH_REGEX.containsMatchIn(url.trim())
    }

    /**
     * 给发往 opencode.ai 的请求补 Go 要求的头；其它厂商原样返回。
     *
     * `header` 会覆盖 OkHttp 的默认 UA；同一 Builder 重复调用是幂等的。
     */
    fun applyHeaders(builder: Request.Builder, url: String): Request.Builder {
        if (!isOpenCodeHost(url)) return builder
        return builder
            .header("User-Agent", USER_AGENT)
            .header("x-opencode-session", SESSION_ID)
    }

    /**
     * 模型列表地址：Go 的 `/models` 比 chat base URL 少一段 `/openai`；
     * 其它厂商保持 `<baseUrl>/models` 的旧行为。
     */
    fun modelsEndpoint(normalizedBaseUrl: String): String {
        return if (isGoEndpoint(normalizedBaseUrl)) {
            MODELS_ENDPOINT
        } else {
            normalizedBaseUrl.trimEnd('/') + "/models"
        }
    }

    /**
     * Go 的模型元数据里 `api` 会标出该模型原生支持的协议
     * （`chat-completions` / `responses` / `messages` / `generate-content`）。
     *
     * 本 App 只会发 Chat Completions，所以有 `api` 字段时只保留带
     * `chat-completions` 的模型；没有 `api` 字段的厂商（OpenAI/DeepSeek 等）
     * 维持原来的「全部返回」行为。
     */
    fun isChatCompletionsModel(item: JSONObject): Boolean {
        val api = item.optJSONObject("api") ?: return true
        return api.has("chat-completions")
    }

    private val OPENCODE_HOST_REGEX = Regex("""^https?://(?:[^/]+\.)?opencode\.ai(?:/|$)""", RegexOption.IGNORE_CASE)
    private val GO_PATH_REGEX = Regex("""^https?://(?:[^/]+\.)?opencode\.ai/(?:inference|zen)/go(?:/|$)""", RegexOption.IGNORE_CASE)
}
