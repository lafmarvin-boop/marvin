package com.pixelhero.app

enum class Tool {
    PENCIL, ERASER, FILL, PICKER, LINE, RECT, RECT_FILL, MOVE, SELECT
}

enum class SymmetryAxis { NONE, HORIZONTAL, VERTICAL, BOTH }

enum class BgFitMode(val label: String) {
    FIT("Adapter"),       // proportional, fits inside (default - letterboxed)
    COVER("Remplir"),     // proportional, fills canvas (may crop)
    STRETCH("Étirer")     // distorts to fit exactly
}

/** A single animation frame. Pixels stored as ARGB ints, row-major. */
class Frame(val width: Int, val height: Int) {
    val pixels: IntArray = IntArray(width * height)
    var tag: String = ""
    var delayMs: Int = 0  // 0 means use project FPS

    constructor(width: Int, height: Int, source: IntArray) : this(width, height) {
        require(source.size == width * height) { "Pixel array size mismatch" }
        source.copyInto(pixels)
    }

    fun copy(): Frame {
        val f = Frame(width, height, pixels)
        f.tag = tag
        f.delayMs = delayMs
        return f
    }

    fun get(x: Int, y: Int): Int =
        if (x in 0 until width && y in 0 until height) pixels[y * width + x] else 0

    fun set(x: Int, y: Int, color: Int) {
        if (x in 0 until width && y in 0 until height) pixels[y * width + x] = color
    }

    fun clear() = pixels.fill(0)

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
    val frames: MutableList<Frame> = mutableListOf(Frame(32, 32)),
    var currentIndex: Int = 0,
    val palette: MutableList<Int> = DEFAULT_PALETTE.toMutableList(),
    val recentColors: MutableList<Int> = mutableListOf(),
    var id: String = "p_" + System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    var symmetry: SymmetryAxis = SymmetryAxis.NONE,
    var primaryColor: Int = 0xFFFF5577.toInt(),
    var secondaryColor: Int = 0xFF000000.toInt(),
    var onionRange: Int = 1,          // number of frames before/after to show
    var pixelPerfect: Boolean = false,
    var bgFit: BgFitMode = BgFitMode.COVER
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

/** Snapshot for undo/redo. */
data class UndoSnapshot(val frameIndex: Int, val pixels: IntArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UndoSnapshot) return false
        return frameIndex == other.frameIndex && pixels.contentEquals(other.pixels)
    }
    override fun hashCode(): Int = frameIndex * 31 + pixels.contentHashCode()
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

    val xMin get() = minOf(x0, x1)
    val xMax get() = maxOf(x0, x1)
    val yMin get() = minOf(y0, y1)
    val yMax get() = maxOf(y0, y1)
    val width get() = xMax - xMin + 1
    val height get() = yMax - yMin + 1

    fun clear() {
        active = false; floating = null
        x0 = -1; y0 = -1; x1 = -1; y1 = -1
    }
}
