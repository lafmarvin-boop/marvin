package com.pixelhero.app

/** Single-frame transformations applied in place. */
object FrameTransforms {

    fun flipHorizontal(frame: Frame): Frame = frame.flipHorizontal()
    fun flipVertical(frame: Frame): Frame = frame.flipVertical()

    /** Shift the frame by (dx, dy). Out-of-bounds becomes transparent. */
    fun shift(frame: Frame, dx: Int, dy: Int): Frame = frame.shifted(dx, dy, wrap = false)

    /** Rotate 90° clockwise. Result kept on the same canvas (center-aligned crop). */
    fun rotate90(frame: Frame): Frame {
        val w = frame.width; val h = frame.height
        val out = Frame(w, h)
        // For a square sprite this is a clean rotation. For non-square, we map src(x,y) -> dst(h-1-y, x)
        // and clip to the existing dimensions, centering around middle.
        val srcW = h; val srcH = w // logical
        val offX = (w - srcW) / 2
        val offY = (h - srcH) / 2
        for (y in 0 until h) for (x in 0 until w) {
            val lx = x - offX; val ly = y - offY
            if (lx in 0 until srcW && ly in 0 until srcH) {
                // Map back to source
                val sx = ly
                val sy = srcW - 1 - lx
                if (sx in 0 until frame.width && sy in 0 until frame.height) {
                    out.set(x, y, frame.get(sx, sy))
                }
            }
        }
        return out
    }

    /** Apply a single transform to a target frame in a project (in place) and return undo data. */
    fun applyInPlace(frame: Frame, newPixels: IntArray) {
        newPixels.copyInto(frame.pixels)
    }
}
