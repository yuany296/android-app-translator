package com.manga.translator.app

import android.graphics.Bitmap
import kotlin.math.max

object FrameHash {
    fun centerDHash(bitmap: Bitmap): Long {
        if (bitmap.width < 2 || bitmap.height < 2) return 0L

        val left = (bitmap.width * 0.25f).toInt().coerceAtLeast(0)
        val top = (bitmap.height * 0.25f).toInt().coerceAtLeast(0)
        val width = max(2, (bitmap.width * 0.5f).toInt())
        val height = max(2, (bitmap.height * 0.5f).toInt())
        val safeWidth = width.coerceAtMost(bitmap.width - left)
        val safeHeight = height.coerceAtMost(bitmap.height - top)

        val center = Bitmap.createBitmap(bitmap, left, top, safeWidth, safeHeight)
        val scaled = Bitmap.createScaledBitmap(center, 9, 8, true)
        if (scaled != center) center.recycle()

        val pixels = IntArray(9 * 8)
        scaled.getPixels(pixels, 0, 9, 0, 0, 9, 8)
        scaled.recycle()

        var bits = 0L
        var bitIndex = 0
        for (y in 0 until 8) {
            val row = y * 9
            for (x in 0 until 8) {
                val leftLum = luminance(pixels[row + x])
                val rightLum = luminance(pixels[row + x + 1])
                if (leftLum > rightLum) {
                    bits = bits or (1L shl bitIndex)
                }
                bitIndex++
            }
        }
        return bits
    }

    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    private fun luminance(color: Int): Int {
        val r = color shr 16 and 0xFF
        val g = color shr 8 and 0xFF
        val b = color and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
