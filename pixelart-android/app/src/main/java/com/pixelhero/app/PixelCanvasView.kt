package com.pixelhero.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.*

/**
 * Custom view that displays the current frame with onion-skin + background reference,
 * grid overlay, and handles touch (draw + pinch zoom + pan).
 */
class PixelCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var project: Project? = null
        set(value) { field = value; rebuildBitmaps(); resetView(); invalidate() }

    var tool: Tool = Tool.PENCIL
    var color: Int = 0xFFFF5577.toInt()
    var showGrid: Boolean = true
    var showOnion: Boolean = true
    var onionOpacity: Float = 0.3f
    var bgOpacity: Float = 0.5f
    var bgBitmap: Bitmap? = null
        set(value) { field = value; invalidate() }

    // View transform
    private var scale = 1f
    private var translateX = 0f
    private var translateY = 0f
    private var baseScale = 1f // base zoom to fit

    // Bitmap caches (sized to project)
    private var frameBmp: Bitmap? = null
    private var onionBmp: Bitmap? = null

    // Touch state
    private val activePointers = mutableMapOf<Int, PointF>()
    private var isDrawing = false
    private var lastPx = -1
    private var lastPy = -1
    private var startPx = -1
    private var startPy = -1
    private var panStartX = 0f
    private var panStartY = 0f
    private var translateStartX = 0f
    private var translateStartY = 0f
    private var pinchStartDist = 0f
    private var pinchStartScale = 1f
    private var pinchCenterX = 0f
    private var pinchCenterY = 0f
    private var pinching = false

    // Shape preview (for line/rect tools): in-progress overlay
    private var previewActive = false
    private var previewPath = mutableListOf<IntArray>() // [x,y] cells

    // Listeners
    var onProjectChanged: (() -> Unit)? = null
    var onColorPicked: ((Int) -> Unit)? = null
    var onStrokeStart: (() -> Unit)? = null
    var onStrokeEnd: (() -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        isFilterBitmap = false
        isAntiAlias = false
    }
    private val gridPaint = Paint().apply {
        color = 0x33FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 0f
    }
    private val gridMajorPaint = Paint().apply {
        color = 0x66A5B4FF
        style = Paint.Style.STROKE
        strokeWidth = 0f
    }
    private val checkerPaint1 = Paint().apply { color = 0xFF22222E.toInt() }
    private val checkerPaint2 = Paint().apply { color = 0xFF15151F.toInt() }
    private val borderPaint = Paint().apply {
        color = 0xFF6677AA.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val previewPaint = Paint().apply { style = Paint.Style.FILL }

    init {
        isFocusable = true
        isClickable = true
    }

    private val scaleGD = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val factor = d.scaleFactor
            // Zoom centered on focus
            val fx = d.focusX
            val fy = d.focusY
            val newScale = (scale * factor).coerceIn(0.25f, 32f)
            val ratio = newScale / scale
            translateX = fx - ratio * (fx - translateX)
            translateY = fy - ratio * (fy - translateY)
            scale = newScale
            invalidate()
            return true
        }
    })

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetView()
    }

    private fun rebuildBitmaps() {
        val p = project ?: return
        frameBmp = Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888)
        onionBmp = Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888)
        syncFrameBitmap()
        syncOnionBitmap()
    }

    fun syncFrameBitmap() {
        val p = project ?: return
        val bmp = frameBmp ?: return
        bmp.setPixels(p.currentFrame.pixels, 0, p.width, 0, 0, p.width, p.height)
        invalidate()
    }

    fun syncOnionBitmap() {
        val p = project ?: return
        val bmp = onionBmp ?: return
        if (p.currentIndex > 0) {
            bmp.setPixels(p.frames[p.currentIndex - 1].pixels, 0, p.width, 0, 0, p.width, p.height)
        } else {
            bmp.eraseColor(0)
        }
        invalidate()
    }

    fun resetView() {
        val p = project ?: return
        val pad = 32f
        val vw = (width - pad).coerceAtLeast(1f)
        val vh = (height - pad).coerceAtLeast(1f)
        baseScale = min(vw / p.width, vh / p.height).coerceAtLeast(1f).let { floor(it) }
        if (baseScale < 1f) baseScale = 1f
        scale = baseScale
        translateX = (width - p.width * scale) / 2f
        translateY = (height - p.height * scale) / 2f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = project ?: return
        val w = p.width
        val h = p.height

        canvas.save()
        canvas.translate(translateX, translateY)
        canvas.scale(scale, scale)

        // Transparent checker background
        val cell = 1f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val pnt = if (((x + y) and 1) == 0) checkerPaint1 else checkerPaint2
                canvas.drawRect(x.toFloat(), y.toFloat(), x + cell, y + cell, pnt)
            }
        }

        // Background reference image (fit + opacity)
        bgBitmap?.let { bg ->
            val ratio = min(w.toFloat() / bg.width, h.toFloat() / bg.height)
            val dw = bg.width * ratio
            val dh = bg.height * ratio
            val dx = (w - dw) / 2f
            val dy = (h - dh) / 2f
            paint.alpha = (bgOpacity * 255).toInt().coerceIn(0, 255)
            canvas.drawBitmap(bg, null, RectF(dx, dy, dx + dw, dy + dh), paint)
            paint.alpha = 255
        }

        // Onion skin (previous frame)
        if (showOnion && p.currentIndex > 0) {
            paint.alpha = (onionOpacity * 255).toInt().coerceIn(0, 255)
            onionBmp?.let { canvas.drawBitmap(it, 0f, 0f, paint) }
            paint.alpha = 255
        }

        // Current frame
        frameBmp?.let { canvas.drawBitmap(it, 0f, 0f, paint) }

        // Preview overlay (in-progress shape)
        if (previewActive) {
            previewPaint.color = color
            for (cellXY in previewPath) {
                canvas.drawRect(cellXY[0].toFloat(), cellXY[1].toFloat(),
                    cellXY[0] + 1f, cellXY[1] + 1f, previewPaint)
            }
        }

        // Grid
        if (showGrid && scale >= 6f) {
            for (x in 0..w) {
                val px = x.toFloat()
                val pnt = if (x % 8 == 0) gridMajorPaint else gridPaint
                canvas.drawLine(px, 0f, px, h.toFloat(), pnt)
            }
            for (y in 0..h) {
                val py = y.toFloat()
                val pnt = if (y % 8 == 0) gridMajorPaint else gridPaint
                canvas.drawLine(0f, py, w.toFloat(), py, pnt)
            }
        }

        // Border
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), borderPaint)

        canvas.restore()
    }

    // -- Touch handling --
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleGD.onTouchEvent(event)
        val action = event.actionMasked
        val idx = event.actionIndex
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val id = event.getPointerId(idx)
                activePointers[id] = PointF(event.getX(idx), event.getY(idx))
                if (activePointers.size == 1) {
                    val coords = clientToPixel(event.x, event.y)
                    if (tool == Tool.MOVE) {
                        panStartX = event.x; panStartY = event.y
                        translateStartX = translateX; translateStartY = translateY
                    } else {
                        beginStroke(coords[0], coords[1])
                    }
                } else if (activePointers.size == 2) {
                    // Cancel drawing, start pan/pinch
                    cancelStroke()
                    val pts = activePointers.values.toList()
                    pinchStartDist = dist(pts[0], pts[1])
                    pinchStartScale = scale
                    pinchCenterX = (pts[0].x + pts[1].x) / 2f
                    pinchCenterY = (pts[0].y + pts[1].y) / 2f
                    panStartX = pinchCenterX; panStartY = pinchCenterY
                    translateStartX = translateX; translateStartY = translateY
                    pinching = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    activePointers[id] = PointF(event.getX(i), event.getY(i))
                }
                if (pinching && activePointers.size == 2) {
                    val pts = activePointers.values.toList()
                    val center = PointF((pts[0].x + pts[1].x) / 2f, (pts[0].y + pts[1].y) / 2f)
                    // Pan from center movement
                    translateX = translateStartX + (center.x - panStartX)
                    translateY = translateStartY + (center.y - panStartY)
                    invalidate()
                } else if (activePointers.size == 1) {
                    if (tool == Tool.MOVE) {
                        translateX = translateStartX + (event.x - panStartX)
                        translateY = translateStartY + (event.y - panStartY)
                        invalidate()
                    } else if (isDrawing) {
                        val coords = clientToPixel(event.x, event.y)
                        continueStroke(coords[0], coords[1])
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val id = event.getPointerId(idx)
                activePointers.remove(id)
                if (activePointers.size < 2) pinching = false
                if (activePointers.isEmpty()) {
                    if (isDrawing) endStroke()
                }
            }
        }
        return true
    }

    private fun dist(a: PointF, b: PointF): Float = hypot(a.x - b.x, a.y - b.y)

    private fun clientToPixel(x: Float, y: Float): IntArray {
        val px = floor((x - translateX) / scale).toInt()
        val py = floor((y - translateY) / scale).toInt()
        return intArrayOf(px, py)
    }

    // -- Drawing --
    private fun beginStroke(px: Int, py: Int) {
        val p = project ?: return
        isDrawing = true
        startPx = px; startPy = py
        lastPx = px; lastPy = py
        onStrokeStart?.invoke()
        when (tool) {
            Tool.PENCIL -> { p.currentFrame.set(px, py, color); syncFrameBitmap() }
            Tool.ERASER -> { p.currentFrame.set(px, py, 0); syncFrameBitmap() }
            Tool.FILL -> { floodFill(px, py, color); syncFrameBitmap(); onProjectChanged?.invoke() }
            Tool.PICKER -> { val c = p.currentFrame.get(px, py); if (Color.alpha(c) > 0) onColorPicked?.invoke(c) }
            Tool.LINE, Tool.RECT, Tool.RECT_FILL -> { previewActive = true; updatePreview(); invalidate() }
            else -> {}
        }
    }

    private fun continueStroke(px: Int, py: Int) {
        if (px == lastPx && py == lastPy) return
        val p = project ?: return
        when (tool) {
            Tool.PENCIL -> {
                drawBresenham(lastPx, lastPy, px, py, color)
                syncFrameBitmap()
            }
            Tool.ERASER -> {
                drawBresenham(lastPx, lastPy, px, py, 0)
                syncFrameBitmap()
            }
            Tool.PICKER -> {
                val c = p.currentFrame.get(px, py)
                if (Color.alpha(c) > 0) onColorPicked?.invoke(c)
            }
            Tool.LINE, Tool.RECT, Tool.RECT_FILL -> { updatePreview(); invalidate() }
            else -> {}
        }
        lastPx = px; lastPy = py
    }

    private fun endStroke() {
        val p = project ?: return
        when (tool) {
            Tool.LINE -> {
                drawBresenham(startPx, startPy, lastPx, lastPy, color)
                syncFrameBitmap()
            }
            Tool.RECT -> { drawRect(startPx, startPy, lastPx, lastPy, color, false); syncFrameBitmap() }
            Tool.RECT_FILL -> { drawRect(startPx, startPy, lastPx, lastPy, color, true); syncFrameBitmap() }
            else -> {}
        }
        previewActive = false
        previewPath.clear()
        isDrawing = false
        onStrokeEnd?.invoke()
        onProjectChanged?.invoke()
        invalidate()
    }

    private fun cancelStroke() {
        previewActive = false
        previewPath.clear()
        isDrawing = false
        invalidate()
    }

    private fun updatePreview() {
        previewPath.clear()
        when (tool) {
            Tool.LINE -> bresenhamCells(startPx, startPy, lastPx, lastPy) { x, y -> previewPath.add(intArrayOf(x, y)) }
            Tool.RECT, Tool.RECT_FILL -> {
                val xMin = min(startPx, lastPx); val xMax = max(startPx, lastPx)
                val yMin = min(startPy, lastPy); val yMax = max(startPy, lastPy)
                if (tool == Tool.RECT_FILL) {
                    for (y in yMin..yMax) for (x in xMin..xMax) previewPath.add(intArrayOf(x, y))
                } else {
                    for (x in xMin..xMax) {
                        previewPath.add(intArrayOf(x, yMin)); previewPath.add(intArrayOf(x, yMax))
                    }
                    for (y in yMin..yMax) {
                        previewPath.add(intArrayOf(xMin, y)); previewPath.add(intArrayOf(xMax, y))
                    }
                }
            }
            else -> {}
        }
    }

    private fun drawBresenham(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
        bresenhamCells(x0, y0, x1, y1) { x, y -> project?.currentFrame?.set(x, y, c) }
    }

    private inline fun bresenhamCells(x0: Int, y0: Int, x1: Int, y1: Int, action: (Int, Int) -> Unit) {
        var x = x0; var y = y0
        val dx = abs(x1 - x0); val dy = abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx - dy
        while (true) {
            action(x, y)
            if (x == x1 && y == y1) break
            val e2 = err * 2
            if (e2 > -dy) { err -= dy; x += sx }
            if (e2 < dx)  { err += dx; y += sy }
        }
    }

    private fun drawRect(x0: Int, y0: Int, x1: Int, y1: Int, c: Int, fill: Boolean) {
        val xMin = min(x0, x1); val xMax = max(x0, x1)
        val yMin = min(y0, y1); val yMax = max(y0, y1)
        val f = project?.currentFrame ?: return
        if (fill) {
            for (y in yMin..yMax) for (x in xMin..xMax) f.set(x, y, c)
        } else {
            for (x in xMin..xMax) { f.set(x, yMin, c); f.set(x, yMax, c) }
            for (y in yMin..yMax) { f.set(xMin, y, c); f.set(xMax, y, c) }
        }
    }

    private fun floodFill(x: Int, y: Int, fillColor: Int) {
        val p = project ?: return
        val f = p.currentFrame
        if (x < 0 || y < 0 || x >= f.width || y >= f.height) return
        val target = f.get(x, y)
        if (target == fillColor) return
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(x, y))
        while (stack.isNotEmpty()) {
            val cell = stack.removeLast()
            val cx = cell[0]; val cy = cell[1]
            if (cx < 0 || cy < 0 || cx >= f.width || cy >= f.height) continue
            if (f.get(cx, cy) != target) continue
            f.set(cx, cy, fillColor)
            stack.addLast(intArrayOf(cx + 1, cy))
            stack.addLast(intArrayOf(cx - 1, cy))
            stack.addLast(intArrayOf(cx, cy + 1))
            stack.addLast(intArrayOf(cx, cy - 1))
        }
    }

    fun clearFrame() {
        project?.currentFrame?.clear()
        syncFrameBitmap()
        onProjectChanged?.invoke()
    }
}
