package com.pixelhero.app

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/**
 * Converts an arbitrary bitmap into a pixel-art representation that fits the
 * project's resolution: downscale via area-averaging, then quantize to a small
 * palette (with optional Floyd-Steinberg dithering).
 *
 * The result can either replace the current frame or be returned as a palette
 * suggestion for the user to keep building manually.
 */
object ImageToPixelArt {

    /**
     * Pixelize [bitmap] to (w, h) and quantize to at most [paletteSize] colors.
     * @param fit Fit mode (matches BgFitMode used for the reference image)
     * @param dither If true, Floyd-Steinberg dithering is applied
     */
    fun pixelize(
        bitmap: Bitmap,
        w: Int, h: Int,
        paletteSize: Int = 16,
        fit: BgFitMode = BgFitMode.COVER,
        dither: Boolean = true
    ): IntArray {
        val downscaled = downscale(bitmap, w, h, fit)
        val palette = extractPalette(downscaled, paletteSize)
        if (palette.isEmpty()) return downscaled
        if (dither) applyFloydSteinberg(downscaled, w, h, palette)
        else for (i in downscaled.indices) downscaled[i] = nearestColor(downscaled[i], palette)
        return downscaled
    }

    /** Downscale [bitmap] to (w, h) using area-averaging. */
    fun downscale(bitmap: Bitmap, w: Int, h: Int, fit: BgFitMode = BgFitMode.COVER): IntArray {
        val srcW = bitmap.width
        val srcH = bitmap.height
        val src = IntArray(srcW * srcH)
        bitmap.getPixels(src, 0, srcW, 0, 0, srcW, srcH)

        // Compute placement rect (in destination coordinates) so the source maps to it.
        val scaleX: Float; val scaleY: Float
        val dx0: Float; val dy0: Float
        when (fit) {
            BgFitMode.STRETCH -> {
                scaleX = w.toFloat() / srcW
                scaleY = h.toFloat() / srcH
                dx0 = 0f; dy0 = 0f
            }
            BgFitMode.COVER -> {
                val s = max(w.toFloat() / srcW, h.toFloat() / srcH)
                scaleX = s; scaleY = s
                dx0 = (w - srcW * s) / 2f
                dy0 = (h - srcH * s) / 2f
            }
            BgFitMode.FIT -> {
                val s = min(w.toFloat() / srcW, h.toFloat() / srcH)
                scaleX = s; scaleY = s
                dx0 = (w - srcW * s) / 2f
                dy0 = (h - srcH * s) / 2f
            }
        }

        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                // Map (x, y) back to source coordinates (using inverse transform)
                val sxFloat = (x - dx0) / scaleX
                val syFloat = (y - dy0) / scaleY
                val sx0 = sxFloat.toInt()
                val sy0 = syFloat.toInt()
                val sx1 = ((x - dx0 + 1) / scaleX).toInt()
                val sy1 = ((y - dy0 + 1) / scaleY).toInt()
                if (sx0 < 0 || sy0 < 0 || sx0 >= srcW || sy0 >= srcH) {
                    out[y * w + x] = 0  // transparent for areas outside source (fit mode)
                    continue
                }
                val xa = sx0.coerceIn(0, srcW - 1)
                val xb = sx1.coerceIn(0, srcW - 1).coerceAtLeast(xa)
                val ya = sy0.coerceIn(0, srcH - 1)
                val yb = sy1.coerceIn(0, srcH - 1).coerceAtLeast(ya)
                var rSum = 0; var gSum = 0; var bSum = 0; var aSum = 0; var n = 0
                for (sy in ya..yb) for (sx in xa..xb) {
                    val c = src[sy * srcW + sx]
                    aSum += (c ushr 24) and 0xFF
                    rSum += (c shr 16) and 0xFF
                    gSum += (c shr 8) and 0xFF
                    bSum += c and 0xFF
                    n++
                }
                if (n > 0) {
                    val a = aSum / n
                    val r = rSum / n; val g = gSum / n; val b = bSum / n
                    out[y * w + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        return out
    }

    /**
     * Extract the [count] most prominent colors from [pixels] using a binned
     * histogram with weighted averages.
     */
    fun extractPalette(pixels: IntArray, count: Int): IntArray {
        if (count < 1) return IntArray(0)
        // Bin to 5 bits per channel (32 levels each)
        data class Bucket(var n: Int = 0, var rSum: Int = 0, var gSum: Int = 0, var bSum: Int = 0)
        val bins = HashMap<Int, Bucket>(1024)
        for (c in pixels) {
            if ((c ushr 24) and 0xFF < 128) continue
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val key = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
            val bucket = bins.getOrPut(key) { Bucket() }
            bucket.n++; bucket.rSum += r; bucket.gSum += g; bucket.bSum += b
        }
        if (bins.isEmpty()) return IntArray(0)
        return bins.values.sortedByDescending { it.n }.take(count).map {
            val r = it.rSum / it.n; val g = it.gSum / it.n; val b = it.bSum / it.n
            0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
        }.toIntArray()
    }

    /** Find the palette color nearest to [c] (Euclidean RGB distance). */
    fun nearestColor(c: Int, palette: IntArray): Int {
        if ((c ushr 24) and 0xFF < 128) return 0 // preserve transparency
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        var best = palette[0]; var bestDist = Int.MAX_VALUE
        for (pc in palette) {
            val pr = (pc shr 16) and 0xFF
            val pg = (pc shr 8) and 0xFF
            val pb = pc and 0xFF
            val d = (r - pr) * (r - pr) + (g - pg) * (g - pg) + (b - pb) * (b - pb)
            if (d < bestDist) { bestDist = d; best = pc }
        }
        return best
    }

    /** Floyd-Steinberg dithering: distributes quantization error to neighbors. */
    fun applyFloydSteinberg(pixels: IntArray, w: Int, h: Int, palette: IntArray) {
        for (y in 0 until h) {
            for (x in 0 until w) {
                val old = pixels[y * w + x]
                if ((old ushr 24) and 0xFF < 128) continue
                val new = nearestColor(old, palette)
                pixels[y * w + x] = new
                val er = ((old shr 16) and 0xFF) - ((new shr 16) and 0xFF)
                val eg = ((old shr 8) and 0xFF) - ((new shr 8) and 0xFF)
                val eb = (old and 0xFF) - (new and 0xFF)
                addError(pixels, w, h, x + 1, y, er, eg, eb, 7f / 16f)
                addError(pixels, w, h, x - 1, y + 1, er, eg, eb, 3f / 16f)
                addError(pixels, w, h, x, y + 1, er, eg, eb, 5f / 16f)
                addError(pixels, w, h, x + 1, y + 1, er, eg, eb, 1f / 16f)
            }
        }
    }

    private fun addError(pixels: IntArray, w: Int, h: Int, x: Int, y: Int,
                         er: Int, eg: Int, eb: Int, factor: Float) {
        if (x !in 0 until w || y !in 0 until h) return
        val c = pixels[y * w + x]
        if ((c ushr 24) and 0xFF < 128) return
        val r = (((c shr 16) and 0xFF) + (er * factor).toInt()).coerceIn(0, 255)
        val g = (((c shr 8) and 0xFF) + (eg * factor).toInt()).coerceIn(0, 255)
        val b = ((c and 0xFF) + (eb * factor).toInt()).coerceIn(0, 255)
        pixels[y * w + x] = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }
}
