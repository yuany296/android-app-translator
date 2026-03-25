package com.manga.translator.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.widget.TextView

class StrokeTextView(context: Context) : TextView(context) {
    private var strokeColor: Int = Color.WHITE
    private var strokeWidthPx: Float = 0f

    fun setStroke(color: Int, widthPx: Float) {
        strokeColor = color
        strokeWidthPx = widthPx.coerceAtLeast(0f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (strokeWidthPx > 0f) {
            val fillColor = currentTextColor
            val originalStyle = paint.style
            val originalStrokeWidth = paint.strokeWidth
            val originalJoin = paint.strokeJoin

            paint.style = Paint.Style.STROKE
            paint.strokeJoin = Paint.Join.ROUND
            paint.strokeWidth = strokeWidthPx
            setTextColor(strokeColor)
            super.onDraw(canvas)

            paint.style = Paint.Style.FILL
            paint.strokeWidth = originalStrokeWidth
            paint.strokeJoin = originalJoin
            setTextColor(fillColor)
            super.onDraw(canvas)

            paint.style = originalStyle
            return
        }
        super.onDraw(canvas)
    }
}
