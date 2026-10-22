package com.talalqasem.netcard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/** إطار بزوايا بيضاء لتوضيح مكان البطاقة */
class CardFrameView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 5f * d
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val m = 22f * d
        val l = 40f * d
        val left = m
        val top = m
        val right = width - m
        val bottom = height - m
        canvas.drawLine(left, top, left + l, top, paint)
        canvas.drawLine(left, top, left, top + l, paint)
        canvas.drawLine(right, top, right - l, top, paint)
        canvas.drawLine(right, top, right, top + l, paint)
        canvas.drawLine(left, bottom, left + l, bottom, paint)
        canvas.drawLine(left, bottom, left, bottom - l, paint)
        canvas.drawLine(right, bottom, right - l, bottom, paint)
        canvas.drawLine(right, bottom, right, bottom - l, paint)
    }
}
