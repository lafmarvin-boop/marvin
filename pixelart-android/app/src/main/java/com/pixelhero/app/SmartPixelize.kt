package com.pixelhero.app

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Professional-grade image-to-pixel-art pipeline.
 *
 * Key techniques used:
 *  - Bilinear downscale via Bitmap.createScaledBitmap (Android's hardware-accelerated)
 *  - Bilateral filter (edge-preserving denoising)
 *  - Saturation boost in HSL space
 *  - Median-cut palette quantization
 *  - K-means refinement (3 iterations) for tighter palette centroids
 *  - LAB color space for nearest-color matching (perceptually accurate)
 *  - Floyd-Steinberg, Atkinson, or Bayer 4x4 dithering
 *  - Sobel-based auto-outline pass
 */
object SmartPixelize {

    enum class Style(val displayName: String) {
        PRO("⭐ Pro (ultra propre)"),
        STANDARD("Standard (équilibré)"),
        VIBRANT("Vibrant (saturé)"),
        CARTOON("Cartoon (lisse + contour)"),
        RETRO("Rétro (16 couleurs)"),
        SHARP("Net (sans tramage)"),
        GAMEBOY("Game Boy (4 verts)"),
        PASTEL("Pastel (Atkinson)"),
        NES_STYLE("Style NES")
    }

    enum class DitherMode { NONE, FLOYD, BAYER, ATKINSON }

    data class Options(
        val paletteSize: Int = 16,
        val preBlur: Boolean = true,
        val bilateral: Boolean = false,
        val saturationBoost: Float = 1.0f,
        val dither: DitherMode = DitherMode.FLOYD,
        val outline: Boolean = false,
        val outlineThreshold: Int = 60,
        val kmeansIterations: Int = 3,
        val useLab: Boolean = true,
        val forcedPalette: IntArray? = null  // if non-null, used instead of extraction
    )

    fun styleOptions(style: Style): Options = when (style) {
        // PRO: bilateral denoise (preserves edges), no pre-blur (sharp), 18-color palette,
        // 6 K-means refinement iterations (tight clusters), no dither (clean blocks),
        // LAB perceptual color matching. Pair with the box-averaging downscale path
        // for output that looks hand-pixelled.
        Style.PRO -> Options(
            paletteSize = 18, preBlur = false, bilateral = true,
            saturationBoost = 1.05f, dither = DitherMode.NONE,
            outline = false, kmeansIterations = 6, useLab = true
        )
        Style.STANDARD -> Options()
        Style.VIBRANT -> Options(paletteSize = 20, saturationBoost = 1.35f, dither = DitherMode.FLOYD)
        Style.CARTOON -> Options(paletteSize = 12, bilateral = true, preBlur = false,
            saturationBoost = 1.15f, dither = DitherMode.NONE, outline = true)
        Style.RETRO -> Options(paletteSize = 16, dither = DitherMode.BAYER, kmeansIterations = 2)
        Style.SHARP -> Options(paletteSize = 24, preBlur = false, dither = DitherMode.NONE)
        Style.GAMEBOY -> Options(dither = DitherMode.BAYER, forcedPalette = GAMEBOY_PALETTE)
        Style.PASTEL -> Options(paletteSize = 14, saturationBoost = 0.85f, dither = DitherMode.ATKINSON)
        Style.NES_STYLE -> Options(paletteSize = 20, saturationBoost = 1.1f,
            dither = DitherMode.FLOYD, forcedPalette = NES_PALETTE)
    }

    private val GAMEBOY_PALETTE = intArrayOf(
        0xFF0F380F.toInt(), 0xFF306230.toInt(), 0xFF8BAC0F.toInt(), 0xFF9BBC0F.toInt()
    )

    private val NES_PALETTE = intArrayOf(
        0xFF000000.toInt(), 0xFF1D2B53.toInt(), 0xFF7E2553.toInt(), 0xFF008751.toInt(),
        0xFFAB5236.toInt(), 0xFF5F574F.toInt(), 0xFFC2C3C7.toInt(), 0xFFFFF1E8.toInt(),
        0xFFFF004D.toInt(), 0xFFFFA300.toInt(), 0xFFFFEC27.toInt(), 0xFF00E436.toInt(),
        0xFF29ADFF.toInt(), 0xFF83769C.toInt(), 0xFFFF77A8.toInt(), 0xFFFFCCAA.toInt()
    )

    fun pixelize(
        bitmap: Bitmap,
        w: Int, h: Int,
        fit: BgFitMode,
        style: Style
    ): Pair<IntArray, IntArray> = pixelizeWith(bitmap, w, h, fit, styleOptions(style))

    fun pixelizeWith(
        bitmap: Bitmap,
        w: Int, h: Int,
        fit: BgFitMode,
        opt: Options
    ): Pair<IntArray, IntArray> {
        // 1. Downscale (bilinear, hardware-accelerated by Android)
        val pixels = bilinearDownscale(bitmap, w, h, fit)

        // 2. Denoise pass: bilateral (edge-preserving) or Gaussian (simple)
        if (opt.bilateral) bilateralFilter(pixels, w, h)
        else if (opt.preBlur) gaussianBlur(pixels, w, h)

        // 3. Saturation adjustment (boost or desat depending on style)
        if (opt.saturationBoost != 1.0f) saturate(pixels, w, h, opt.saturationBoost)

        // 4. Palette: extract or use forced
        val palette: IntArray = if (opt.forcedPalette != null) {
            opt.forcedPalette
        } else {
            val initialPalette = medianCut(pixels, opt.paletteSize)
            if (opt.kmeansIterations > 0 && initialPalette.isNotEmpty()) {
                kmeansRefine(pixels, initialPalette, opt.kmeansIterations)
            } else initialPalette
        }
        if (palette.isEmpty()) return pixels to intArrayOf()

        // 5. Precompute LAB for palette if requested (massive speedup)
        val paletteLab: Array<FloatArray>? =
            if (opt.useLab) Array(palette.size) { rgbToLab(palette[it]) } else null

        // 6. Apply palette with chosen dither
        when (opt.dither) {
            DitherMode.NONE -> applyNearest(pixels, palette, paletteLab)
            DitherMode.FLOYD -> applyFloydSteinberg(pixels, w, h, palette, paletteLab)
            DitherMode.BAYER -> applyBayer(pixels, w, h, palette, paletteLab)
            DitherMode.ATKINSON -> applyAtkinson(pixels, w, h, palette, paletteLab)
        }

        // 7. Optional outline (after quantization for stable edge detection)
        if (opt.outline) addOutline(pixels, w, h, opt.outlineThreshold)

        return pixels to palette
    }

    // ========================================================================
    // Downscale
    // ========================================================================
    /**
     * Picks between bilinear (Android-accelerated) and box-area-averaging
     * downscale. Box averaging produces clean, alias-free pixel art when
     * the source is significantly larger than the target — the gold-standard
     * approach used by tools like Aseprite for photo→pixel-art conversion.
     * Bilinear is good enough when the ratio is close to 1.
     */
    private fun bilinearDownscale(src: Bitmap, w: Int, h: Int, fit: BgFitMode): IntArray {
        val srcW = src.width; val srcH = src.height
        val targetW: Int; val targetH: Int; val dx0: Int; val dy0: Int
        when (fit) {
            BgFitMode.STRETCH -> { targetW = w; targetH = h; dx0 = 0; dy0 = 0 }
            BgFitMode.COVER -> {
                val s = max(w.toFloat() / srcW, h.toFloat() / srcH)
                targetW = (srcW * s).toInt(); targetH = (srcH * s).toInt()
                dx0 = (w - targetW) / 2; dy0 = (h - targetH) / 2
            }
            BgFitMode.FIT -> {
                val s = min(w.toFloat() / srcW, h.toFloat() / srcH)
                targetW = (srcW * s).toInt(); targetH = (srcH * s).toInt()
                dx0 = (w - targetW) / 2; dy0 = (h - targetH) / 2
            }
        }
        val tw = targetW.coerceAtLeast(1); val th = targetH.coerceAtLeast(1)
        val ratio = max(srcW.toFloat() / tw, srcH.toFloat() / th)
        val scaledPixels: IntArray = if (ratio > 1.5f) {
            // Box-area-averaging: average every source pixel that maps into each
            // destination pixel. Removes high-frequency noise that bilinear
            // sampling otherwise aliases into the result.
            boxAreaDownscale(src, tw, th)
        } else {
            val scaled = Bitmap.createScaledBitmap(src, tw, th, true)
            val px = IntArray(tw * th)
            scaled.getPixels(px, 0, tw, 0, 0, tw, th)
            if (scaled !== src) scaled.recycle()
            px
        }
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val sx = x - dx0; val sy = y - dy0
            if (sx in 0 until tw && sy in 0 until th) {
                out[y * w + x] = scaledPixels[sy * tw + sx]
            }
        }
        return out
    }

    private fun boxAreaDownscale(src: Bitmap, tw: Int, th: Int): IntArray {
        val srcW = src.width; val srcH = src.height
        val srcPixels = IntArray(srcW * srcH)
        src.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)
        val out = IntArray(tw * th)
        val scaleX = srcW.toFloat() / tw
        val scaleY = srcH.toFloat() / th
        for (ty in 0 until th) {
            val sy0 = (ty * scaleY).toInt().coerceIn(0, srcH - 1)
            val sy1 = ((ty + 1) * scaleY).toInt().coerceIn(sy0 + 1, srcH)
            for (tx in 0 until tw) {
                val sx0 = (tx * scaleX).toInt().coerceIn(0, srcW - 1)
                val sx1 = ((tx + 1) * scaleX).toInt().coerceIn(sx0 + 1, srcW)
                var rs = 0L; var gs = 0L; var bs = 0L; var asum = 0L; var n = 0
                for (yy in sy0 until sy1) for (xx in sx0 until sx1) {
                    val c = srcPixels[yy * srcW + xx]
                    asum += (c ushr 24) and 0xFF
                    rs   += (c shr 16) and 0xFF
                    gs   += (c shr 8)  and 0xFF
                    bs   += c and 0xFF
                    n++
                }
                if (n == 0) { out[ty * tw + tx] = 0; continue }
                val ar = (asum / n).toInt()
                val rr = (rs / n).toInt()
                val gr = (gs / n).toInt()
                val br = (bs / n).toInt()
                out[ty * tw + tx] = (ar shl 24) or (rr shl 16) or (gr shl 8) or br
            }
        }
        return out
    }

    // ========================================================================
    // Denoising
    // ========================================================================
    private fun gaussianBlur(pixels: IntArray, w: Int, h: Int) {
        if (w < 3 || h < 3) return
        val copy = pixels.copyOf()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            var r = 0; var g = 0; var b = 0; var a = 0
            for (ky in -1..1) for (kx in -1..1) {
                val weight = (2 - abs(kx)) * (2 - abs(ky))
                val c = copy[(y + ky) * w + (x + kx)]
                r += ((c shr 16) and 0xFF) * weight
                g += ((c shr 8) and 0xFF) * weight
                b += (c and 0xFF) * weight
                a += ((c ushr 24) and 0xFF) * weight
            }
            pixels[y * w + x] = ((a / 16) shl 24) or ((r / 16) shl 16) or ((g / 16) shl 8) or (b / 16)
        }
    }

    /** Edge-preserving bilateral filter (small radius, color-weighted). */
    private fun bilateralFilter(pixels: IntArray, w: Int, h: Int, radius: Int = 2, sigmaColor: Float = 28f) {
        val copy = pixels.copyOf()
        val sigmaColorSq2 = 2f * sigmaColor * sigmaColor
        for (y in 0 until h) for (x in 0 until w) {
            val center = copy[y * w + x]
            if ((center ushr 24) and 0xFF < 128) continue
            val cr = (center shr 16) and 0xFF
            val cg = (center shr 8) and 0xFF
            val cb = center and 0xFF
            var sumR = 0f; var sumG = 0f; var sumB = 0f; var wSum = 0f
            for (dy in -radius..radius) for (dx in -radius..radius) {
                val nx = x + dx; val ny = y + dy
                if (nx !in 0 until w || ny !in 0 until h) continue
                val n = copy[ny * w + nx]
                if ((n ushr 24) and 0xFF < 128) continue
                val nr = (n shr 16) and 0xFF
                val ng = (n shr 8) and 0xFF
                val nb = n and 0xFF
                val cd = (cr - nr).toFloat() * (cr - nr) + (cg - ng).toFloat() * (cg - ng) +
                         (cb - nb).toFloat() * (cb - nb)
                val sw = exp(-cd / sigmaColorSq2)
                sumR += nr * sw; sumG += ng * sw; sumB += nb * sw; wSum += sw
            }
            if (wSum > 0) {
                val r = (sumR / wSum).toInt().coerceIn(0, 255)
                val g = (sumG / wSum).toInt().coerceIn(0, 255)
                val b = (sumB / wSum).toInt().coerceIn(0, 255)
                pixels[y * w + x] = (center and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    // ========================================================================
    // Saturation
    // ========================================================================
    private fun saturate(pixels: IntArray, w: Int, h: Int, factor: Float) {
        for (i in pixels.indices) {
            val c = pixels[i]
            if ((c ushr 24) and 0xFF < 128) continue
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val gray = 0.299f * r + 0.587f * g + 0.114f * b
            val nr = (gray + (r - gray) * factor).toInt().coerceIn(0, 255)
            val ng = (gray + (g - gray) * factor).toInt().coerceIn(0, 255)
            val nb = (gray + (b - gray) * factor).toInt().coerceIn(0, 255)
            pixels[i] = (c and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
        }
    }

    // ========================================================================
    // Median cut
    // ========================================================================
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
        fun longestAxis(): Int = when (max(rRange, max(gRange, bRange))) {
            rRange -> 0; gRange -> 1; else -> 2
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
        val sample = mutableListOf<Int>()
        val step = (pixels.size / 20000).coerceAtLeast(1)
        for (i in pixels.indices step step) {
            val c = pixels[i]
            if ((c ushr 24) and 0xFF >= 128) sample.add(c)
        }
        if (sample.isEmpty()) return intArrayOf()
        val boxes = mutableListOf(ColorBox(sample))
        while (boxes.size < count) {
            val maxIdx = boxes.indices.maxByOrNull { boxes[it].volume } ?: break
            val box = boxes[maxIdx]
            if (box.pixels.size < 2 || box.volume <= 1) break
            val (a, b) = box.split()
            boxes.removeAt(maxIdx)
            boxes.add(a); boxes.add(b)
        }
        return boxes.map { it.average() }.toIntArray()
    }

    // ========================================================================
    // K-means refinement
    // ========================================================================
    fun kmeansRefine(pixels: IntArray, initial: IntArray, iterations: Int): IntArray {
        val current = initial.copyOf()
        val sumsR = IntArray(current.size)
        val sumsG = IntArray(current.size)
        val sumsB = IntArray(current.size)
        val counts = IntArray(current.size)
        repeat(iterations) {
            sumsR.fill(0); sumsG.fill(0); sumsB.fill(0); counts.fill(0)
            for (p in pixels) {
                if ((p ushr 24) and 0xFF < 128) continue
                val pr = (p shr 16) and 0xFF
                val pg = (p shr 8) and 0xFF
                val pb = p and 0xFF
                var bestIdx = 0; var bestDist = Int.MAX_VALUE
                for (i in current.indices) {
                    val c = current[i]
                    val dr = pr - ((c shr 16) and 0xFF)
                    val dg = pg - ((c shr 8) and 0xFF)
                    val db = pb - (c and 0xFF)
                    val d = dr*dr + dg*dg + db*db
                    if (d < bestDist) { bestDist = d; bestIdx = i }
                }
                sumsR[bestIdx] += pr; sumsG[bestIdx] += pg; sumsB[bestIdx] += pb; counts[bestIdx]++
            }
            for (i in current.indices) {
                if (counts[i] > 0) {
                    val r = sumsR[i] / counts[i]
                    val g = sumsG[i] / counts[i]
                    val b = sumsB[i] / counts[i]
                    current[i] = 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
                }
            }
        }
        return current
    }

    // ========================================================================
    // LAB color space (for perceptually accurate matching)
    // ========================================================================
    private fun srgbToLinear(c: Int): Float {
        val n = c / 255f
        return if (n <= 0.04045f) n / 12.92f
               else ((n + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }

    private fun rgbToLab(color: Int): FloatArray {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val rl = srgbToLinear(r)
        val gl = srgbToLinear(g)
        val bl = srgbToLinear(b)
        // sRGB D65 -> XYZ
        val x = rl * 0.4124564f + gl * 0.3575761f + bl * 0.1804375f
        val y = rl * 0.2126729f + gl * 0.7151522f + bl * 0.0721750f
        val z = rl * 0.0193339f + gl * 0.1191920f + bl * 0.9503041f
        // XYZ -> LAB (D65 white)
        fun f(t: Float): Float =
            if (t > 0.008856f) t.toDouble().pow(1.0 / 3.0).toFloat()
            else 7.787f * t + 16f / 116f
        val fx = f(x / 0.95047f)
        val fy = f(y / 1.0f)
        val fz = f(z / 1.08883f)
        return floatArrayOf(116f * fy - 16f, 500f * (fx - fy), 200f * (fy - fz))
    }

    // ========================================================================
    // Nearest-color lookup
    // ========================================================================
    private fun nearestColor(c: Int, palette: IntArray, paletteLab: Array<FloatArray>?): Int {
        if ((c ushr 24) and 0xFF < 128) return 0
        if (paletteLab != null) {
            val lab = rgbToLab(c)
            var best = palette[0]; var bestDist = Float.MAX_VALUE
            for (i in palette.indices) {
                val p = paletteLab[i]
                val dL = lab[0] - p[0]; val da = lab[1] - p[1]; val db = lab[2] - p[2]
                val d = dL * dL + da * da + db * db
                if (d < bestDist) { bestDist = d; best = palette[i] }
            }
            return best
        }
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
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

    // ========================================================================
    // Dither methods
    // ========================================================================
    private fun applyNearest(pixels: IntArray, palette: IntArray, paletteLab: Array<FloatArray>?) {
        for (i in pixels.indices) pixels[i] = nearestColor(pixels[i], palette, paletteLab)
    }

    private val BAYER_4X4 = arrayOf(
        intArrayOf(0, 8, 2, 10),
        intArrayOf(12, 4, 14, 6),
        intArrayOf(3, 11, 1, 9),
        intArrayOf(15, 7, 13, 5)
    )

    private fun applyBayer(pixels: IntArray, w: Int, h: Int, palette: IntArray, paletteLab: Array<FloatArray>?) {
        for (y in 0 until h) for (x in 0 until w) {
            val c = pixels[y * w + x]
            if ((c ushr 24) and 0xFF < 128) continue
            val threshold = (BAYER_4X4[y % 4][x % 4] - 8) * 4
            val r = (((c shr 16) and 0xFF) + threshold).coerceIn(0, 255)
            val g = (((c shr 8) and 0xFF) + threshold).coerceIn(0, 255)
            val b = ((c and 0xFF) + threshold).coerceIn(0, 255)
            val biased = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
            pixels[y * w + x] = nearestColor(biased, palette, paletteLab)
        }
    }

    private fun applyFloydSteinberg(pixels: IntArray, w: Int, h: Int, palette: IntArray, paletteLab: Array<FloatArray>?) {
        for (y in 0 until h) for (x in 0 until w) {
            val old = pixels[y * w + x]
            if ((old ushr 24) and 0xFF < 128) continue
            val new = nearestColor(old, palette, paletteLab)
            pixels[y * w + x] = new
            val er = ((old shr 16) and 0xFF) - ((new shr 16) and 0xFF)
            val eg = ((old shr 8) and 0xFF) - ((new shr 8) and 0xFF)
            val eb = (old and 0xFF) - (new and 0xFF)
            spread(pixels, w, h, x + 1, y, er, eg, eb, 7f / 16f)
            spread(pixels, w, h, x - 1, y + 1, er, eg, eb, 3f / 16f)
            spread(pixels, w, h, x, y + 1, er, eg, eb, 5f / 16f)
            spread(pixels, w, h, x + 1, y + 1, er, eg, eb, 1f / 16f)
        }
    }

    /** Atkinson dither: gentler than Floyd-Steinberg, used by classic Mac. */
    private fun applyAtkinson(pixels: IntArray, w: Int, h: Int, palette: IntArray, paletteLab: Array<FloatArray>?) {
        val factor = 1f / 8f
        for (y in 0 until h) for (x in 0 until w) {
            val old = pixels[y * w + x]
            if ((old ushr 24) and 0xFF < 128) continue
            val new = nearestColor(old, palette, paletteLab)
            pixels[y * w + x] = new
            val er = ((old shr 16) and 0xFF) - ((new shr 16) and 0xFF)
            val eg = ((old shr 8) and 0xFF) - ((new shr 8) and 0xFF)
            val eb = (old and 0xFF) - (new and 0xFF)
            spread(pixels, w, h, x + 1, y, er, eg, eb, factor)
            spread(pixels, w, h, x + 2, y, er, eg, eb, factor)
            spread(pixels, w, h, x - 1, y + 1, er, eg, eb, factor)
            spread(pixels, w, h, x, y + 1, er, eg, eb, factor)
            spread(pixels, w, h, x + 1, y + 1, er, eg, eb, factor)
            spread(pixels, w, h, x, y + 2, er, eg, eb, factor)
        }
    }

    private fun spread(pixels: IntArray, w: Int, h: Int, x: Int, y: Int,
                       er: Int, eg: Int, eb: Int, factor: Float) {
        if (x !in 0 until w || y !in 0 until h) return
        val c = pixels[y * w + x]
        if ((c ushr 24) and 0xFF < 128) return
        val r = (((c shr 16) and 0xFF) + (er * factor).toInt()).coerceIn(0, 255)
        val g = (((c shr 8) and 0xFF) + (eg * factor).toInt()).coerceIn(0, 255)
        val b = ((c and 0xFF) + (eb * factor).toInt()).coerceIn(0, 255)
        pixels[y * w + x] = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }

    // ========================================================================
    // Outline (Sobel)
    // ========================================================================
    private fun addOutline(pixels: IntArray, w: Int, h: Int, threshold: Int) {
        if (w < 3 || h < 3) return
        val luminance = IntArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            if ((c ushr 24) and 0xFF < 128) { luminance[i] = -1; continue }
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            luminance[i] = (0.299f * r + 0.587f * g + 0.114f * b).toInt()
        }
        val outline = 0xFF1A1428.toInt()
        val edges = BooleanArray(pixels.size)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
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
            val mag = sqrt((gx * gx + gy * gy).toFloat()).toInt()
            if (mag > threshold) edges[y * w + x] = true
        }
        for (i in edges.indices) if (edges[i]) pixels[i] = outline
    }
}
