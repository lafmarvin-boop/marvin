package com.pixelhero.app

import android.graphics.Color

/**
 * Generate intermediate frames between two key frames by pixel-level blending.
 * This is NOT motion interpolation (it doesn't move pixels) - it's a colour cross-fade
 * that produces "ghost" frames the artist refines manually.
 *
 * For real motion tweening between two poses, draw the start and end frames,
 * generate N intermediates, then redraw the moving parts manually using the
 * intermediates as positional guides.
 */
object Tweening {

    /** Returns [steps] new frames, in order, that fade from [a] to [b]. */
    fun generate(a: Frame, b: Frame, steps: Int, curve: Easing.Curve = Easing.Curve.LINEAR): List<Frame> {
        require(a.width == b.width && a.height == b.height) { "Frames must be same size" }
        val w = a.width; val h = a.height
        val aPx = if (a.layers.size > 1) a.composited() else a.pixels
        val bPx = if (b.layers.size > 1) b.composited() else b.pixels
        return (1..steps).map { i ->
            val rawT = i.toFloat() / (steps + 1)
            val t = Easing.apply(curve, rawT)
            val out = Frame(w, h)
            out.tag = "tween"
            for (k in aPx.indices) {
                val ac = aPx[k]
                val bc = bPx[k]
                val aa = (ac ushr 24) and 0xFF
                val ba = (bc ushr 24) and 0xFF
                val blended = lerpColor(ac, bc, t)
                // If one side is transparent, fade in/out instead of blending
                val outA = (aa * (1 - t) + ba * t).toInt().coerceIn(0, 255)
                out.pixels[k] = (outA shl 24) or (blended and 0xFFFFFF)
            }
            out
        }
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val ar = (a shr 16) and 0xFF
        val ag = (a shr 8) and 0xFF
        val ab = a and 0xFF
        val br = (b shr 16) and 0xFF
        val bg = (b shr 8) and 0xFF
        val bb = b and 0xFF
        val r = (ar + (br - ar) * t).toInt().coerceIn(0, 255)
        val g = (ag + (bg - ag) * t).toInt().coerceIn(0, 255)
        val bl = (ab + (bb - ab) * t).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or bl
    }
}
