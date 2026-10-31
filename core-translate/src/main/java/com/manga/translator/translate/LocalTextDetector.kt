package com.manga.translator.translate

import android.graphics.Bitmap

interface LocalTextDetector {
    fun detect(bitmap: Bitmap, sourceLanguageId: String): List<TextBlock>

    /**
     * 带接缝边界的检测（2026-11-01）：`seamTopPx` / `seamBottomPx` 是**拼接图内部**的
     * 上/下切缝（像素，0 = 没有）。默认忽略；Paddle 会用它阻止段落跨缝合并。
     */
    fun detect(
        bitmap: Bitmap,
        sourceLanguageId: String,
        seamTopPx: Int,
        seamBottomPx: Int
    ): List<TextBlock> = detect(bitmap, sourceLanguageId)

    /** 当前引擎的模型是否已就绪（无需加载即可直接检测）。默认视为就绪。 */
    fun isReady(sourceLanguageId: String): Boolean = true

    /** 后台预加载模型（不执行检测），用于点击时预热，避免首次检测卡顿。默认无操作。 */
    fun prewarm(sourceLanguageId: String) {}
}
