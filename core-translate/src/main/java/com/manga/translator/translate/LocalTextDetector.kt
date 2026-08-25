package com.manga.translator.translate

import android.graphics.Bitmap

interface LocalTextDetector {
    fun detect(bitmap: Bitmap, sourceLanguageId: String): List<TextBlock>

    /** 当前引擎的模型是否已就绪（无需加载即可直接检测）。默认视为就绪。 */
    fun isReady(sourceLanguageId: String): Boolean = true

    /** 后台预加载模型（不执行检测），用于点击时预热，避免首次检测卡顿。默认无操作。 */
    fun prewarm(sourceLanguageId: String) {}
}
