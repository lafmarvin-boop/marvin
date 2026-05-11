package com.pixelhero.app

import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Auto-shading: given a base color, generate a small palette ramp with shadows and highlights.
 * Uses HSL adjustments + slight hue rotation toward warmer (highlight) / cooler (shadow).
 */
object AutoShading {

    /** Returns 5 colors: deepShadow, shadow, base, highlight, deepHighlight. */
    fun ramp(base: Int): IntArray {
        val r = Color.red(base) / 255f
        val g = Color.green(base) / 255f
        val b = Color.blue(base) / 255f
        val hsl = rgbToHsl(r, g, b)
        val out = IntArray(5)
        // Shadow: -L, hue toward blue (~+15°), -saturation slightly
        out[0] = hslToColorClamped(hsl[0] + 0.04f, max(0f, hsl[1] - 0.05f), max(0.05f, hsl[2] - 0.35f))
        out[1] = hslToColorClamped(hsl[0] + 0.02f, hsl[1], max(0.08f, hsl[2] - 0.18f))
        out[2] = base
        // Highlight: +L, hue toward yellow (~-15°), +saturation slightly
        out[3] = hslToColorClamped(hsl[0] - 0.02f, hsl[1], min(0.95f, hsl[2] + 0.18f))
        out[4] = hslToColorClamped(hsl[0] - 0.04f, max(0f, hsl[1] - 0.1f), min(0.98f, hsl[2] + 0.32f))
        return out
    }

    private fun hslToColorClamped(h: Float, s: Float, l: Float): Int {
        val hh = ((h % 1f) + 1f) % 1f
        val rgb = hslToRgb(hh, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
        return Color.rgb((rgb[0] * 255).toInt(), (rgb[1] * 255).toInt(), (rgb[2] * 255).toInt()) or 0xFF000000.toInt()
    }

    private fun rgbToHsl(r: Float, g: Float, b: Float): FloatArray {
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        var h = 0f; val l = (maxC + minC) / 2f
        val s: Float
        if (maxC == minC) { h = 0f; s = 0f }
        else {
            val d = maxC - minC
            s = if (l > 0.5f) d / (2f - maxC - minC) else d / (maxC + minC)
            h = when (maxC) {
                r -> (g - b) / d + (if (g < b) 6f else 0f)
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            } / 6f
        }
        return floatArrayOf(h, s, l)
    }

    private fun hslToRgb(h: Float, s: Float, l: Float): FloatArray {
        if (s == 0f) return floatArrayOf(l, l, l)
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        return floatArrayOf(hue2rgb(p, q, h + 1f/3f), hue2rgb(p, q, h), hue2rgb(p, q, h - 1f/3f))
    }

    private fun hue2rgb(p: Float, q: Float, tt: Float): Float {
        var t = tt
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        if (t < 1f/6f) return p + (q - p) * 6f * t
        if (t < 1f/2f) return q
        if (t < 2f/3f) return p + (q - p) * (2f/3f - t) * 6f
        return p
    }
}
