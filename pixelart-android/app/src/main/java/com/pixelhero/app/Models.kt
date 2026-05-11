package com.pixelhero.app

enum class Tool {
    PENCIL, ERASER, FILL, PICKER, LINE, RECT, RECT_FILL, MOVE
}

/** A single animation frame. Pixels stored as ARGB ints, row-major. */
class Frame(val width: Int, val height: Int) {
    val pixels: IntArray = IntArray(width * height)

    constructor(width: Int, height: Int, source: IntArray) : this(width, height) {
        require(source.size == width * height) { "Pixel array size mismatch" }
        source.copyInto(pixels)
    }

    fun copy(): Frame = Frame(width, height, pixels)

    fun get(x: Int, y: Int): Int =
        if (x in 0 until width && y in 0 until height) pixels[y * width + x] else 0

    fun set(x: Int, y: Int, color: Int) {
        if (x in 0 until width && y in 0 until height) pixels[y * width + x] = color
    }

    fun clear() = pixels.fill(0)
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
    var updatedAt: Long = System.currentTimeMillis()
) {
    val currentFrame: Frame get() = frames[currentIndex]

    fun setColor(color: Int) {
        recentColors.remove(color)
        recentColors.add(0, color)
        while (recentColors.size > 16) recentColors.removeAt(recentColors.size - 1)
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
