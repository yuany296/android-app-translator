package com.manga.translator.overlay.erase

import android.graphics.Bitmap
import android.graphics.RectF

data class ErasePatch(
    val bitmap: Bitmap,
    val srcRect: RectF,
    val renderRect: RectF,
    val bubbleId: Int
)

