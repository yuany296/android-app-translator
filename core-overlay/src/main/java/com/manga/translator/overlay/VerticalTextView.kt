package com.manga.translator.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.max

class VerticalTextView(context: Context) : TextView(context) {
    private var strokeColor: Int = Color.WHITE
    private var strokeWidthPx: Float = 0f

    fun setStroke(color: Int, widthPx: Float) {
        strokeColor = color
        strokeWidthPx = widthPx.coerceAtLeast(0f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val raw = text?.toString().orEmpty()
        if (raw.isBlank()) return
        val normalized = normalizeVerticalPunctuation(raw.replace("\n", ""))
        if (normalized.isBlank()) return

        val drawLeft = paddingLeft.toFloat()
        val drawTop = paddingTop.toFloat()
        val drawRight = (width - paddingRight).toFloat()
        val drawBottom = (height - paddingBottom).toFloat()
        val drawW = (drawRight - drawLeft).coerceAtLeast(1f)
        val drawH = (drawBottom - drawTop).coerceAtLeast(1f)

        val paint = paint
        paint.isAntiAlias = true
        val charStep = (paint.textSize * 1.08f).coerceAtLeast(1f)
        val rows = max(1, (drawH / charStep).toInt())
        val cols = ceil(normalized.length / rows.toFloat()).toInt().coerceAtLeast(1)
        val requiredW = cols * charStep
        val startX = drawLeft + ((drawW - requiredW).coerceAtLeast(0f) / 2f) + requiredW - charStep / 2f
        val fm = paint.fontMetrics
        val baselineOffset = -(fm.ascent + fm.descent) / 2f

        var index = 0
        for (col in 0 until cols) {
            val x = startX - col * charStep
            for (row in 0 until rows) {
                if (index >= normalized.length) break
                val y = drawTop + row * charStep + charStep / 2f + baselineOffset
                val ch = normalized[index].toString()
                if (strokeWidthPx > 0f) {
                    val fillColor = currentTextColor
                    val oldStyle = paint.style
                    val oldStrokeWidth = paint.strokeWidth
                    val oldJoin = paint.strokeJoin
                    paint.style = Paint.Style.STROKE
                    paint.strokeJoin = Paint.Join.ROUND
                    paint.strokeWidth = strokeWidthPx
                    paint.color = strokeColor
                    canvas.drawText(ch, x, y, paint)

                    paint.style = Paint.Style.FILL
                    paint.strokeWidth = oldStrokeWidth
                    paint.strokeJoin = oldJoin
                    paint.color = fillColor
                    canvas.drawText(ch, x, y, paint)
                    paint.style = oldStyle
                } else {
                    canvas.drawText(ch, x, y, paint)
                }
                index++
            }
        }
    }

    private fun normalizeVerticalPunctuation(text: String): String {
        return text
            .replace('「', '﹁')
            .replace('」', '﹂')
            .replace('（', '﹙')
            .replace('）', '﹚')
    }
}

