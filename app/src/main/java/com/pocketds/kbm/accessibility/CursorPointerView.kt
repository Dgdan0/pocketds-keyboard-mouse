package com.pocketds.kbm.accessibility

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * A classic arrow pointer, drawn with its hotspot (the tip) at the view's top-left
 * corner — that's what lets the cursor reach every corner of the screen exactly,
 * unlike a centered circular blob whose body always clips before the hotspot does.
 */
class CursorPointerView(context: Context, sizePx: Int, accentColor: Int) : View(context) {

    private val arrowPath = Path()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = sizePx * 0.06f
        strokeJoin = Paint.Join.ROUND
    }

    init {
        val s = sizePx.toFloat()
        arrowPath.apply {
            moveTo(0f, 0f)
            lineTo(0f, s * 0.75f)
            lineTo(s * 0.2f, s * 0.6f)
            lineTo(s * 0.35f, s * 1.0f)
            lineTo(s * 0.5f, s * 0.92f)
            lineTo(s * 0.38f, s * 0.55f)
            lineTo(s * 0.65f, s * 0.55f)
            close()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(arrowPath, fillPaint)
        canvas.drawPath(arrowPath, outlinePaint)
    }
}
