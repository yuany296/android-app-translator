package com.manga.translator.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.manga.translator.store.AppLanguage
import com.manga.translator.translate.SourceScriptDetector

/**
 * 「语言可能选错了」底栏提示（2026-10-14 定稿：自动识别**只提示、不动作**）。
 *
 * 原方案是「识别不符 → 换模型重跑 OCR + 翻译源语言跟随」，用户裁定砍掉这两个自动动作，
 * 只保留提示：识别出的脚本族与配置源语言**不同族**时记录，阅读底栏显示
 * 「语言可能选错了 · 页面是 X」。翻译始终完全按用户配置的语言走。
 *
 * 数据流：`EngineSelectingTextDetector`（漫画 OCR）/ `EmbeddedBrowserScreen` 的网页文字
 * 链路分别写入 → 阅读底栏组合时读取（`mutableStateOf` 驱动重组）→ 换页时清除
 * （提示只属于当前页面，带去下一页就是误报）。
 *
 * 2026-10-15 修了两处「看不到提示」的原因：
 * 1. 底栏原先拿 `statusText.isBlank()` 当空闲判据，而翻译结束后底栏会一直停在
 *    「截图翻译完成（N 处）· 点一下恢复原文」上，提示永远被挡 —— 改成按「没有任务在跑」判；
 * 2. 网页文字翻译链路（不经过 OCR）以前完全不报，现在复用同一条判据补上。
 */
object LanguageMismatchHint {

    /** 当前页面识别出的、与配置不同的语言 id（AppLanguage 口径）；null = 不提示。 */
    var detectedLanguageId by mutableStateOf<String?>(null)
        private set

    /**
     * 一次性提示回调（UI 层注册，当前实现是 Toast）。
     *
     * 为什么除了底栏还要这个：底栏那行字在「连续翻译 / 监听新图」时被进度文案占着，
     * 用户一直开着监听就永远看不到（2026-10-15 用户报「不弹提示」）。
     * 弹一次就走，不跟进度抢位置。
     */
    var onMismatch: ((languageId: String) -> Unit)? = null

    /** 已经弹过提示的语言 id：同一条页面连续 OCR 不重复弹；换页（report(null)）时重置。 */
    private var notifiedLanguageId: String? = null

    /** 有不同族证据就记录（同时弹一次即时提示）；传 null = 本遍证据不支持「选错了」，清除。 */
    fun report(languageId: String?) {
        detectedLanguageId = languageId
        if (languageId == null) {
            notifiedLanguageId = null
            return
        }
        if (languageId == notifiedLanguageId) return
        notifiedLanguageId = languageId
        onMismatch?.invoke(languageId)
    }

    /**
     * 「检测到的语言」与「配置的源语言」是否属于不同识别族（不同族 = 很可能选错了）。
     *
     * 同族不算：简繁互混、以及 Paddle 里 en 与 zh 共用同一个 rec 模型，都不值得打扰用户。
     * 两个链路共用这一个判据，别再各写一份。
     */
    fun isMismatch(detectedLanguageId: String?, configuredLanguageId: String): Boolean {
        if (detectedLanguageId.isNullOrBlank()) return false
        return SourceScriptDetector.recognitionFamily(detectedLanguageId, paddle = false) !=
            SourceScriptDetector.recognitionFamily(configuredLanguageId, paddle = false)
    }

    /** 底栏展示文案；不提示时返回 null。 */
    fun hintText(): String? {
        val id = detectedLanguageId ?: return null
        val label = AppLanguage.entries.firstOrNull { it.id == id }?.label ?: id
        return "语言可能选错了 · 页面是$label"
    }
}
