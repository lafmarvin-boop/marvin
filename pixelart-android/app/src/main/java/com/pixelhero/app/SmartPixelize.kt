package com.pixelhero.app

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * High-quality pixelization pipeline tuned for game-ready pixel art output.
 * Supersedes the basic histogram-based pixelize() in ImageToPixelArt.
 *
 * Pipeline:
 *   1. Bilinear downscale (built-in)
 *   2. Optional pre-blur to clean noise
 *   3. Optional saturation boost
 *   4. Median-cut palette extraction (proper color quantization)
 *   5. Optional Bayer or Floyd-Steinberg dithering
 *   6. Optional auto-outline (Sobel edges in dark color)
 */
object SmartPixelize {

    enum class Style(val displayName: String) {
        STANDARD("Standard (équilibré)"),
        VIBRANT("Vibrant (saturé + net)"),
        CARTOON("Cartoon (lisse + contour)"),
        RETRO("Rétro (16 couleurs + Bayer)"),
        SHARP("Net (sans tramage)")
    }

    data class Options(
        val paletteSize: Int = 16,
        val preBlur: Boolean = true,
        val saturationBoost: Float = 1.0f,
        val dither: DitherMode = DitherMode.FLOYD,
        val outline: Boolean = false,
        val outlineThreshold: Int = 60
    )

    enum class DitherMode { NONE, FLOYD, BAYER }

    fun styleOptions(style: Style): Options = when (style) {
        Style.STANDARD -> Options()
        Style.VIBRANT -> Options(paletteSize = 20, saturationBoost = 1.3f, dither = DitherMode.FLOYD)
        Style.CARTOON -> Options(paletteSize = 12, preBlur = true, saturationBoost = 1.15f, dither = DitherMode.NONE, outline = true)
        Style.RETRO -> Options(paletteSize = 16, dither = DitherMode.BAYER)
        Style.SHARP -> Options(paletteSize = 24, preBlur = false, dither = DitherMode.NONE)
    }

    /** Main entry point. */
    fun pixelize(
        bitmap: Bitmap,
        w: Int, h: Int,
        fit: BgFitMode,
        style: Style
    ): Pair<IntArray, IntArray> {
        val options = styleOptions(style)
        return pixelizeWith(bitmap, w, h, fit, options)
    }

    fun pixelizeWith(
        bitmap: Bitmap,
        w: Int, h: Int,
        fit: BgFitMode,
        opt: Options
    ): Pair<IntArray, IntArray> {
        // 1. Downscale (uses bilinear via Bitmap.createScaledBitmap for higher quality
        //    than pure area-averaging on certain content)
        val pixels = bilinearDownscale(bitmap, w, h, fit)

        // 2. Pre-blur (3x3 gaussian-ish kernel) — softens noise
        if (opt.preBlur) gaussianBlur(pixels, w, h)

        // 3. Saturation boost
        if (opt.saturationBoost != 1.0f) saturate(pixels, w, h, opt.saturationBoost)

        // 4. Palette via median cut
        val palette = medianCut(pixels, opt.paletteSize)

        // 5. Apply palette with chosen dither
        when (opt.dither) {
            DitherMode.NONE -> applyNearest(pixels, w, h, palette)
            DitherMode.FLOYD -> ImageToPixelArt.applyFloydSteinberg(pixels, w, h, palette)
            DitherMode.BAYER -> applyBayer(pixels, w, h, palette)
        }

        // 6. Optional outline
        if (opt.outline) addOutline(pixels, w, h, opt.outlineThreshold)

        return pixels to palette
    }

    // ---- Downscale ----
    private fun bilinearDownscale(src: Bitmap, w: Int, h: Int, fit: BgFitMode): IntArray {
        // Use Bitmap.createScaledBitmap which uses bilinear filtering for the destination
        // size, then extract pixels.
        val srcW = src.width; val srcH = src.height
        val scaleX: Float; val scaleY: Float
        val dx0: Int; val dy0: Int; val targetW: Int; val targetH: Int
        when (fit) {
            BgFitMode.STRETCH -> {
                scaleX = w.toFloat() / srcW; scaleY = h.toFloat() / srcH
                targetW = w; targetH = h; dx0 = 0; dy0 = 0
            }
            BgFitMode.COVER -> {
                val s = max(w.toFloat() / srcW, h.toFloat() / srcH)
                scaleX = s; scaleY = s
                targetW = (srcW * s).toInt(); targetH = (srcH * s).toInt()
                dx0 = ((w - targetW) / 2)
                dy0 = ((h - targetH) / 2)
            }
            BgFitMode.FIT -> {
                val s = min(w.toFloat() / srcW, h.toFloat() / srcH)
                scaleX = s; scaleY = s
                targetW = (srcW * s).toInt(); targetH = (srcH * s).toInt()
                dx0 = ((w - targetW) / 2)
                dy0 = ((h - targetH) / 2)
            }
        }
        val tw = targetW.coerceAtLeast(1)
        val th = targetH.coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, tw, th, true)
        val scaledPixels = IntArray(tw * th)
        scaled.getPixels(scaledPixels, 0, tw, 0, 0, tw, th)
        if (scaled !== src) scaled.recycle()

        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val sx = x - dx0; val sy = y - dy0
                if (sx in 0 until tw && sy in 0 until th) {
                    out[y * w + x] = scaledPixels[sy * tw + sx]
                }
            }
        }
        return out
    }

    // ---- Pre-blur (3x3 simple gaussian-ish kernel) ----
    private fun gaussianBlur(pixels: IntArray, w: Int, h: Int) {
        if (w < 3 || h < 3) return
        val copy = pixels.copyOf()
        // Kernel: 1 2 1 / 2 4 2 / 1 2 1  (sum 16)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                var r = 0; var g = 0; var b = 0; var a = 0
                for (ky in -1..1) {
                    for (kx in -1..1) {
                        val weight = (2 - abs(kx)) * (2 - abs(ky))
                        val c = copy[(y + ky) * w + (x + kx)]
                        r += ((c shr 16) and 0xFF) * weight
                        g += ((c shr 8) and 0xFF) * weight
                        b += (c and 0xFF) * weight
                        a += ((c ushr 24) and 0xFF) * weight
                    }
                }
                pixels[y * w + x] = ((a / 16) shl 24) or
                    ((r / 16) shl 16) or ((g / 16) shl 8) or (b / 16)
            }
        }
    }

    // ---- Saturation boost ----
    private fun saturate(pixels: IntArray, w: Int, h: Int, factor: Float) {
        for (i in pixels.indices) {
            val c = pixels[i]
            if ((c ushr 24) and 0xFF < 128) continue
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val gray = (0.299f * r + 0.587f * g + 0.114f * b)
            val nr = (gray + (r - gray) * factor).toInt().coerceIn(0, 255)
            val ng = (gray + (g - gray) * factor).toInt().coerceIn(0, 255)
            val nb = (gray + (b - gray) * factor).toInt().coerceIn(0, 255)
            pixels[i] = (c and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
        }
    }

    // ---- Median Cut palette extraction ----
    private data class ColorBox(val pixels: MutableList<Int>) {
        var rMin = 255; var rMax = 0; var gMin = 255; var gMax = 0; var bMin = 255; var bMax = 0
        init { computeBounds() }
        private fun computeBounds() {
            rMin = 255; rMax = 0; gMin = 255; gMax = 0; bMin = 255; bMax = 0
            for (c in pixels) {
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r < rMin) rMin = r; if (r > rMax) rMax = r
                if (g < gMin) gMin = g; if (g > gMax) gMax = g
                if (b < bMin) bMin = b; if (b > bMax) bMax = b
            }
        }
        val rRange get() = rMax - rMin
        val gRange get() = gMax - gMin
        val bRange get() = bMax - bMin
        val volume get() = rRange.coerceAtLeast(1) * gRange.coerceAtLeast(1) * bRange.coerceAtLeast(1)
        fun longestAxis(): Int {
            return when (max(rRange, max(gRange, bRange))) {
                rRange -> 0
                gRange -> 1
                else -> 2
            }
        }
        fun split(): Pair<ColorBox, ColorBox> {
            val axis = longestAxis()
            pixels.sortBy { c ->
                when (axis) {
                    0 -> (c shr 16) and 0xFF
                    1 -> (c shr 8) and 0xFF
                    else -> c and 0xFF
                }
            }
            val mid = pixels.size / 2
            return ColorBox(pixels.subList(0, mid).toMutableList()) to
                   ColorBox(pixels.subList(mid, pixels.size).toMutableList())
        }
        fun average(): Int {
            var r = 0L; var g = 0L; var b = 0L
            for (c in pixels) {
                r += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
            }
            val n = pixels.size.coerceAtLeast(1)
            return 0xFF000000.toInt() or
                ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
        }
    }

    fun medianCut(pixels: IntArray, count: Int): IntArray {
        // Collect opaque pixels (sampled if too many)
        val sample = mutableListOf<Int>()
        val step = (pixels.size / 20000).coerceAtLeast(1)
        for (i in pixels.indices step step) {
            val c = pixels[i]
            if ((c ushr 24) and 0xFF >= 128) sample.add(c)
        }
        if (sample.isEmpty()) return intArrayOf()
        // Start with one box containing everything
        val boxes = mutableListOf(ColorBox(sample))
        while (boxes.size < count) {
            // Split the box with the largest volume
            val maxIdx = boxes.indices.maxByOrNull { boxes[it].volume } ?: break
            val box = boxes[maxIdx]
            if (box.pixels.size < 2 || box.volume <= 1) break
            val (a, b) = box.split()
            boxes.removeAt(maxIdx)
            boxes.add(a); boxes.add(b)
        }
        return boxes.map { it.average() }.toIntArray()
    }

    // ---- Apply palette: nearest-color, no dither ----
    private fun applyNearest(pixels: IntArray, w: Int, h: Int, palette: IntArray) {
        if (palette.isEmpty()) return
        for (i in pixels.indices) {
            pixels[i] = ImageToPixelArt.nearestColor(pixels[i], palette)
        }
    }

    // ---- Bayer 4x4 ordered dithering ----
    private val BAYER_4X4 = arrayOf(
        intArrayOf(0, 8, 2, 10),
        intArrayOf(12, 4, 14, 6),
        intArrayOf(3, 11, 1, 9),
        intArrayOf(15, 7, 13, 5)
    )

    private fun applyBayer(pixels: IntArray, w: Int, h: Int, palette: IntArray) {
        if (palette.isEmpty()) return
        // Apply Bayer threshold to each channel before quantizing
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = pixels[y * w + x]
                if ((c ushr 24) and 0xFF < 128) continue
                val threshold = (BAYER_4X4[y % 4][x % 4] - 8) * 4 // -32..28 range
                val r = (((c shr 16) and 0xFF) + threshold).coerceIn(0, 255)
                val g = (((c shr 8) and 0xFF) + threshold).coerceIn(0, 255)
                val b = ((c and 0xFF) + threshold).coerceIn(0, 255)
                val biased = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
                pixels[y * w + x] = ImageToPixelArt.nearestColor(biased, palette)
            }
        }
    }

    // ---- Outline pass: Sobel edge detection, paint dark on strong edges ----
    private fun addOutline(pixels: IntArray, w: Int, h: Int, threshold: Int) {
        if (w < 3 || h < 3) return
        val luminance = IntArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            if ((c ushr 24) and 0xFF < 128) { luminance[i] = -1; continue }
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            luminance[i] = (0.299f * r + 0.587f * g + 0.114f * b).toInt()
        }
        val outline = 0xFF1A1428.toInt()
        // Apply Sobel: gradient magnitude > threshold -> outline
        val edges = BooleanArray(pixels.size)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val tl = luminance[(y - 1) * w + (x - 1)]
                val tr = luminance[(y - 1) * w + (x + 1)]
                val ml = luminance[y * w + (x - 1)]
                val mr = luminance[y * w + (x + 1)]
                val bl = luminance[(y + 1) * w + (x - 1)]
                val br = luminance[(y + 1) * w + (x + 1)]
                if (tl < 0 || tr < 0 || ml < 0 || mr < 0 || bl < 0 || br < 0) continue
                val gx = -tl + tr - 2 * ml + 2 * mr - bl + br
                val gy = -tl - 2 * luminance[(y - 1) * w + x] - tr +
                         bl + 2 * luminance[(y + 1) * w + x] + br
                val mag = kotlin.math.sqrt((gx * gx + gy * gy).toFloat()).toInt()
                if (mag > threshold) edges[y * w + x] = true
            }
        }
        // Paint detected edges in outline color
        for (i in edges.indices) {
            if (edges[i]) pixels[i] = outline
        }
    }
}
