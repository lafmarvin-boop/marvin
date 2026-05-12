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
 * grid overlay, selection overlay, and handles touch (draw + pinch zoom + pan).
 *
 * Supports:
 * - Drawing tools (pencil/eraser/fill/line/rect/select/move/picker)
 * - Symmetry mirror (H/V/both)
 * - Multi-frame onion skin (configurable range; previous frames in blue, next in red)
 * - Pixel-perfect mode (removes L-shaped corner pixels in lines for clean outlines)
 * - Rectangle selection with floating clipboard for move/copy/paste
 */
class PixelCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var project: Project? = null
        set(value) { field = value; rebuildBitmaps(); resetView(); invalidate() }

    var tool: Tool = Tool.PENCIL
        set(value) {
            // When changing tool away from SELECT, commit any floating selection
            if (field == Tool.SELECT && value != Tool.SELECT) commitFloatingSelection()
            field = value; invalidate()
        }
    var color: Int = 0xFFFF5577.toInt()
    var showGrid: Boolean = true
    var bgOpacity: Float = 0.5f
    var bgBitmap: Bitmap? = null
        set(value) { field = value; invalidate() }

    val selection = Selection()

    // Tap-to-place callback: when non-null, the next canvas tap calls this
    // with pixel coordinates instead of starting a stroke.
    var nextTapHandler: ((x: Int, y: Int) -> Unit)? = null

    // Brush hover preview: pixel position of last move, drawn as outlined cursor
    private var hoverPx = -1
    private var hoverPy = -1

    /** Last reported pressure from the pointer (S-Pen / stylus). 0..1, default 1. */
    private var currentPressure: Float = 1f

    // Sketch layer: a separate per-frame pixel buffer rendered above the frame
    // with reduced opacity, drawn ONLY when sketchMode is true.
    var sketchMode: Boolean = false
        set(value) { field = value; invalidate() }
    var sketchOpacity: Float = 0.5f
    private val sketchLayers = HashMap<String, IntArray>()
    private var sketchBmp: Bitmap? = null

    private fun ensureSketchBuffer(): IntArray {
        val p = project ?: return IntArray(0)
        val key = p.currentFrame.let { "${p.id}_${it.hashCode()}" }
        return sketchLayers.getOrPut(key) { IntArray(p.width * p.height) }
    }

    fun clearSketch() {
        ensureSketchBuffer().fill(0)
        invalidate()
    }

    fun bakeSketchIntoFrame() {
        val p = project ?: return
        val buf = ensureSketchBuffer()
        val f = p.currentFrame
        for (i in buf.indices) {
            if (buf[i] != 0 && (buf[i] ushr 24) and 0xFF >= 128) f.pixels[i] = buf[i]
        }
        buf.fill(0)
        syncFrameBitmap()
    }

    // View transform
    private var scale = 1f
    private var translateX = 0f
    private var translateY = 0f
    private var baseScale = 1f

    // Bitmap caches
    private var frameBmp: Bitmap? = null
    private var onionBmps = mutableListOf<Triple<Bitmap, Int, Int>>() // bitmap, offset (negative=before), tintColor

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
    private var pinching = false

    // Pixel-perfect: track recent pixel positions to delete corners
    private val recentPixels = ArrayDeque<IntArray>()

    // Preview overlay (for line/rect tools)
    private var previewActive = false
    private var previewPath = mutableListOf<IntArray>()

    // Selection drag state
    private var selectionDragMode: SelectionDragMode = SelectionDragMode.NONE
    private enum class SelectionDragMode { NONE, CREATE, MOVE_FLOATING }
    private var floatingDragOffsetX = 0
    private var floatingDragOffsetY = 0

    // Listeners
    var onProjectChanged: (() -> Unit)? = null
    var onColorPicked: ((Int) -> Unit)? = null
    var onStrokeStart: (() -> Unit)? = null
    var onStrokeEnd: (() -> Unit)? = null

    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val gridPaint = Paint().apply { color = 0x33FFFFFF; style = Paint.Style.STROKE; strokeWidth = 0f }
    private val gridMajorPaint = Paint().apply { color = 0x66A5B4FF; style = Paint.Style.STROKE; strokeWidth = 0f }
    private val checkerPaint1 = Paint().apply { color = 0xFF22222E.toInt() }
    private val checkerPaint2 = Paint().apply { color = 0xFF15151F.toInt() }
    private val borderPaint = Paint().apply { color = 0xFF6677AA.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val previewPaint = Paint().apply { style = Paint.Style.FILL }
    private val selDashPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 0f
        pathEffect = DashPathEffect(floatArrayOf(2f, 2f), 0f)
    }
    private val selSymPaint = Paint().apply {
        color = 0x6655AAFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 0f
    }

    init { isFocusable = true; isClickable = true }

    private val scaleGD = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val factor = d.scaleFactor
            val fx = d.focusX; val fy = d.focusY
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
        rebuildOnionBitmaps()
        syncFrameBitmap()
    }

    fun rebuildOnionBitmaps() {
        val p = project ?: return
        // Recycle existing bitmaps to free memory
        onionBmps.forEach {
            try { if (!it.first.isRecycled) it.first.recycle() } catch (_: Exception) {}
        }
        onionBmps.clear()
        if (p.onionRange <= 0) return
        // Previous frames (blue tint)
        for (off in 1..p.onionRange) {
            val idx = p.currentIndex - off
            if (idx < 0) break
            val bmp = Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888)
            bmp.setPixels(frameComposite(p.frames[idx]), 0, p.width, 0, 0, p.width, p.height)
            val tint = 0x4400AAFF.toInt()
            onionBmps.add(Triple(bmp, -off, tint))
        }
        // Next frames (red tint)
        for (off in 1..p.onionRange) {
            val idx = p.currentIndex + off
            if (idx >= p.frames.size) break
            val bmp = Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888)
            bmp.setPixels(frameComposite(p.frames[idx]), 0, p.width, 0, 0, p.width, p.height)
            val tint = 0x44FF4477.toInt()
            onionBmps.add(Triple(bmp, off, tint))
        }
    }

    fun syncFrameBitmap() {
        val p = project ?: return
        val bmp = frameBmp ?: return
        // Use composited view if multi-layer; otherwise direct pixels for speed
        val data = if (p.currentFrame.layers.size > 1) p.currentFrame.composited() else p.currentFrame.pixels
        bmp.setPixels(data, 0, p.width, 0, 0, p.width, p.height)
        invalidate()
    }

    fun syncOnionBitmap() {
        rebuildOnionBitmaps()
        invalidate()
    }

    private fun frameComposite(f: Frame): IntArray =
        if (f.layers.size > 1) f.composited() else f.pixels

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

    fun setZoom(target: Float) {
        val cx = width / 2f
        val cy = height / 2f
        val newScale = target.coerceIn(0.25f, 32f)
        val ratio = newScale / scale
        translateX = cx - ratio * (cx - translateX)
        translateY = cy - ratio * (cy - translateY)
        scale = newScale
        invalidate()
    }

    fun zoomBy(factor: Float) = setZoom(scale * factor)
    fun zoomReset() = resetView()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = project ?: return
        val w = p.width; val h = p.height

        canvas.save()
        canvas.translate(translateX, translateY)
        canvas.scale(scale, scale)

        // Checker background - draw as a single bitmap with a pattern shader instead of
        // per-pixel rects (which is O(w*h) and slow for 1024x1024).
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), checkerPaint1)
        // Overlay the offset pattern with 2x2 tiles (still fewer ops than per-pixel)
        val tileSize = 4f
        var ty = 0f
        var rowOff = 0
        while (ty < h) {
            var tx = (rowOff and 1) * tileSize
            while (tx < w) {
                canvas.drawRect(tx, ty, (tx + tileSize).coerceAtMost(w.toFloat()), (ty + tileSize).coerceAtMost(h.toFloat()), checkerPaint2)
                tx += tileSize * 2
            }
            ty += tileSize
            rowOff++
        }

        // BG reference (3 modes: fit / cover / stretch)
        bgBitmap?.let { bg ->
            paint.alpha = (bgOpacity * 255).toInt().coerceIn(0, 255)
            // Clip to canvas so COVER mode doesn't overflow into the surrounding chrome
            canvas.save()
            canvas.clipRect(0, 0, w, h)
            when (p.bgFit) {
                BgFitMode.STRETCH -> {
                    canvas.drawBitmap(bg, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), paint)
                }
                BgFitMode.COVER -> {
                    val ratio = max(w.toFloat() / bg.width, h.toFloat() / bg.height)
                    val dw = bg.width * ratio
                    val dh = bg.height * ratio
                    val dx = (w - dw) / 2f
                    val dy = (h - dh) / 2f
                    canvas.drawBitmap(bg, null, RectF(dx, dy, dx + dw, dy + dh), paint)
                }
                BgFitMode.FIT -> {
                    val ratio = min(w.toFloat() / bg.width, h.toFloat() / bg.height)
                    val dw = bg.width * ratio
                    val dh = bg.height * ratio
                    val dx = (w - dw) / 2f
                    val dy = (h - dh) / 2f
                    canvas.drawBitmap(bg, null, RectF(dx, dy, dx + dw, dy + dh), paint)
                }
            }
            canvas.restore()
            paint.alpha = 255
        }

        // Onion skin layers (further frames more transparent)
        for ((bmp, off, _) in onionBmps) {
            val absOff = abs(off)
            val alpha = (90 / absOff).coerceAtLeast(30)
            paint.alpha = alpha
            val tint = if (off < 0) p.onionColorPrev else p.onionColorNext
            paint.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_ATOP)
            canvas.drawBitmap(bmp, 0f, 0f, paint)
            paint.colorFilter = null
            paint.alpha = 255
        }

        // Current frame
        frameBmp?.let { canvas.drawBitmap(it, 0f, 0f, paint) }

        // Sketch layer (rendered above frame at reduced opacity)
        if (sketchLayers.isNotEmpty()) {
            val buf = sketchLayers["${p.id}_${p.currentFrame.hashCode()}"]
            if (buf != null) {
                val bmp = sketchBmp ?: Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888).also { sketchBmp = it }
                if (bmp.width != p.width || bmp.height != p.height) {
                    sketchBmp = Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888)
                }
                sketchBmp!!.setPixels(buf, 0, p.width, 0, 0, p.width, p.height)
                paint.alpha = (sketchOpacity * 255).toInt().coerceIn(0, 255)
                canvas.drawBitmap(sketchBmp!!, 0f, 0f, paint)
                paint.alpha = 255
            }
        }

        // Floating selection overlay
        selection.floating?.let {
            val bmp = Bitmap.createBitmap(selection.floatW, selection.floatH, Bitmap.Config.ARGB_8888)
            bmp.setPixels(it, 0, selection.floatW, 0, 0, selection.floatW, selection.floatH)
            canvas.drawBitmap(bmp, selection.floatX.toFloat(), selection.floatY.toFloat(), paint)
            bmp.recycle()
        }

        // Preview shape
        if (previewActive) {
            previewPaint.color = color
            for (cell in previewPath) {
                canvas.drawRect(cell[0].toFloat(), cell[1].toFloat(),
                    cell[0] + 1f, cell[1] + 1f, previewPaint)
            }
        }

        // Symmetry axis indicator
        if (p.symmetry != SymmetryAxis.NONE) {
            if (p.symmetry == SymmetryAxis.HORIZONTAL || p.symmetry == SymmetryAxis.BOTH) {
                val cx = w / 2f
                canvas.drawLine(cx, 0f, cx, h.toFloat(), selSymPaint)
            }
            if (p.symmetry == SymmetryAxis.VERTICAL || p.symmetry == SymmetryAxis.BOTH) {
                val cy = h / 2f
                canvas.drawLine(0f, cy, w.toFloat(), cy, selSymPaint)
            }
        }

        // Grid
        if (showGrid && scale >= 6f) {
            for (x in 0..w) {
                canvas.drawLine(x.toFloat(), 0f, x.toFloat(), h.toFloat(),
                    if (x % 8 == 0) gridMajorPaint else gridPaint)
            }
            for (y in 0..h) {
                canvas.drawLine(0f, y.toFloat(), w.toFloat(), y.toFloat(),
                    if (y % 8 == 0) gridMajorPaint else gridPaint)
            }
        }

        // Selection rectangle (dashed)
        if (selection.active) {
            val r = RectF(selection.xMin.toFloat(), selection.yMin.toFloat(),
                selection.xMax + 1f, selection.yMax + 1f)
            canvas.drawRect(r, selDashPaint)
        }

        // Brush hover outline (around last touched pixel, sized to brush)
        if (isDrawing && hoverPx >= 0 && hoverPy >= 0 && (tool == Tool.PENCIL || tool == Tool.ERASER)) {
            val size = p.brushSize.coerceAtLeast(1)
            val half = size / 2
            val rx = (hoverPx - half).toFloat()
            val ry = (hoverPy - half).toFloat()
            val borderPnt = Paint().apply {
                color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 0.15f
            }
            canvas.drawRect(rx, ry, rx + size, ry + size, borderPnt)
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
                currentPressure = event.getPressure(idx).coerceIn(0.1f, 1f).takeIf { it > 0f } ?: 1f
                if (activePointers.size == 1) {
                    val coords = clientToPixel(event.x, event.y)
                    // Intercept for one-shot tap handler if registered
                    val handler = nextTapHandler
                    if (handler != null) {
                        nextTapHandler = null
                        handler(coords[0], coords[1])
                        return true
                    }
                    if (tool == Tool.MOVE) {
                        panStartX = event.x; panStartY = event.y
                        translateStartX = translateX; translateStartY = translateY
                    } else {
                        beginStroke(coords[0], coords[1])
                    }
                } else if (activePointers.size == 2) {
                    cancelStroke()
                    val pts = activePointers.values.toList()
                    panStartX = (pts[0].x + pts[1].x) / 2f
                    panStartY = (pts[0].y + pts[1].y) / 2f
                    translateStartX = translateX; translateStartY = translateY
                    pinching = true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    activePointers[id] = PointF(event.getX(i), event.getY(i))
                }
                // Use the historical max pressure across this batch
                val pressure = (0 until event.pointerCount).maxOf { event.getPressure(it) }
                if (pressure > 0f) currentPressure = pressure.coerceIn(0.1f, 1f)
                if (pinching && activePointers.size == 2) {
                    val pts = activePointers.values.toList()
                    val cx = (pts[0].x + pts[1].x) / 2f
                    val cy = (pts[0].y + pts[1].y) / 2f
                    translateX = translateStartX + (cx - panStartX)
                    translateY = translateStartY + (cy - panStartY)
                    invalidate()
                } else if (activePointers.size == 1) {
                    if (tool == Tool.MOVE) {
                        translateX = translateStartX + (event.x - panStartX)
                        translateY = translateStartY + (event.y - panStartY)
                        invalidate()
                    } else if (isDrawing) {
                        val coords = clientToPixel(event.x, event.y)
                        continueStroke(coords[0], coords[1])
                        hoverPx = coords[0]; hoverPy = coords[1]
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                activePointers.remove(event.getPointerId(idx))
                if (activePointers.size < 2) pinching = false
                if (activePointers.isEmpty() && isDrawing) endStroke()
            }
        }
        return true
    }

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
        recentPixels.clear()
        onStrokeStart?.invoke()
        when (tool) {
            Tool.PENCIL -> { paintPixelSymmetric(px, py, color); syncFrameBitmap() }
            Tool.ERASER -> { paintPixelSymmetric(px, py, 0); syncFrameBitmap() }
            Tool.FILL -> { floodFill(px, py, color); syncFrameBitmap(); onProjectChanged?.invoke() }
            Tool.PICKER -> { val c = p.currentFrame.get(px, py); if (Color.alpha(c) > 0) onColorPicked?.invoke(c) }
            Tool.LINE, Tool.RECT, Tool.RECT_FILL -> { previewActive = true; updatePreview(); invalidate() }
            Tool.SELECT -> {
                val floating = selection.floating
                if (floating != null && px in selection.floatX until (selection.floatX + selection.floatW) &&
                    py in selection.floatY until (selection.floatY + selection.floatH)) {
                    selectionDragMode = SelectionDragMode.MOVE_FLOATING
                    floatingDragOffsetX = px - selection.floatX
                    floatingDragOffsetY = py - selection.floatY
                } else {
                    commitFloatingSelection()
                    selection.clear()
                    selection.active = true
                    selection.x0 = px; selection.y0 = py
                    selection.x1 = px; selection.y1 = py
                    selectionDragMode = SelectionDragMode.CREATE
                    invalidate()
                }
            }
            Tool.WAND -> {
                // Magic wand: select all 4-connected pixels of the same color
                commitFloatingSelection()
                wandSelectFloodFill(px, py)
                invalidate()
            }
            else -> {}
        }
    }

    private fun continueStroke(px: Int, py: Int) {
        if (px == lastPx && py == lastPy) return
        val p = project ?: return
        when (tool) {
            Tool.PENCIL -> {
                bresenhamCells(lastPx, lastPy, px, py) { x, y ->
                    paintPixelSymmetric(x, y, color)
                    if (p.pixelPerfect) pixelPerfectCheck()
                }
                syncFrameBitmap()
            }
            Tool.ERASER -> {
                bresenhamCells(lastPx, lastPy, px, py) { x, y -> paintPixelSymmetric(x, y, 0) }
                syncFrameBitmap()
            }
            Tool.PICKER -> {
                val c = p.currentFrame.get(px, py)
                if (Color.alpha(c) > 0) onColorPicked?.invoke(c)
            }
            Tool.LINE, Tool.RECT, Tool.RECT_FILL -> { updatePreview(); invalidate() }
            Tool.SELECT -> {
                when (selectionDragMode) {
                    SelectionDragMode.CREATE -> {
                        selection.x1 = px; selection.y1 = py
                        invalidate()
                    }
                    SelectionDragMode.MOVE_FLOATING -> {
                        selection.floatX = px - floatingDragOffsetX
                        selection.floatY = py - floatingDragOffsetY
                        invalidate()
                    }
                    else -> {}
                }
            }
            else -> {}
        }
        lastPx = px; lastPy = py
    }

    private fun endStroke() {
        when (tool) {
            Tool.LINE -> { drawBresenham(startPx, startPy, lastPx, lastPy, color); syncFrameBitmap() }
            Tool.RECT -> { drawRect(startPx, startPy, lastPx, lastPy, color, false); syncFrameBitmap() }
            Tool.RECT_FILL -> { drawRect(startPx, startPy, lastPx, lastPy, color, true); syncFrameBitmap() }
            Tool.SELECT -> {
                if (selectionDragMode == SelectionDragMode.CREATE && selection.active) {
                    // Lift selected pixels into floating
                    liftSelectionToFloating()
                }
                selectionDragMode = SelectionDragMode.NONE
            }
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

    // ---- Symmetry ----
    /**
     * Paint a pixel at (x,y) applying current brush size, symmetry, and dither pattern.
     * For ERASER the dither pattern is ignored (always erase fully).
     */
    private fun paintPixelSymmetric(x: Int, y: Int, c: Int) {
        val p = project ?: return
        // Stylus / S-Pen pressure modulation: scales brush size by current pressure
        val effective = if (p.pressureSensitive) {
            (p.brushSize * currentPressure).toInt().coerceAtLeast(1)
        } else p.brushSize
        val size = effective.coerceAtLeast(1)
        val half = size / 2
        for (dy in 0 until size) for (dx in 0 until size) {
            val px = x - half + dx
            val py = y - half + dy
            // Apply dither pattern (only when painting non-zero color)
            if (c != 0 && p.ditherPattern > 0 && !ditherShouldPaint(px, py, p.ditherPattern)) continue
            val effective = if (c != 0 && p.ditherPattern > 0 && p.ditherPattern == 5)
                p.secondaryColor else c
            paintSinglePixelWithSymmetry(px, py, effective)
        }
        recentPixels.addLast(intArrayOf(x, y))
        while (recentPixels.size > 3) recentPixels.removeFirst()
    }

    private fun paintSinglePixelWithSymmetry(x: Int, y: Int, c: Int) {
        val p = project ?: return
        if (c != 0 && !sketchMode && x in 0 until p.width && y in 0 until p.height) {
            val target = p.currentFrame.get(x, y)
            if (target in p.lockedColors) return
        }
        val sketchBuf: IntArray? = if (sketchMode) ensureSketchBuffer() else null
        val frame = p.currentFrame
        val pw = p.width; val ph = p.height
        val paint: (Int, Int) -> Unit = { sx, sy ->
            if (sketchBuf != null) setBufPixel(sketchBuf, pw, ph, sx, sy, c)
            else frame.set(sx, sy, c)
        }
        applySymmetry(p, x, y, paint)
    }

    private inline fun applySymmetry(p: Project, x: Int, y: Int, paint: (Int, Int) -> Unit) {
        paint(x, y)
        when (p.symmetry) {
            SymmetryAxis.NONE -> {}
            SymmetryAxis.HORIZONTAL -> paint(p.width - 1 - x, y)
            SymmetryAxis.VERTICAL -> paint(x, p.height - 1 - y)
            SymmetryAxis.BOTH -> {
                paint(p.width - 1 - x, y)
                paint(x, p.height - 1 - y)
                paint(p.width - 1 - x, p.height - 1 - y)
            }
            SymmetryAxis.ROTATE_4 -> {
                // 4-fold rotational symmetry around canvas center
                val cx = p.width / 2; val cy = p.height / 2
                val dx = x - cx; val dy = y - cy
                paint(cx + dy, cy - dx)         // 90 deg
                paint(cx - dx, cy - dy)         // 180 deg
                paint(cx - dy, cy + dx)         // 270 deg
            }
        }
    }

    private fun setBufPixel(buf: IntArray, w: Int, h: Int, x: Int, y: Int, c: Int) {
        if (x in 0 until w && y in 0 until h) buf[y * w + x] = c
    }

    /**
     * Returns true if the pixel at (x,y) should be painted given the dither pattern:
     *   1 = checkerboard (paint when (x+y) is even)
     *   2 = vertical lines (paint when x is even)
     *   3 = horizontal lines (paint when y is even)
     *   4 = sparse (paint when x and y both divisible by 2 -> 1/4 density)
     */
    private fun ditherShouldPaint(x: Int, y: Int, pattern: Int): Boolean = when (pattern) {
        1 -> (x + y) and 1 == 0
        2 -> x and 1 == 0
        3 -> y and 1 == 0
        4 -> (x and 1 == 0) && (y and 1 == 0)
        6 -> {
            // Custom 4x4 pattern
            val p = project ?: return true
            val px = ((x % 4) + 4) % 4
            val py = ((y % 4) + 4) % 4
            p.customDither[py][px]
        }
        else -> true
    }

    /**
     * Pixel-perfect: when the last 3 drawn pixels form an L-shape, remove the corner.
     * Example: (0,0), (1,0), (1,1) → remove (1,0). This avoids "double-thick" diagonal lines.
     */
    private fun pixelPerfectCheck() {
        if (recentPixels.size < 3) return
        val a = recentPixels[recentPixels.size - 3]
        val b = recentPixels[recentPixels.size - 2]
        val c = recentPixels[recentPixels.size - 1]
        // a and c must be diagonal neighbors of b
        val abDx = b[0] - a[0]; val abDy = b[1] - a[1]
        val bcDx = c[0] - b[0]; val bcDy = c[1] - b[1]
        val isStraight1 = (abDx != 0 && abDy == 0) || (abDx == 0 && abDy != 0)
        val isStraight2 = (bcDx != 0 && bcDy == 0) || (bcDx == 0 && bcDy != 0)
        val turns = (abDx != bcDx || abDy != bcDy)
        if (isStraight1 && isStraight2 && turns) {
            // Remove the corner pixel b (set to 0)
            project?.currentFrame?.set(b[0], b[1], 0)
            val p = project
            if (p != null) {
                when (p.symmetry) {
                    SymmetryAxis.HORIZONTAL -> p.currentFrame.set(p.width - 1 - b[0], b[1], 0)
                    SymmetryAxis.VERTICAL -> p.currentFrame.set(b[0], p.height - 1 - b[1], 0)
                    SymmetryAxis.BOTH -> {
                        p.currentFrame.set(p.width - 1 - b[0], b[1], 0)
                        p.currentFrame.set(b[0], p.height - 1 - b[1], 0)
                        p.currentFrame.set(p.width - 1 - b[0], p.height - 1 - b[1], 0)
                    }
                    else -> {}
                }
            }
            // Keep a and c only
            recentPixels.removeAt(recentPixels.size - 2)
        }
    }

    // ---- Selection ----
    private fun liftSelectionToFloating() {
        val p = project ?: return
        if (!selection.active) return
        val w = selection.width
        val h = selection.height
        if (w <= 0 || h <= 0) return
        val floating = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val sx = selection.xMin + x
            val sy = selection.yMin + y
            floating[y * w + x] = p.currentFrame.get(sx, sy)
            p.currentFrame.set(sx, sy, 0) // clear source
        }
        selection.floating = floating
        selection.floatW = w
        selection.floatH = h
        selection.floatX = selection.xMin
        selection.floatY = selection.yMin
        syncFrameBitmap()
    }

    fun commitFloatingSelection() {
        val p = project ?: return
        val floating = selection.floating ?: return
        for (y in 0 until selection.floatH) for (x in 0 until selection.floatW) {
            val c = floating[y * selection.floatW + x]
            if (Color.alpha(c) >= 128) {
                p.currentFrame.set(selection.floatX + x, selection.floatY + y, c)
            }
        }
        selection.clear()
        syncFrameBitmap()
        invalidate()
    }

    fun cutSelectionToClipboard(): IntArray? {
        if (selection.floating != null) {
            // Floating: convert to clipboard, clear floating without committing
            val out = selection.floating?.copyOf()
            selection.clear()
            invalidate()
            return out
        }
        return null
    }

    fun copySelectionToClipboard(): Pair<Int, IntArray>? {
        val floating = selection.floating ?: return null
        return selection.floatW to floating.copyOf()
    }

    fun pasteClipboard(w: Int, pixels: IntArray, x: Int, y: Int) {
        commitFloatingSelection()
        selection.clear()
        selection.active = true
        selection.floatW = w
        selection.floatH = pixels.size / w
        selection.floatX = x
        selection.floatY = y
        selection.floating = pixels.copyOf()
        invalidate()
    }

    // ---- Shape drawing ----
    private fun updatePreview() {
        previewPath.clear()
        when (tool) {
            Tool.LINE -> bresenhamCells(startPx, startPy, lastPx, lastPy) { x, y ->
                previewPath.add(intArrayOf(x, y))
                addSymmetricPreview(x, y)
            }
            Tool.RECT, Tool.RECT_FILL -> {
                val xMin = min(startPx, lastPx); val xMax = max(startPx, lastPx)
                val yMin = min(startPy, lastPy); val yMax = max(startPy, lastPy)
                if (tool == Tool.RECT_FILL) {
                    for (y in yMin..yMax) for (x in xMin..xMax) {
                        previewPath.add(intArrayOf(x, y)); addSymmetricPreview(x, y)
                    }
                } else {
                    for (x in xMin..xMax) {
                        previewPath.add(intArrayOf(x, yMin)); previewPath.add(intArrayOf(x, yMax))
                        addSymmetricPreview(x, yMin); addSymmetricPreview(x, yMax)
                    }
                    for (y in yMin..yMax) {
                        previewPath.add(intArrayOf(xMin, y)); previewPath.add(intArrayOf(xMax, y))
                        addSymmetricPreview(xMin, y); addSymmetricPreview(xMax, y)
                    }
                }
            }
            else -> {}
        }
    }

    private fun addSymmetricPreview(x: Int, y: Int) {
        val p = project ?: return
        when (p.symmetry) {
            SymmetryAxis.HORIZONTAL -> previewPath.add(intArrayOf(p.width - 1 - x, y))
            SymmetryAxis.VERTICAL -> previewPath.add(intArrayOf(x, p.height - 1 - y))
            SymmetryAxis.BOTH -> {
                previewPath.add(intArrayOf(p.width - 1 - x, y))
                previewPath.add(intArrayOf(x, p.height - 1 - y))
                previewPath.add(intArrayOf(p.width - 1 - x, p.height - 1 - y))
            }
            else -> {}
        }
    }

    private fun drawBresenham(x0: Int, y0: Int, x1: Int, y1: Int, c: Int) {
        bresenhamCells(x0, y0, x1, y1) { x, y -> paintPixelSymmetric(x, y, c) }
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
        if (fill) {
            for (y in yMin..yMax) for (x in xMin..xMax) paintPixelSymmetric(x, y, c)
        } else {
            for (x in xMin..xMax) { paintPixelSymmetric(x, yMin, c); paintPixelSymmetric(x, yMax, c) }
            for (y in yMin..yMax) { paintPixelSymmetric(xMin, y, c); paintPixelSymmetric(xMax, y, c) }
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
        selection.clear()
        syncFrameBitmap()
        onProjectChanged?.invoke()
    }

    /** Magic wand selection: flood-select all 4-connected pixels matching the target color. */
    private fun wandSelectFloodFill(x: Int, y: Int) {
        val p = project ?: return
        val f = p.currentFrame
        if (x !in 0 until f.width || y !in 0 until f.height) return
        val target = f.get(x, y)
        // Visited mask
        val mask = BooleanArray(f.width * f.height)
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(x, y))
        var minX = x; var maxX = x; var minY = y; var maxY = y
        while (stack.isNotEmpty()) {
            val cell = stack.removeLast()
            val cx = cell[0]; val cy = cell[1]
            if (cx !in 0 until f.width || cy !in 0 until f.height) continue
            val idx = cy * f.width + cx
            if (mask[idx]) continue
            if (f.get(cx, cy) != target) continue
            mask[idx] = true
            if (cx < minX) minX = cx; if (cx > maxX) maxX = cx
            if (cy < minY) minY = cy; if (cy > maxY) maxY = cy
            stack.addLast(intArrayOf(cx + 1, cy))
            stack.addLast(intArrayOf(cx - 1, cy))
            stack.addLast(intArrayOf(cx, cy + 1))
            stack.addLast(intArrayOf(cx, cy - 1))
        }
        // Build floating selection from the masked region
        val w = maxX - minX + 1
        val h = maxY - minY + 1
        val floating = IntArray(w * h)
        for (yy in minY..maxY) for (xx in minX..maxX) {
            val li = (yy - minY) * w + (xx - minX)
            if (mask[yy * f.width + xx]) {
                floating[li] = f.get(xx, yy)
                f.set(xx, yy, 0)
            }
        }
        selection.clear()
        selection.active = true
        selection.x0 = minX; selection.y0 = minY
        selection.x1 = maxX; selection.y1 = maxY
        selection.floating = floating
        selection.floatW = w
        selection.floatH = h
        selection.floatX = minX
        selection.floatY = minY
        syncFrameBitmap()
    }
}
