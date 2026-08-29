package com.manga.translator.translate

/**
 * 本地（离线）翻译引擎抽象。与 [LocalTextDetector] 对应，供翻译阶段离线使用。
 * 具体实现（如 ML Kit Translation）负责按（源语言,目标语言）语言对管理模型下载与缓存。
 */
interface LocalTranslator {

    /**
     * 该引擎是否支持给定语言对（源语言 -> 目标语言），语言编码与应用内 [com.manga.translator.store.AppLanguage] 的 id 一致。
     * 不支持的组合应返回 false，调用方回退到云端。
     */
    fun isSupported(sourceLanguageId: String, targetLanguageId: String): Boolean

    /**
     * 翻译一批文本。语言对必须已由 [isSupported] 确认支持。
     * 若模型尚未就绪会尝试下载；下载失败或翻译失败时抛出异常，由调用方决定是否回退云端。
     */
    suspend fun translate(
        texts: List<String>,
        sourceLanguageId: String,
        targetLanguageId: String
    ): List<String>

    /** 该语言对的本地模型是否已下载就绪（无需再下载即可直接翻译）。未就绪时 UI 应提示"正在下载模型"。 */
    fun isModelReady(sourceLanguageId: String, targetLanguageId: String): Boolean = false

    /** 后台预加载当前语言对模型，用于预热，避免首次翻译卡顿。默认无操作。 */
    fun prewarm(sourceLanguageId: String, targetLanguageId: String) {}

    /**
     * 主动下载指定语言对的模型（供 UI 手动触发）。
     * 返回 null 表示下载成功；返回非空字符串为失败原因（可直接展示给用户）。
     * 默认实现视为无需下载（成功）。
     */
    suspend fun downloadModel(sourceLanguageId: String, targetLanguageId: String): String? = null

    /**
     * 探测指定语言对的模型是否已在设备本地（磁盘已缓存）。
     * 与 [isModelReady] 的区别：本方法跨进程重启后仍能正确反映磁盘状态，用于决定是否隐藏下载入口。
     * 默认实现视为未下载（保守显示下载入口）。
     */
    suspend fun isDownloaded(sourceLanguageId: String, targetLanguageId: String): Boolean = false
}
