package com.manga.translator.overlay.erase

import android.graphics.Bitmap
import android.graphics.RectF

data class ErasePatch(
    val bitmap: Bitmap,
    val srcRect: RectF,
    val renderRect: RectF,
    val bubbleId: Int,
    /** true = 贴片来自整页 inpaint 清理图（「清理图」选项生效），false = 逐气泡本地填充。 */
    val fromCleanedImage: Boolean = false
)

