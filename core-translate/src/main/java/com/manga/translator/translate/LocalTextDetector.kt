package com.manga.translator.translate

import android.graphics.Bitmap

interface LocalTextDetector {
    fun detect(bitmap: Bitmap, sourceLanguageId: String): List<TextBlock>
}
