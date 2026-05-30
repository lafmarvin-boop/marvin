package com.pixelhero.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.GestureDetector
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
    /** True while a stylus is hovering over the screen without touching. */
    private var stylusHovering = false

    /** Last reported pressure from the pointer (S-Pen / stylus). 0..1, default 1. */
    private var currentPressure: Float = 1f

    /** Stroke stabilizer: when > 0, drawing input is smoothed via weighted average. */
    var stabilizerStrength: Int = 0  // 0=off, 1-5 = smoothing radius
    private val recentInputs = ArrayDeque<FloatArray>()

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

    /**
     * Optional reference image drawn ON TOP of the current frame (faded)
     * for rotoscoping. Unlike [bgBitmap] which sits UNDER the drawing,
     * this one is a ghost overlay the artist redraws under.
     */
    var referenceBitmap: Bitmap? = null
        set(value) { field = value; invalidate() }
    var referenceOpacity: Float = 0.4f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    /**
     * Palm rejection: when ON, finger touches are ignored if a stylus was
     * active within the last [palmRejectionWindowMs] ms. When you put the
     * stylus down, your palm resting on the screen is silently dropped.
     */
    var palmRejection: Boolean = true
    private var lastStylusEventTime: Long = 0L
    private val palmRejectionWindowMs = 1500L
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

    /** Points traced during a LASSO drag (canvas-pixel coords). */
    private val lassoPoints = ArrayList<IntArray>()

    /**
     * Selection refine mode: while ADD or SUB, touches on the canvas modify
     * the selection mask instead of drawing. NONE disables.
     */
    enum class SelectionRefineMode { NONE, ADD, SUB }
    var selectionRefineMode: SelectionRefineMode = SelectionRefineMode.NONE
        set(value) { field = value; invalidate() }

    // Listeners
    var onProjectChanged: (() -> Unit)? = null
    var onColorPicked: ((Int) -> Unit)? = null
    var onStrokeStart: (() -> Unit)? = null
    /** Fired after a SELECT-tool rectangle is finished and lifted to floating. */
    var onSelectionCreated: (() -> Unit)? = null
    /** Fired whenever any selection state (active / floating / mask) changes. */
    var onSelectionStateChanged: (() -> Unit)? = null
    /** Fired when the user requests a frame navigation (delta = ±1 typically). */
    var onFrameSwipe: ((delta: Int) -> Unit)? = null

    private var tapCount = 0
    private var lastTapTime = 0L
    private val tapClusterMs = 280L
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        // Custom multi-tap counter so we can distinguish double / triple tap
        // without waiting through onSingleTapConfirmed delay every time.
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val now = System.currentTimeMillis()
            tapCount = if (now - lastTapTime < tapClusterMs) tapCount + 1 else 1
            lastTapTime = now
            postDelayed({
                if (System.currentTimeMillis() - lastTapTime >= tapClusterMs) {
                    when (tapCount) {
                        2 -> zoomReset()             // double-tap → fit
                        3 -> setZoom(1f)             // triple-tap → 1:1
                    }
                    tapCount = 0
                }
            }, tapClusterMs + 10)
            return false
        }
    })

    private var swipeStartCx = 0f
    private var swipeStartCy = 0f
    private var swipeDispatched = false
    var onStrokeEnd: (() -> Unit)? = null

    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val gridPaint = Paint().apply { color = 0x33FFFFFF; style = Paint.Style.STROKE; strokeWidth = 0f }
    private val gridMajorPaint = Paint().apply { color = 0x66A5B4FF; style = Paint.Style.STROKE; strokeWidth = 0f }
    private val checkerPaint1 = Paint().apply { color = 0xFFE8E8EE.toInt() }
    private val checkerPaint2 = Paint().apply { color = 0xFFCCCCD5.toInt() }
    private val borderPaint = Paint().apply { color = 0xFF6677AA.toInt(); style = Paint.Style.STROKE; strokeWidth = 2f }
    private val previewPaint = Paint().apply { style = Paint.Style.FILL }
    /**
     * "Marching ants" selection outline: alternating black & white dashes that
     * scroll along the boundary so the selection stays visible against any
     * underlying art color. Two paints; we render them with offset dash phases.
     */
    private val antsBlack = Paint().apply {
        color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 0f
        pathEffect = DashPathEffect(floatArrayOf(2.0f, 2.0f), 0f)
    }
    private val antsWhite = Paint().apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 0f
        pathEffect = DashPathEffect(floatArrayOf(2.0f, 2.0f), 2.0f)  // phase offset
    }
    private var antsPhase = 0f
    private val selDashPaint = Paint().apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 0f
        pathEffect = DashPathEffect(floatArrayOf(2f, 2f), 0f)
    }
    /** Soft cyan overlay drawn over every selected cell so the user can SEE the mask shape. */
    private val selFillPaint = Paint().apply {
        color = 0x4455CCFF; style = Paint.Style.FILL
    }
    /** Crisp outline drawn along the boundary of the selection mask. */
    private val selOutlinePaint = Paint().apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 0.18f
    }
    private val selSymPaint = Paint().apply {
        color = 0xCCFFB85B.toInt()  // amber dashed axis, always visible on any art
        style = Paint.Style.STROKE; strokeWidth = 0.3f
        pathEffect = DashPathEffect(floatArrayOf(2f, 2f), 0f)
    }

    init { isFocusable = true; isClickable = true }

    private var selectionScaleAccum: Float = 1f
    /** Set in onScaleBegin: true only when the pinch focus started inside the
     *  floating selection. Without this, [onScale] would resize the floating
     *  on every pinch, even when the user is just zooming the canvas. */
    private var pinchResizesSelection: Boolean = false
    private val scaleGD = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            pinchResizesSelection = false
            val sel = selection
            if (sel.floating != null) {
                val coords = clientToPixel(d.focusX, d.focusY)
                val px = coords[0]; val py = coords[1]
                if (px in sel.floatX until sel.floatX + sel.floatW &&
                    py in sel.floatY until sel.floatY + sel.floatH) {
                    pinchResizesSelection = true
                    selectionScaleAccum = 1f
                }
            }
            return true
        }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            if (pinchResizesSelection && selection.floating != null) {
                selectionScaleAccum *= d.scaleFactor
                // Apply the accumulated scale to the floating buffer when it
                // crosses a threshold worth re-rasterizing (avoid wasted work
                // on micro-zoom).
                if (selectionScaleAccum > 1.12f || selectionScaleAccum < 0.9f) {
                    resizeFloatingSelection(selectionScaleAccum)
                    selectionScaleAccum = 1f
                    invalidate()
                }
                return true
            }
            // Canvas zoom path (pinch did NOT start inside a floating selection).
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

    /** Resize the floating selection in place using nearest-neighbor. */
    private fun resizeFloatingSelection(ratio: Float) {
        val floating = selection.floating ?: return
        val oldW = selection.floatW; val oldH = selection.floatH
        val newW = (oldW * ratio).toInt().coerceIn(1, 4096)
        val newH = (oldH * ratio).toInt().coerceIn(1, 4096)
        if (newW == oldW && newH == oldH) return
        val out = IntArray(newW * newH)
        for (y in 0 until newH) for (x in 0 until newW) {
            val sx = (x.toFloat() / newW * oldW).toInt().coerceIn(0, oldW - 1)
            val sy = (y.toFloat() / newH * oldH).toInt().coerceIn(0, oldH - 1)
            out[y * newW + x] = floating[sy * oldW + sx]
        }
        // Keep the visual center fixed when growing/shrinking.
        val centerX = selection.floatX + oldW / 2
        val centerY = selection.floatY + oldH / 2
        selection.floating = out
        selection.floatW = newW
        selection.floatH = newH
        selection.floatX = centerX - newW / 2
        selection.floatY = centerY - newH / 2
        // Also expand the bbox so the marching ants outline reflects the new size.
        selection.x0 = selection.floatX; selection.y0 = selection.floatY
        selection.x1 = selection.floatX + newW - 1
        selection.y1 = selection.floatY + newH - 1
        // Mask is no longer applicable after resize — drop it.
        selection.mask = null
        onSelectionStateChanged?.invoke()
    }

    /**
     * Stylus long-press detector: when the stylus is held still for ~400 ms
     * without lift, we switch the current gesture into a pan (canvas drag)
     * until the stylus is lifted. Lets users move around the drawing without
     * leaving their stylus tool.
     */
    private var stylusLongPressArmed = false
    private var stylusLongPressActive = false
    private var stylusLongPressX = 0f
    private var stylusLongPressY = 0f
    private val stylusLongPressDelayMs = 400L
    private val stylusLongPressMoveTolerance = 8f  // px before we cancel arming
    /**
     * Finger long-press = momentary eyedropper: hold a finger still on a
     * pixel for ~550 ms and the colour under it is picked + the user
     * stays on their current tool. Speeds up "draw → resample → keep
     * drawing" by 2 taps. Stylus long-press takes the pan path instead.
     */
    private var fingerEyedropperArmed = false
    private var fingerEyedropperX = 0f
    private var fingerEyedropperY = 0f
    private val fingerEyedropperDelayMs = 550L
    private val fingerEyedropperRunnable = Runnable {
        if (!fingerEyedropperArmed) return@Runnable
        fingerEyedropperArmed = false
        val coords = clientToPixel(fingerEyedropperX, fingerEyedropperY)
        val p = project ?: return@Runnable
        val c = p.currentFrame.get(coords[0], coords[1])
        if (Color.alpha(c) > 0) {
            // Undo the in-progress stroke (any pixel painted on ACTION_DOWN).
            cancelStroke()
            onColorPicked?.invoke(c)
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private val stylusLongPressRunnable = Runnable {
        if (stylusLongPressArmed) {
            stylusLongPressActive = true
            // Cancel any in-progress stroke and start panning from current touch.
            cancelStroke()
            panStartX = stylusLongPressX; panStartY = stylusLongPressY
            translateStartX = translateX; translateStartY = translateY
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }

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
        // Return existing onion bitmaps to the pool so the next rebuild
        // doesn't re-allocate. The pool caps reuse per (w, h) bucket.
        onionBmps.forEach { BitmapPool.release(it.first) }
        onionBmps.clear()
        if (p.onionRange <= 0) return
        for (off in 1..p.onionRange) {
            val idx = p.currentIndex - off
            if (idx < 0) break
            val bmp = BitmapPool.acquire(p.width, p.height)
            bmp.setPixels(frameComposite(p.frames[idx]), 0, p.width, 0, 0, p.width, p.height)
            val tint = 0x4400AAFF.toInt()
            onionBmps.add(Triple(bmp, -off, tint))
        }
        if (!p.onionTrailOnly) {
            for (off in 1..p.onionRange) {
                val idx = p.currentIndex + off
                if (idx >= p.frames.size) break
                val bmp = BitmapPool.acquire(p.width, p.height)
                bmp.setPixels(frameComposite(p.frames[idx]), 0, p.width, 0, 0, p.width, p.height)
                val tint = 0x44FF4477.toInt()
                onionBmps.add(Triple(bmp, off, tint))
            }
        }
    }

    fun syncFrameBitmap() {
        val p = project ?: return
        val bmp = frameBmp ?: return
        // ALWAYS composite — even a single-layer frame must be hidden when
        // its only layer is invisible. The previous shortcut returned the
        // active layer's raw pixels and bypassed visibility flags.
        val frameData = p.currentFrame.composited()
        val bg = p.globalBackground
        if (bg != null) {
            // Composite globalBg under frameData
            val combined = IntArray(p.width * p.height)
            bg.pixels.copyInto(combined)
            for (i in combined.indices) {
                val fc = frameData[i]
                if ((fc ushr 24) and 0xFF >= 128) combined[i] = fc
            }
            bmp.setPixels(combined, 0, p.width, 0, 0, p.width, p.height)
        } else {
            bmp.setPixels(frameData, 0, p.width, 0, 0, p.width, p.height)
        }
        invalidate()
    }

    fun syncOnionBitmap() {
        rebuildOnionBitmaps()
        invalidate()
    }

    private fun frameComposite(f: Frame): IntArray = f.composited()

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

        // Onion skin layers — alpha decays linearly with distance from the
        // current frame so a clear "depth" is visible (closest = most opaque).
        for ((bmp, off, _) in onionBmps) {
            val absOff = abs(off)
            val alpha = (130 - 28 * absOff).coerceIn(18, 130)
            paint.alpha = alpha
            val tint = if (off < 0) p.onionColorPrev else p.onionColorNext
            paint.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_ATOP)
            canvas.drawBitmap(bmp, 0f, 0f, paint)
            paint.colorFilter = null
            paint.alpha = 255
        }

        // Current frame
        frameBmp?.let { canvas.drawBitmap(it, 0f, 0f, paint) }

        // Reference image: drawn ABOVE the frame at user-controlled opacity
        // so the artist can decalque under it (rotoscoping).
        referenceBitmap?.let { ref ->
            paint.alpha = (referenceOpacity * 255).toInt().coerceIn(0, 255)
            canvas.save()
            canvas.clipRect(0, 0, w, h)
            // Fit into canvas (preserve aspect ratio)
            val ratio = min(w.toFloat() / ref.width, h.toFloat() / ref.height)
            val dw = ref.width * ratio
            val dh = ref.height * ratio
            val dx = (w - dw) / 2f
            val dy = (h - dh) / 2f
            canvas.drawBitmap(ref, null, RectF(dx, dy, dx + dw, dy + dh), paint)
            canvas.restore()
            paint.alpha = 255
        }

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

        // Lasso in-progress path (drawn while the user is still tracing)
        if (lassoPoints.size >= 2) {
            val lassoPaint = Paint().apply {
                color = 0xFFA5B4FF.toInt(); style = Paint.Style.STROKE
                strokeWidth = 0.3f
            }
            val path = android.graphics.Path()
            path.moveTo(lassoPoints[0][0] + 0.5f, lassoPoints[0][1] + 0.5f)
            for (i in 1 until lassoPoints.size) {
                path.lineTo(lassoPoints[i][0] + 0.5f, lassoPoints[i][1] + 0.5f)
            }
            canvas.drawPath(path, lassoPaint)
        }

        // Symmetry axis indicator — dashed amber lines so the user always
        // sees where the mirror happens, on any underlying art.
        if (p.symmetry != SymmetryAxis.NONE) {
            if (p.symmetry == SymmetryAxis.HORIZONTAL || p.symmetry == SymmetryAxis.BOTH ||
                p.symmetry == SymmetryAxis.ROTATE_4) {
                val cx = w / 2f
                canvas.drawLine(cx, 0f, cx, h.toFloat(), selSymPaint)
            }
            if (p.symmetry == SymmetryAxis.VERTICAL || p.symmetry == SymmetryAxis.BOTH ||
                p.symmetry == SymmetryAxis.ROTATE_4) {
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

        // Selection — render the exact shape (mask) when present, otherwise the bbox.
        // Outline uses alternating black/white "marching ants" dashes so the
        // selection stays visible against any underlying art color.
        if (selection.active) {
            val mask = selection.mask
            val floating = selection.floating
            val ox: Float; val oy: Float; val rectW: Float; val rectH: Float
            if (floating != null) {
                ox = selection.floatX.toFloat(); oy = selection.floatY.toFloat()
                rectW = selection.floatW.toFloat(); rectH = selection.floatH.toFloat()
            } else {
                ox = selection.xMin.toFloat(); oy = selection.yMin.toFloat()
                rectW = selection.width.toFloat(); rectH = selection.height.toFloat()
            }
            // Animate the dash phase so the ants march. Adjusting phase needs
            // a new DashPathEffect instance because phase isn't a settable prop.
            antsBlack.pathEffect = DashPathEffect(floatArrayOf(2f, 2f), antsPhase)
            antsWhite.pathEffect = DashPathEffect(floatArrayOf(2f, 2f), antsPhase + 2f)
            antsPhase = (antsPhase + 0.2f) % 4f
            if (mask == null) {
                val r = RectF(ox, oy, ox + rectW, oy + rectH)
                canvas.drawRect(r, antsBlack)
                canvas.drawRect(r, antsWhite)
            } else {
                val mw = selection.width
                val mh = selection.height
                // Build an outline Path along the boundary edges
                val path = android.graphics.Path()
                for (yy in 0 until mh) for (xx in 0 until mw) {
                    if (!mask[yy * mw + xx]) continue
                    val left = xx == 0 || !mask[yy * mw + (xx - 1)]
                    val right = xx == mw - 1 || !mask[yy * mw + (xx + 1)]
                    val top = yy == 0 || !mask[(yy - 1) * mw + xx]
                    val bottom = yy == mh - 1 || !mask[(yy + 1) * mw + xx]
                    val cx0 = ox + xx; val cy0 = oy + yy
                    if (top)    { path.moveTo(cx0, cy0); path.lineTo(cx0 + 1f, cy0) }
                    if (bottom) { path.moveTo(cx0, cy0 + 1f); path.lineTo(cx0 + 1f, cy0 + 1f) }
                    if (left)   { path.moveTo(cx0, cy0); path.lineTo(cx0, cy0 + 1f) }
                    if (right)  { path.moveTo(cx0 + 1f, cy0); path.lineTo(cx0 + 1f, cy0 + 1f) }
                }
                canvas.drawPath(path, antsBlack)
                canvas.drawPath(path, antsWhite)
            }
            // Keep animating while a selection is on screen.
            postInvalidateOnAnimation()
        }

        // Brush hover outline (drawing OR stylus hover-without-touch)
        if ((isDrawing || stylusHovering) && hoverPx >= 0 && hoverPy >= 0 &&
            (tool == Tool.PENCIL || tool == Tool.ERASER)) {
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
    /**
     * Stylus hover: when a stylus is in proximity without touching, Android
     * sends ACTION_HOVER_* events. We update [hoverPx]/[hoverPy] and flip
     * [stylusHovering] so the brush outline tracks the cursor BEFORE the
     * user actually puts the pen down — gives precise visual feedback.
     */
    override fun onHoverEvent(event: MotionEvent): Boolean {
        val tt = event.getToolType(0)
        if (tt != MotionEvent.TOOL_TYPE_STYLUS && tt != MotionEvent.TOOL_TYPE_ERASER)
            return super.onHoverEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                val coords = clientToPixel(event.x, event.y)
                hoverPx = coords[0]; hoverPy = coords[1]
                stylusHovering = true
                invalidate()
            }
            MotionEvent.ACTION_HOVER_EXIT -> {
                stylusHovering = false
                invalidate()
            }
        }
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Palm rejection: if any pointer in this batch is a stylus, mark the
        // stylus active. While the stylus is recently active, finger pointers
        // are silently dropped — the user's palm resting on the tablet stops
        // creating fake taps. When the stylus has been away long enough
        // (palmRejectionWindowMs), fingers work normally again.
        if (palmRejection) {
            var hasStylus = false
            for (i in 0 until event.pointerCount) {
                val tt = event.getToolType(i)
                if (tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER) {
                    hasStylus = true
                    lastStylusEventTime = System.currentTimeMillis()
                    break
                }
            }
            if (!hasStylus) {
                val sinceStylus = System.currentTimeMillis() - lastStylusEventTime
                if (sinceStylus < palmRejectionWindowMs) {
                    // Within the rejection window: drop finger events entirely.
                    return true
                }
            }
        }
        scaleGD.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        val action = event.actionMasked
        val idx = event.actionIndex
        val primaryIsStylus = run {
            val tt = event.getToolType(event.actionIndex.coerceAtMost(event.pointerCount - 1))
            tt == MotionEvent.TOOL_TYPE_STYLUS || tt == MotionEvent.TOOL_TYPE_ERASER
        }
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val id = event.getPointerId(idx)
                activePointers[id] = PointF(event.getX(idx), event.getY(idx))
                currentPressure = event.getPressure(idx).coerceIn(0.1f, 1f).takeIf { it > 0f } ?: 1f
                // Arm stylus long-press: schedule pan-mode after the delay if
                // the stylus stays still.
                if (primaryIsStylus && activePointers.size == 1) {
                    stylusLongPressArmed = true
                    stylusLongPressActive = false
                    stylusLongPressX = event.x
                    stylusLongPressY = event.y
                    postDelayed(stylusLongPressRunnable, stylusLongPressDelayMs)
                }
                // Finger long-press = momentary eyedropper (skip for stylus,
                // skip for tools that already do something specific with hold).
                if (!primaryIsStylus && activePointers.size == 1 &&
                    tool != Tool.PICKER && tool != Tool.MOVE && tool != Tool.SELECT &&
                    tool != Tool.LASSO && tool != Tool.WAND) {
                    fingerEyedropperArmed = true
                    fingerEyedropperX = event.x
                    fingerEyedropperY = event.y
                    postDelayed(fingerEyedropperRunnable, fingerEyedropperDelayMs)
                }
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
                    // Track swipe centroid so a horizontal 2-finger drag flips frames.
                    swipeStartCx = panStartX
                    swipeStartCy = panStartY
                    swipeDispatched = false
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
                // If the stylus moved too much before the long-press timer
                // fires, cancel the arming so we don't grab a normal stroke.
                if (stylusLongPressArmed && !stylusLongPressActive) {
                    val dx = event.x - stylusLongPressX
                    val dy = event.y - stylusLongPressY
                    if (dx * dx + dy * dy > stylusLongPressMoveTolerance * stylusLongPressMoveTolerance) {
                        stylusLongPressArmed = false
                        removeCallbacks(stylusLongPressRunnable)
                    }
                }
                if (fingerEyedropperArmed) {
                    val dx = event.x - fingerEyedropperX
                    val dy = event.y - fingerEyedropperY
                    if (dx * dx + dy * dy > 8f * 8f) {  // 8 px tolerance
                        fingerEyedropperArmed = false
                        removeCallbacks(fingerEyedropperRunnable)
                    }
                }
                // Stylus long-press is ACTIVE → behave like pan.
                if (stylusLongPressActive && activePointers.size == 1) {
                    translateX = translateStartX + (event.x - panStartX)
                    translateY = translateStartY + (event.y - panStartY)
                    invalidate()
                } else if (pinching && activePointers.size == 2) {
                    val pts = activePointers.values.toList()
                    val cx = (pts[0].x + pts[1].x) / 2f
                    val cy = (pts[0].y + pts[1].y) / 2f
                    translateX = translateStartX + (cx - panStartX)
                    translateY = translateStartY + (cy - panStartY)
                    invalidate()
                    // 2-finger horizontal swipe → frame navigation. Fires once
                    // per gesture (resets on UP). Requires mostly horizontal
                    // motion so a pan or pinch doesn't accidentally trigger.
                    if (!swipeDispatched) {
                        val dx = cx - swipeStartCx
                        val dy = cy - swipeStartCy
                        val swipeThreshold = 160f
                        if (kotlin.math.abs(dx) > swipeThreshold && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 2) {
                            swipeDispatched = true
                            onFrameSwipe?.invoke(if (dx < 0) +1 else -1)
                            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        }
                    }
                } else if (activePointers.size == 1) {
                    if (tool == Tool.MOVE) {
                        translateX = translateStartX + (event.x - panStartX)
                        translateY = translateStartY + (event.y - panStartY)
                        invalidate()
                    } else if (isDrawing) {
                        val coords = clientToPixel(event.x, event.y)
                        continueStroke(coords[0], coords[1])
                        hoverPx = coords[0]; hoverPy = coords[1]
                    } else if (selectionRefineMode != SelectionRefineMode.NONE && selection.active) {
                        // Drag-paint in refine mode (beginStroke returned early without
                        // setting isDrawing, so we route MOVE events here directly).
                        val coords = clientToPixel(event.x, event.y)
                        refineMaskAt(coords[0], coords[1])
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                activePointers.remove(event.getPointerId(idx))
                if (activePointers.size < 2) pinching = false
                if (activePointers.isEmpty() && isDrawing) endStroke()
                // Reset stylus long-press state on lift.
                if (activePointers.isEmpty()) {
                    stylusLongPressArmed = false
                    stylusLongPressActive = false
                    removeCallbacks(stylusLongPressRunnable)
                    fingerEyedropperArmed = false
                    removeCallbacks(fingerEyedropperRunnable)
                }
            }
        }
        return true
    }

    private fun clientToPixel(x: Float, y: Float): IntArray {
        // Adaptive stroke stabilizer: when ON, smoothing strength scales DOWN
        // as input speed increases. Slow strokes get full smoothing (clean
        // curves), fast strokes get little (no lag, no soggy lines).
        if (stabilizerStrength > 0) {
            recentInputs.addLast(floatArrayOf(x, y))
            // Cap buffer to max possible smoothing window
            while (recentInputs.size > stabilizerStrength + 1) recentInputs.removeFirst()
            // Measure recent input speed: distance covered by the last 2 samples.
            val speed = if (recentInputs.size >= 2) {
                val a = recentInputs[recentInputs.size - 2]
                val b = recentInputs[recentInputs.size - 1]
                val dx = b[0] - a[0]; val dy = b[1] - a[1]
                kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            } else 0f
            // Fast = speed > 30 px/event → barely any smoothing.
            // Slow = speed < 4 px → full smoothing.
            val speedFactor = (1f - ((speed - 4f) / 26f)).coerceIn(0f, 1f)
            val effective = (stabilizerStrength * speedFactor).toInt().coerceAtLeast(0)
            // Weighted average over the most recent (effective+1) samples.
            val window = (effective + 1).coerceAtMost(recentInputs.size)
            var sumX = 0f; var sumY = 0f; var sumW = 0f
            for (i in 0 until window) {
                val p = recentInputs[recentInputs.size - window + i]
                val w = (i + 1).toFloat()
                sumX += p[0] * w; sumY += p[1] * w; sumW += w
            }
            val sx = if (sumW > 0) sumX / sumW else x
            val sy = if (sumW > 0) sumY / sumW else y
            val px = floor((sx - translateX) / scale).toInt()
            val py = floor((sy - translateY) / scale).toInt()
            return intArrayOf(px, py)
        }
        val px = floor((x - translateX) / scale).toInt()
        val py = floor((y - translateY) / scale).toInt()
        return intArrayOf(px, py)
    }

    // -- Drawing --
    private fun beginStroke(px: Int, py: Int) {
        val p = project ?: return
        // Refine mode short-circuits all tool behavior — touching a pixel only
        // adds/removes it from the selection mask.
        if (selectionRefineMode != SelectionRefineMode.NONE && selection.active) {
            refineMaskAt(px, py)
            return
        }
        isDrawing = true
        startPx = px; startPy = py
        lastPx = px; lastPy = py
        recentPixels.clear()
        recentInputs.clear()
        onStrokeStart?.invoke()
        when (tool) {
            Tool.PENCIL -> { paintPixelSymmetric(px, py, color); syncFrameBitmap() }
            Tool.ERASER -> { paintPixelSymmetric(px, py, 0); syncFrameBitmap() }
            Tool.FILL -> { floodFill(px, py, color); syncFrameBitmap(); onProjectChanged?.invoke() }
            Tool.UNFILL -> { floodFill(px, py, 0); syncFrameBitmap(); onProjectChanged?.invoke() }
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
            Tool.LASSO -> {
                // If the user taps INSIDE the floating selection created by a
                // previous lasso, treat it as a drag (move the selection)
                // instead of immediately committing it and starting a new path.
                val floating = selection.floating
                if (floating != null && px in selection.floatX until (selection.floatX + selection.floatW) &&
                    py in selection.floatY until (selection.floatY + selection.floatH)) {
                    selectionDragMode = SelectionDragMode.MOVE_FLOATING
                    floatingDragOffsetX = px - selection.floatX
                    floatingDragOffsetY = py - selection.floatY
                } else {
                    commitFloatingSelection()
                    selection.clear()
                    lassoPoints.clear()
                    lassoPoints.add(intArrayOf(px, py))
                    invalidate()
                }
            }
            else -> {}
        }
    }

    private fun continueStroke(px: Int, py: Int) {
        if (selectionRefineMode != SelectionRefineMode.NONE && selection.active) {
            refineMaskAt(px, py)
            return
        }
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
            Tool.LASSO -> {
                if (selectionDragMode == SelectionDragMode.MOVE_FLOATING) {
                    selection.floatX = px - floatingDragOffsetX
                    selection.floatY = py - floatingDragOffsetY
                    invalidate()
                } else if (lassoPoints.isEmpty() || lassoPoints.last()[0] != px || lassoPoints.last()[1] != py) {
                    lassoPoints.add(intArrayOf(px, py))
                    invalidate()
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
                    // Lift selected pixels into floating — they become draggable.
                    liftSelectionToFloating()
                    onSelectionCreated?.invoke()
                }
                selectionDragMode = SelectionDragMode.NONE
            }
            Tool.LASSO -> {
                if (selectionDragMode == SelectionDragMode.MOVE_FLOATING) {
                    // Drag-move ended; keep the floating selection where it is.
                    selectionDragMode = SelectionDragMode.NONE
                    lassoPoints.clear()
                } else if (lassoPoints.size >= 3) {
                    finalizeLassoSelection()
                    onSelectionCreated?.invoke()
                }
                lassoPoints.clear()
                invalidate()
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
                val cx = p.width / 2; val cy = p.height / 2
                val dx = x - cx; val dy = y - cy
                paint(cx + dy, cy - dx)
                paint(cx - dx, cy - dy)
                paint(cx - dy, cy + dx)
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
    private fun ditherShouldPaint(x: Int, y: Int, pattern: Int): Boolean {
        return when (pattern) {
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
    fun liftSelectionToFloating() {
        val p = project ?: return
        if (!selection.active) return
        val w = selection.width
        val h = selection.height
        if (w <= 0 || h <= 0) return
        val mask = selection.mask
        val floating = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val sx = selection.xMin + x
            val sy = selection.yMin + y
            val inMask = mask?.let { it.getOrNull(y * w + x) == true } ?: true
            if (inMask) {
                floating[y * w + x] = p.currentFrame.get(sx, sy)
                p.currentFrame.set(sx, sy, 0)  // clear source
            }
            // else: leave floating[i] = 0 (transparent) so committing later
            // doesn't paint anything outside the lasso shape.
        }
        selection.floating = floating
        selection.floatW = w
        selection.floatH = h
        selection.floatX = selection.xMin
        selection.floatY = selection.yMin
        syncFrameBitmap()
        onSelectionStateChanged?.invoke()
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
        onSelectionStateChanged?.invoke()
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

    /**
     * Close the lasso polygon, rasterize it to a mask, set the selection
     * bbox + mask, lift the floating content. Even-odd point-in-polygon
     * fill of every pixel inside the bbox.
     */
    private fun finalizeLassoSelection() {
        val p = project ?: return
        if (lassoPoints.size < 3) return
        // Compute polygon bbox clipped to canvas
        var xMin = Int.MAX_VALUE; var xMax = Int.MIN_VALUE
        var yMin = Int.MAX_VALUE; var yMax = Int.MIN_VALUE
        for (pt in lassoPoints) {
            if (pt[0] < xMin) xMin = pt[0]; if (pt[0] > xMax) xMax = pt[0]
            if (pt[1] < yMin) yMin = pt[1]; if (pt[1] > yMax) yMax = pt[1]
        }
        xMin = xMin.coerceAtLeast(0); yMin = yMin.coerceAtLeast(0)
        xMax = xMax.coerceAtMost(p.width - 1); yMax = yMax.coerceAtMost(p.height - 1)
        if (xMax < xMin || yMax < yMin) return
        val w = xMax - xMin + 1
        val h = yMax - yMin + 1
        val mask = BooleanArray(w * h)
        // Point-in-polygon for every pixel center. Even-odd rule via horizontal ray cast.
        val n = lassoPoints.size
        for (yy in 0 until h) {
            val py = yMin + yy + 0.5f
            for (xx in 0 until w) {
                val pxc = xMin + xx + 0.5f
                var inside = false
                var j = n - 1
                for (i in 0 until n) {
                    val xi = lassoPoints[i][0].toFloat(); val yi = lassoPoints[i][1].toFloat()
                    val xj = lassoPoints[j][0].toFloat(); val yj = lassoPoints[j][1].toFloat()
                    if (((yi > py) != (yj > py)) &&
                        (pxc < (xj - xi) * (py - yi) / ((yj - yi).takeIf { it != 0f } ?: 1e-6f) + xi)) {
                        inside = !inside
                    }
                    j = i
                }
                if (inside) mask[yy * w + xx] = true
            }
        }
        selection.clear()
        selection.active = true
        selection.x0 = xMin; selection.y0 = yMin
        selection.x1 = xMax; selection.y1 = yMax
        selection.mask = mask
        liftSelectionToFloating()
    }

    /** Toggle a single mask cell at the given canvas pixel — used by the ADD/SUB refine modes. */
    private fun refineMaskAt(px: Int, py: Int) {
        if (!selection.active) return
        val p = project ?: return
        // Snapshot current state BEFORE commit (commit calls selection.clear()
        // which wipes bbox + mask).
        val oldXMin = selection.xMin
        val oldYMin = selection.yMin
        val oldW = selection.width
        val oldH = selection.height
        val oldMask = selection.mask?.copyOf()
        val hadMask = oldMask != null

        // If currently floating, put pixels back onto the frame so we can
        // re-lift cleanly. commitFloatingSelection() runs selection.clear()
        // — that's why we snapshot first.
        if (selection.floating != null) commitFloatingSelection()

        // Compute the new bbox, expanding only when ADDING outside it.
        val isAdd = (selectionRefineMode == SelectionRefineMode.ADD)
        val newXMin = (if (isAdd) minOf(oldXMin, px) else oldXMin).coerceAtLeast(0)
        val newYMin = (if (isAdd) minOf(oldYMin, py) else oldYMin).coerceAtLeast(0)
        val newXMax = (if (isAdd) maxOf(oldXMin + oldW - 1, px) else oldXMin + oldW - 1)
            .coerceAtMost(p.width - 1)
        val newYMax = (if (isAdd) maxOf(oldYMin + oldH - 1, py) else oldYMin + oldH - 1)
            .coerceAtMost(p.height - 1)
        val nw = newXMax - newXMin + 1
        val nh = newYMax - newYMin + 1
        if (nw <= 0 || nh <= 0) return
        val newMask = BooleanArray(nw * nh)

        // Copy old mask into new bbox space. If the old selection had no mask,
        // treat the whole old bbox as selected.
        for (y in 0 until oldH) for (x in 0 until oldW) {
            val cellOld = if (hadMask) oldMask!![y * oldW + x] else true
            val sx = (oldXMin + x) - newXMin
            val sy = (oldYMin + y) - newYMin
            if (sx in 0 until nw && sy in 0 until nh) newMask[sy * nw + sx] = cellOld
        }
        // Apply ADD or SUB at (px, py)
        val lx = px - newXMin; val ly = py - newYMin
        if (lx in 0 until nw && ly in 0 until nh) newMask[ly * nw + lx] = isAdd

        // Restore selection state with the new bbox + mask
        selection.active = true
        selection.x0 = newXMin; selection.y0 = newYMin
        selection.x1 = newXMax; selection.y1 = newYMax
        selection.mask = newMask
        liftSelectionToFloating()
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
