package com.pixelhero.app

enum class Tool {
    PENCIL, ERASER, FILL, UNFILL, PICKER, LINE, RECT, RECT_FILL, MOVE, SELECT, WAND, LASSO
}

enum class SymmetryAxis { NONE, HORIZONTAL, VERTICAL, BOTH, ROTATE_4 }

enum class BgFitMode(val label: String) {
    FIT("Adapter"),       // proportional, fits inside (default - letterboxed)
    COVER("Remplir"),     // proportional, fills canvas (may crop)
    STRETCH("Étirer")     // distorts to fit exactly
}

enum class PlayMode(val label: String) {
    LOOP("Boucle"),        // 1, 2, 3, 1, 2, 3...
    PING_PONG("Ping-pong"),// 1, 2, 3, 2, 1, 2, 3...
    REVERSE("Inverse"),    // 3, 2, 1, 3, 2, 1...
    ONCE("Une fois")       // 1, 2, 3 stop
}

/** A single drawing layer within a frame. */
class Layer(val width: Int, val height: Int, var name: String = "Couche", var visible: Boolean = true, var opacity: Float = 1f) {
    val pixels: IntArray = IntArray(width * height)
    /** Dirty flag for downstream caches (composite, thumbnail). */
    @Transient var dirty: Boolean = true
    /**
     * Optional group label. Layers that share the same non-null groupName are
     * rendered together in the layers strip (with a group header) and persist
     * across frame copies. Toggling the group's eye toggles all members.
     */
    var groupName: String? = null
    constructor(width: Int, height: Int, name: String, source: IntArray) : this(width, height, name) {
        require(source.size == width * height) { "Layer size mismatch" }
        source.copyInto(pixels)
    }
    fun copy(): Layer {
        val l = Layer(width, height, name, pixels.copyOf())
        l.visible = visible; l.opacity = opacity; l.groupName = groupName
        return l
    }
}

/** A single animation frame, composed of one or more stacked Layers. */
class Frame(val width: Int, val height: Int) {
    val layers: MutableList<Layer> = mutableListOf(Layer(width, height, "Couche 1"))
    var activeLayer: Int = 0
        set(value) { field = value.coerceIn(0, layers.size - 1); invalidateComposite() }
    var tag: String = ""
    var delayMs: Int = 0  // 0 means use project FPS

    /**
     * Cached result of [composited]. Invalidated whenever a known pixel
     * write happens via [set] / [clear] / [pixels] getter, or a layer is
     * added/removed. External code that mutates layer pixels directly must
     * call [invalidateComposite] for the cache to stay correct.
     *
     * The cache is the main perf win for animation Play/miniPreview loops:
     * frames that aren't being drawn return their stored composite array
     * instead of re-running the O(layers × w × h) alpha blend on every tick.
     */
    @Transient private var compositedCache: IntArray? = null

    constructor(width: Int, height: Int, source: IntArray) : this(width, height) {
        require(source.size == width * height) { "Pixel array size mismatch" }
        source.copyInto(layers[0].pixels)
    }

    /** Active layer's pixel buffer (for backward-compat: most code paints here). */
    val pixels: IntArray get() {
        // .pixels readers typically modify in-place right after, so we
        // invalidate the cache defensively.
        invalidateComposite()
        return layers[activeLayer.coerceIn(0, layers.size - 1)].pixels
    }

    fun invalidateComposite() { compositedCache = null }

    /** Composited view of all visible layers, bottom-to-top, with alpha blending. */
    fun composited(): IntArray {
        compositedCache?.let { return it }
        val out = if (layers.size == 1 && layers[0].visible) {
            layers[0].pixels.copyOf()
        } else {
            val r = IntArray(width * height)
            for (layer in layers) {
                if (!layer.visible) continue
                val layerOpacity = (layer.opacity * 255).toInt().coerceIn(0, 255)
                for (i in r.indices) {
                    val src = layer.pixels[i]
                    val srcA = ((src ushr 24) and 0xFF) * layerOpacity / 255
                    if (srcA == 0) continue
                    val dst = r[i]
                    val dstA = (dst ushr 24) and 0xFF
                    if (dstA == 0 || srcA == 255) {
                        r[i] = (srcA shl 24) or (src and 0xFFFFFF)
                    } else {
                        val sa = srcA / 255f
                        val da = dstA / 255f * (1f - sa)
                        val outA = (sa + da).coerceIn(0f, 1f)
                        val sr = ((src shr 16) and 0xFF) * sa
                        val sg = ((src shr 8) and 0xFF) * sa
                        val sb = (src and 0xFF) * sa
                        val dr = ((dst shr 16) and 0xFF) * da
                        val dg = ((dst shr 8) and 0xFF) * da
                        val db = (dst and 0xFF) * da
                        val rr = ((sr + dr) / outA).toInt().coerceIn(0, 255)
                        val gg = ((sg + dg) / outA).toInt().coerceIn(0, 255)
                        val bb = ((sb + db) / outA).toInt().coerceIn(0, 255)
                        r[i] = ((outA * 255).toInt() shl 24) or (rr shl 16) or (gg shl 8) or bb
                    }
                }
            }
            r
        }
        compositedCache = out
        return out
    }

    fun copy(): Frame {
        val f = Frame(width, height)
        f.layers.clear()
        layers.forEach { f.layers.add(it.copy()) }
        f.activeLayer = activeLayer
        f.tag = tag
        f.delayMs = delayMs
        return f
    }

    fun get(x: Int, y: Int): Int =
        if (x in 0 until width && y in 0 until height) pixels[y * width + x] else 0

    fun set(x: Int, y: Int, color: Int) {
        if (x in 0 until width && y in 0 until height) {
            pixels[y * width + x] = color
            invalidateComposite()
        }
    }

    fun clear() { pixels.fill(0); invalidateComposite() }

    fun addLayer(name: String = "Couche ${layers.size + 1}"): Layer {
        val l = Layer(width, height, name)
        layers.add(l)
        activeLayer = layers.size - 1
        invalidateComposite()
        return l
    }

    fun removeLayer(idx: Int): Boolean {
        if (layers.size <= 1) return false
        if (idx !in layers.indices) return false
        layers.removeAt(idx)
        activeLayer = activeLayer.coerceAtMost(layers.size - 1)
        invalidateComposite()
        return true
    }

    fun flipHorizontal(): Frame {
        val out = Frame(width, height)
        out.tag = tag; out.delayMs = delayMs
        for (y in 0 until height) for (x in 0 until width)
            out.set(width - 1 - x, y, get(x, y))
        return out
    }

    fun flipVertical(): Frame {
        val out = Frame(width, height)
        out.tag = tag; out.delayMs = delayMs
        for (y in 0 until height) for (x in 0 until width)
            out.set(x, height - 1 - y, get(x, y))
        return out
    }

    fun shifted(dx: Int, dy: Int, wrap: Boolean = false): Frame {
        val out = Frame(width, height)
        out.tag = tag; out.delayMs = delayMs
        for (y in 0 until height) for (x in 0 until width) {
            val nx = if (wrap) (x + dx).mod(width) else x + dx
            val ny = if (wrap) (y + dy).mod(height) else y + dy
            if (nx in 0 until width && ny in 0 until height) out.set(nx, ny, get(x, y))
        }
        return out
    }

    /** Replace one color globally. */
    fun replaceColor(from: Int, to: Int): Int {
        var n = 0
        for (i in pixels.indices) {
            if (pixels[i] == from) { pixels[i] = to; n++ }
        }
        return n
    }
}

class Project(
    var name: String = "Sans titre",
    var width: Int = 32,
    var height: Int = 32,
    var fps: Int = 8,
    val frames: MutableList<Frame> = mutableListOf(Frame(64, 64)),
    var currentIndex: Int = 0,
    val palette: MutableList<Int> = DEFAULT_PALETTE.toMutableList(),
    val recentColors: MutableList<Int> = mutableListOf(),
    var id: String = "p_" + System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var symmetry: SymmetryAxis = SymmetryAxis.NONE,
    var primaryColor: Int = 0xFFFF5577.toInt(),
    var secondaryColor: Int = 0xFF000000.toInt(),
    var onionRange: Int = 2,          // number of frames before/after to show
    var onionTrailOnly: Boolean = false,  // when true, show only PAST frames (motion trail)
    var pixelPerfect: Boolean = false,
    var bgFit: BgFitMode = BgFitMode.FIT,
    var brushSize: Int = 1,
    var ditherPattern: Int = 0,  // 0=none, 1=checker, 2=v.lines, 3=h.lines, 4=sparse, 5=primary+secondary mix, 6=custom
    var customDither: Array<BooleanArray> = Array(4) { BooleanArray(4) },  // 4x4 pattern for ditherPattern=6
    var playMode: PlayMode = PlayMode.LOOP,
    val lockedColors: MutableSet<Int> = mutableSetOf(),
    var pressureSensitive: Boolean = true,
    var onionColorPrev: Int = 0xFF00AAFF.toInt(),
    var onionColorNext: Int = 0xFFFF4477.toInt(),
    /** Shared static background drawn under every frame's composite. Optional. */
    var globalBackground: Layer? = null
) {
    val currentFrame: Frame get() = frames[currentIndex]

    fun setColor(color: Int) {
        recentColors.remove(color)
        recentColors.add(0, color)
        while (recentColors.size > 16) recentColors.removeAt(recentColors.size - 1)
    }

    fun swapColors() {
        val tmp = primaryColor
        primaryColor = secondaryColor
        secondaryColor = tmp
    }

    fun delayForFrame(idx: Int): Int {
        val f = frames.getOrNull(idx) ?: return (1000 / fps.coerceAtLeast(1))
        return if (f.delayMs > 0) f.delayMs else (1000 / fps.coerceAtLeast(1))
    }

    companion object {
        val DEFAULT_PALETTE = listOf(
            0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF7F7F7F.toInt(), 0xFFBCBCBC.toInt(),
            0xFFFF0000.toInt(), 0xFFFF7F00.toInt(), 0xFFFFFF00.toInt(), 0xFF7FFF00.toInt(),
            0xFF00FF00.toInt(), 0xFF00FF7F.toInt(), 0xFF00FFFF.toInt(), 0xFF007FFF.toInt(),
            0xFF0000FF.toInt(), 0xFF7F00FF.toInt(), 0xFFFF00FF.toInt(), 0xFFFF007F.toInt(),
            0xFF8B4513.toInt(), 0xFFA0522D.toInt(), 0xFFCD853F.toInt(), 0xFFDEB887.toInt(),
            0xFFFF5577.toInt(), 0xFF5566FF.toInt(), 0xFF22CC88.toInt(), 0xFFFFAA33.toInt()
        )
    }
}

/**
 * Snapshot for undo/redo. Two flavours so multi-layer / multi-frame operations
 * can restore properly without paying the snapshot cost on every pencil stroke:
 *  - single-layer snapshots store one IntArray for the active layer of one frame
 *  - full-frame snapshots store every layer of one frame
 *  - all-frames snapshots store every layer of every frame (filters, etc.)
 */
sealed class UndoSnapshot {
    data class SingleLayer(
        val frameIndex: Int,
        val layerIndex: Int,
        val pixels: IntArray
    ) : UndoSnapshot()

    data class FullFrame(
        val frameIndex: Int,
        val layers: List<IntArray>
    ) : UndoSnapshot()

    data class AllFrames(
        val frames: List<List<IntArray>>
    ) : UndoSnapshot()
}

/** Rectangular selection with optional floating clipboard content. */
class Selection {
    var x0: Int = -1; var y0: Int = -1
    var x1: Int = -1; var y1: Int = -1
    var active: Boolean = false
    /** Floating pixels when moving/pasting (non-null while floating). */
    var floating: IntArray? = null
    var floatW: Int = 0
    var floatH: Int = 0
    var floatX: Int = 0
    var floatY: Int = 0
    /**
     * Per-pixel mask describing exactly which cells inside the bbox are
     * part of the selection. When null, the whole bbox is selected.
     * Indexed by (y - yMin) * width + (x - xMin). Used by lasso and the
     * refine-pixel modes to mark non-rectangular shapes.
     */
    var mask: BooleanArray? = null

    val xMin get() = minOf(x0, x1)
    val xMax get() = maxOf(x0, x1)
    val yMin get() = minOf(y0, y1)
    val yMax get() = maxOf(y0, y1)
    val width get() = xMax - xMin + 1
    val height get() = yMax - yMin + 1

    /** Returns true if (x,y) is part of the selection (bbox + mask check). */
    fun contains(x: Int, y: Int): Boolean {
        if (!active) return false
        if (x !in xMin..xMax || y !in yMin..yMax) return false
        val m = mask ?: return true
        val lx = x - xMin; val ly = y - yMin
        val w = width
        val idx = ly * w + lx
        return idx in m.indices && m[idx]
    }

    fun clear() {
        active = false; floating = null
        x0 = -1; y0 = -1; x1 = -1; y1 = -1
        mask = null
    }
}
