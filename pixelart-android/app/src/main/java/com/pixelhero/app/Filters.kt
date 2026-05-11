package com.pixelhero.app

import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * Image filters operating on a single frame (or its pixels array).
 * All filters return a new IntArray of the same size; the caller decides
 * whether to apply it back to the frame.
 */
object Filters {

    enum class Filter(val displayName: String) {
        BRIGHTNESS_UP("Luminosité +"),
        BRIGHTNESS_DOWN("Luminosité −"),
        CONTRAST_UP("Contraste +"),
        CONTRAST_DOWN("Contraste −"),
        INVERT("Inverser couleurs"),
        GRAYSCALE("Niveaux de gris"),
        SATURATE("Saturer +"),
        DESATURATE("Désaturer −"),
        AUTO_OUTLINE("Auto-contour"),
        DROP_SHADOW("Ombre portée"),
        BLUR("Flou léger (anti-jaggies)"),
        POSTERIZE("Postériser (réduire couleurs)");

        override fun toString() = displayName
    }

    fun apply(pixels: IntArray, w: Int, h: Int, filter: Filter, outlineColor: Int = 0xFF000000.toInt()): IntArray {
        return when (filter) {
            Filter.BRIGHTNESS_UP -> brightness(pixels, w, h, 30)
            Filter.BRIGHTNESS_DOWN -> brightness(pixels, w, h, -30)
            Filter.CONTRAST_UP -> contrast(pixels, w, h, 1.3f)
            Filter.CONTRAST_DOWN -> contrast(pixels, w, h, 0.75f)
            Filter.INVERT -> invert(pixels, w, h)
            Filter.GRAYSCALE -> grayscale(pixels, w, h)
            Filter.SATURATE -> saturate(pixels, w, h, 1.4f)
            Filter.DESATURATE -> saturate(pixels, w, h, 0.5f)
            Filter.AUTO_OUTLINE -> autoOutline(pixels, w, h, outlineColor)
            Filter.DROP_SHADOW -> dropShadow(pixels, w, h)
            Filter.BLUR -> blur(pixels, w, h)
            Filter.POSTERIZE -> posterize(pixels, w, h, 4)
        }
    }

    private fun brightness(pixels: IntArray, w: Int, h: Int, delta: Int): IntArray {
        return IntArray(pixels.size) { i ->
            val c = pixels[i]
            if ((c ushr 24) and 0xFF < 128) return@IntArray c
            val r = (((c shr 16) and 0xFF) + delta).coerceIn(0, 255)
            val g = (((c shr 8) and 0xFF) + delta).coerceIn(0, 255)
            val b = ((c and 0xFF) + delta).coerceIn(0, 255)
            (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun contrast(pixels: IntArray, w: Int, h: Int, factor: Float): IntArray {
        return IntArray(pixels.size) { i ->
            val c = pixels[i]
            if ((c ushr 24) and 0xFF < 128) return@IntArray c
            val r = ((((c shr 16) and 0xFF) - 128) * factor + 128).toInt().coerceIn(0, 255)
            val g = ((((c shr 8) and 0xFF) - 128) * factor + 128).toInt().coerceIn(0, 255)
            val b = (((c and 0xFF) - 128) * factor + 128).toInt().coerceIn(0, 255)
            (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun invert(pixels: IntArray, w: Int, h: Int): IntArray {
        return IntArray(pixels.size) { i ->
            val c = pixels[i]
            if ((c ushr 24) and 0xFF < 128) return@IntArray c
            val r = 255 - ((c shr 16) and 0xFF)
            val g = 255 - ((c shr 8) and 0xFF)
            val b = 255 - (c and 0xFF)
            (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
    }

    private fun grayscale(pixels: IntArray, w: Int, h: Int): IntArray {
        return IntArray(pixels.size) { i ->
            val c = pixels[i]
            if ((c ushr 24) and 0xFF < 128) return@IntArray c
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val gray = (0.299f * r + 0.587f * g + 0.114f * b).toInt().coerceIn(0, 255)
            (c and 0xFF000000.toInt()) or (gray shl 16) or (gray shl 8) or gray
        }
    }

    private fun saturate(pixels: IntArray, w: Int, h: Int, factor: Float): IntArray {
        return IntArray(pixels.size) { i ->
            val c = pixels[i]
            if ((c ushr 24) and 0xFF < 128) return@IntArray c
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val gray = (0.299f * r + 0.587f * g + 0.114f * b)
            val nr = (gray + (r - gray) * factor).toInt().coerceIn(0, 255)
            val ng = (gray + (g - gray) * factor).toInt().coerceIn(0, 255)
            val nb = (gray + (b - gray) * factor).toInt().coerceIn(0, 255)
            (c and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
        }
    }

    /** Add a 1-pixel outline (default black) around every opaque region. */
    private fun autoOutline(pixels: IntArray, w: Int, h: Int, outlineColor: Int): IntArray {
        val out = pixels.copyOf()
        for (y in 0 until h) for (x in 0 until w) {
            val c = pixels[y * w + x]
            if ((c ushr 24) and 0xFF >= 128) continue  // already opaque
            // Check 4-neighbors: if any is opaque, paint outline here
            val n = (if (y > 0) pixels[(y - 1) * w + x] else 0)
            val s = (if (y < h - 1) pixels[(y + 1) * w + x] else 0)
            val e = (if (x < w - 1) pixels[y * w + (x + 1)] else 0)
            val we = (if (x > 0) pixels[y * w + (x - 1)] else 0)
            if ((n ushr 24) and 0xFF >= 128 || (s ushr 24) and 0xFF >= 128 ||
                (e ushr 24) and 0xFF >= 128 || (we ushr 24) and 0xFF >= 128) {
                out[y * w + x] = outlineColor
            }
        }
        return out
    }

    /** Drop shadow: add a translucent dark shadow offset by (+1, +1). */
    private fun dropShadow(pixels: IntArray, w: Int, h: Int): IntArray {
        val out = pixels.copyOf()
        val shadow = 0x80000000.toInt()
        for (y in 0 until h) for (x in 0 until w) {
            val src = pixels[y * w + x]
            if ((src ushr 24) and 0xFF < 128) continue
            val tx = x + 1; val ty = y + 1
            if (tx < w && ty < h) {
                val dst = out[ty * w + tx]
                if ((dst ushr 24) and 0xFF < 128) {
                    out[ty * w + tx] = shadow
                }
            }
        }
        // Now overlay the original on top
        for (i in pixels.indices) {
            val src = pixels[i]
            if ((src ushr 24) and 0xFF >= 128) out[i] = src
        }
        return out
    }

    /** Simple box blur (3x3). Preserves transparency. */
    private fun blur(pixels: IntArray, w: Int, h: Int): IntArray {
        return IntArray(pixels.size) { i ->
            val x = i % w; val y = i / w
            val c = pixels[i]
            if ((c ushr 24) and 0xFF < 128) return@IntArray c
            var rSum = 0; var gSum = 0; var bSum = 0; var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx !in 0 until w || yy !in 0 until h) continue
                val cc = pixels[yy * w + xx]
                if ((cc ushr 24) and 0xFF < 128) continue
                rSum += (cc shr 16) and 0xFF
                gSum += (cc shr 8) and 0xFF
                bSum += cc and 0xFF
                n++
            }
            if (n == 0) c else {
                val r = rSum / n; val g = gSum / n; val b = bSum / n
                (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    /** Posterize: reduce each channel to [levels] steps. */
    private fun posterize(pixels: IntArray, w: Int, h: Int, levels: Int): IntArray {
        val step = 255 / max(1, levels - 1)
        return IntArray(pixels.size) { i ->
            val c = pixels[i]
            if ((c ushr 24) and 0xFF < 128) return@IntArray c
            val r = ((c shr 16) and 0xFF) / step * step
            val g = ((c shr 8) and 0xFF) / step * step
            val b = (c and 0xFF) / step * step
            (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
    }
}
