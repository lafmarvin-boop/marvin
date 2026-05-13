package com.pixelhero.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Horizontal timeline showing all frames as thumbnails. Tap or drag to navigate.
 * Highlights the current frame with a colored border.
 */
class TimelineView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var project: Project? = null
        set(value) { field = value; invalidate() }

    var onFrameSelected: ((Int) -> Unit)? = null

    private val bgPaint = Paint().apply { color = 0xFF15151F.toInt() }
    private val framePaint = Paint().apply { isFilterBitmap = false }
    private val currentBorderPaint = Paint().apply {
        color = 0xFF5566FF.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val normalBorderPaint = Paint().apply {
        color = 0xFF3A3A4A.toInt(); style = Paint.Style.STROKE; strokeWidth = 1f
    }
    private val indexPaint = Paint().apply {
        color = 0xFFB4B4C8.toInt(); textSize = 18f; textAlign = Paint.Align.CENTER
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val p = project ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val cellW = (width.toFloat() / p.frames.size.coerceAtLeast(1))
                val idx = (event.x / cellW).toInt().coerceIn(0, p.frames.size - 1)
                if (idx != p.currentIndex) {
                    onFrameSelected?.invoke(idx)
                }
                return true
            }
        }
        return false
    }

    override fun onDraw(canvas: Canvas) {
        val p = project ?: return
        if (p.frames.isEmpty()) return
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val cellW = width.toFloat() / p.frames.size
        val cellH = height - 4f
        val margin = 2f
        p.frames.forEachIndexed { i, frame ->
            val rx = i * cellW + margin
            val ry = margin
            val rw = cellW - margin * 2
            val rh = cellH - margin
            // Draw thumbnail
            val src = if (frame.layers.size > 1) frame.composited() else frame.pixels
            val bmp = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
            bmp.setPixels(src, 0, frame.width, 0, 0, frame.width, frame.height)
            canvas.drawBitmap(bmp, null, RectF(rx, ry, rx + rw, ry + rh), framePaint)
            bmp.recycle()
            // Border
            if (i == p.currentIndex) {
                canvas.drawRect(rx, ry, rx + rw, ry + rh, currentBorderPaint)
            } else {
                canvas.drawRect(rx, ry, rx + rw, ry + rh, normalBorderPaint)
            }
            // Frame number (only if cell is wide enough)
            if (cellW >= 30f) {
                canvas.drawText("${i + 1}", rx + rw / 2, height - 4f, indexPaint)
            }
        }
    }
}
