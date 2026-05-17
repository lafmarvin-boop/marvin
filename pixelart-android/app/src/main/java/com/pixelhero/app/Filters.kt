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
        OUTER_GLOW("Lueur extérieure"),
        INNER_SHADOW("Ombre interne"),
        EMBOSS("Relief / bevel"),
        SCANLINES("Lignes de balayage CRT"),
        NOISE("Bruit / grain"),
        GLITCH("Glitch (lignes décalées)"),
        VIGNETTE("Vignette (coins assombris)"),
        PIXELATE_2X("Pixeliser 2×2"),
        PIXELATE_4X("Pixeliser 4×4"),
        FIRE_AURA("🔥 Aura de feu"),
        ICE_FROST("❄️ Givre / glace"),
        ELECTRIC("⚡ Électrique"),
        RAINBOW_HUE("🌈 Arc-en-ciel"),
        BLUR("Flou léger (anti-jaggies)"),
        POSTERIZE("Postériser (réduire couleurs)"),
        TEMP_WARM("Tons chauds +"),
        TEMP_COOL("Tons froids +"),
        CB_PROTANOPIA("Daltonisme : protanopie"),
        CB_DEUTERANOPIA("Daltonisme : deutéranopie"),
        CB_TRITANOPIA("Daltonisme : tritanopie");

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
            Filter.OUTER_GLOW -> outerGlow(pixels, w, h, 0xFFFFD66B.toInt())
            Filter.INNER_SHADOW -> innerShadow(pixels, w, h)
            Filter.EMBOSS -> emboss(pixels, w, h)
            Filter.SCANLINES -> scanlines(pixels, w, h)
            Filter.NOISE -> noise(pixels, w, h, 18)
            Filter.GLITCH -> glitch(pixels, w, h)
            Filter.VIGNETTE -> vignette(pixels, w, h)
            Filter.PIXELATE_2X -> pixelate(pixels, w, h, 2)
            Filter.PIXELATE_4X -> pixelate(pixels, w, h, 4)
            Filter.FIRE_AURA -> fireAura(pixels, w, h)
            Filter.ICE_FROST -> iceFrost(pixels, w, h)
            Filter.ELECTRIC -> electric(pixels, w, h)
            Filter.RAINBOW_HUE -> rainbowHue(pixels, w, h)
            Filter.BLUR -> blur(pixels, w, h)
            Filter.POSTERIZE -> posterize(pixels, w, h, 4)
            Filter.TEMP_WARM -> colorTemperature(pixels, w, h, +20)
            Filter.TEMP_COOL -> colorTemperature(pixels, w, h, -20)
            Filter.CB_PROTANOPIA -> colorBlindness(pixels, w, h, CB_PROTAN)
            Filter.CB_DEUTERANOPIA -> colorBlindness(pixels, w, h, CB_DEUTAN)
            Filter.CB_TRITANOPIA -> colorBlindness(pixels, w, h, CB_TRITAN)
        }
    }

    /** Soft halo around opaque pixels. Color tint chosen by caller. */
    private fun outerGlow(pixels: IntArray, w: Int, h: Int, tint: Int): IntArray {
        val out = pixels.copyOf()
        val r = 2
        for (y in 0 until h) for (x in 0 until w) {
            val srcIdx = y * w + x
            if ((pixels[srcIdx] ushr 24) and 0xFF >= 128) continue
            // For each transparent pixel, look at neighbours within radius r.
            // Tint based on inverse distance to any opaque neighbour.
            var minD2 = Int.MAX_VALUE
            for (dy in -r..r) for (dx in -r..r) {
                val nx = x + dx; val ny = y + dy
                if (nx !in 0 until w || ny !in 0 until h) continue
                if ((pixels[ny * w + nx] ushr 24) and 0xFF >= 128) {
                    val d2 = dx * dx + dy * dy
                    if (d2 in 1..minD2) minD2 = d2
                }
            }
            if (minD2 != Int.MAX_VALUE) {
                val alpha = (180 - 60 * minD2).coerceIn(0, 220)
                out[srcIdx] = (alpha shl 24) or (tint and 0x00FFFFFF)
            }
        }
        return out
    }

    /** Darken pixels close to a transparent edge from the inside of the silhouette. */
    private fun innerShadow(pixels: IntArray, w: Int, h: Int): IntArray {
        return IntArray(pixels.size) { i ->
            val c = pixels[i]
            if ((c ushr 24) and 0xFF < 128) return@IntArray c
            val x = i % w; val y = i / w
            // Distance to nearest transparent neighbour (max 3)
            var minD = 4
            for (dy in -3..3) for (dx in -3..3) {
                val nx = x + dx; val ny = y + dy
                val transparent = nx !in 0 until w || ny !in 0 until h ||
                    (pixels[ny * w + nx] ushr 24) and 0xFF < 128
                if (transparent) {
                    val d = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    if (d < minD) minD = d
                }
            }
            if (minD >= 3) return@IntArray c
            val factor = 1f - (3 - minD) * 0.18f  // up to ~46% darkening at edge
            val r = (((c shr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
            val g = (((c shr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
            val b = ((c and 0xFF) * factor).toInt().coerceIn(0, 255)
            (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
    }

    /** Sobel-style edge enhancement to give a chiseled / bevel look. */
    private fun emboss(pixels: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(pixels.size)
        for (y in 0 until h) for (x in 0 until w) {
            val c = pixels[y * w + x]
            if ((c ushr 24) and 0xFF < 128) { out[y * w + x] = c; continue }
            val left = if (x > 0) pixels[y * w + (x - 1)] else c
            val top = if (y > 0) pixels[(y - 1) * w + x] else c
            val lum = ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
            val lumL = ((left shr 16) and 0xFF) + ((left shr 8) and 0xFF) + (left and 0xFF)
            val lumT = ((top shr 16) and 0xFF) + ((top shr 8) and 0xFF) + (top and 0xFF)
            val delta = ((lum - lumL) + (lum - lumT)) / 6
            val r = (((c shr 16) and 0xFF) + delta).coerceIn(0, 255)
            val g = (((c shr 8) and 0xFF) + delta).coerceIn(0, 255)
            val b = ((c and 0xFF) + delta).coerceIn(0, 255)
            out[y * w + x] = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    /** Darken every other row to suggest a CRT scanline look. */
    private fun scanlines(pixels: IntArray, w: Int, h: Int): IntArray {
        return IntArray(pixels.size) { i ->
            val c = pixels[i]
            if ((c ushr 24) and 0xFF < 128) return@IntArray c
            val y = i / w
            if (y and 1 == 0) c
            else {
                val r = (((c shr 16) and 0xFF) * 0.7f).toInt()
                val g = (((c shr 8) and 0xFF) * 0.7f).toInt()
                val b = ((c and 0xFF) * 0.7f).toInt()
                (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    /** Add salt-and-pepper-ish noise: ±[amount] per channel, random per pixel. */
    private fun noise(pixels: IntArray, w: Int, h: Int, amount: Int): IntArray {
        val rng = java.util.Random()
        return IntArray(pixels.size) { i ->
            val c = pixels[i]
            if ((c ushr 24) and 0xFF < 128) return@IntArray c
            val n = rng.nextInt(amount * 2 + 1) - amount
            val r = (((c shr 16) and 0xFF) + n).coerceIn(0, 255)
            val g = (((c shr 8) and 0xFF) + n).coerceIn(0, 255)
            val b = ((c and 0xFF) + n).coerceIn(0, 255)
            (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
    }

    /** Shift a few rows horizontally to suggest a digital glitch. */
    private fun glitch(pixels: IntArray, w: Int, h: Int): IntArray {
        val out = pixels.copyOf()
        val rng = java.util.Random()
        val rowsAffected = (h / 6).coerceAtLeast(1)
        repeat(rowsAffected) {
            val y = rng.nextInt(h)
            val shift = rng.nextInt(11) - 5  // -5..+5 px
            if (shift == 0) return@repeat
            val rowCopy = IntArray(w) { x -> pixels[y * w + x] }
            for (x in 0 until w) {
                val srcX = (x - shift).coerceIn(0, w - 1)
                out[y * w + x] = rowCopy[srcX]
            }
        }
        return out
    }

    /** Darken corners with a circular falloff (classic vignette). */
    private fun vignette(pixels: IntArray, w: Int, h: Int): IntArray {
        val cx = w / 2f; val cy = h / 2f
        val maxR = kotlin.math.sqrt((cx * cx + cy * cy).toDouble()).toFloat()
        return IntArray(pixels.size) { i ->
            val c = pixels[i]
            if ((c ushr 24) and 0xFF < 128) return@IntArray c
            val x = i % w; val y = i / w
            val dx = x - cx; val dy = y - cy
            val r = kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
            val t = (r / maxR).coerceIn(0f, 1f)
            val falloff = 1f - t * t * 0.55f  // strongest in corners
            val rr = (((c shr 16) and 0xFF) * falloff).toInt().coerceIn(0, 255)
            val gg = (((c shr 8) and 0xFF) * falloff).toInt().coerceIn(0, 255)
            val bb = ((c and 0xFF) * falloff).toInt().coerceIn(0, 255)
            (c and 0xFF000000.toInt()) or (rr shl 16) or (gg shl 8) or bb
        }
    }

    /** Orange/red halo + warmed sprite + a few flickering hot pixels. */
    private fun fireAura(pixels: IntArray, w: Int, h: Int): IntArray {
        // 1. Halo with fire-orange tint
        val out = outerGlow(pixels, w, h, 0xFFFF6A1A.toInt())
        val rng = java.util.Random()
        // 2. Warm the opaque pixels slightly toward orange
        for (i in pixels.indices) {
            val src = pixels[i]
            if ((src ushr 24) and 0xFF < 128) continue
            val r = (((src shr 16) and 0xFF) + 25).coerceAtMost(255)
            val g = (((src shr 8) and 0xFF) + 5).coerceAtLeast(0)
            val b = ((src and 0xFF) - 25).coerceAtLeast(0)
            out[i] = (src and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
        // 3. Random hot spots above the sprite (flames flicker)
        val opaqueRows = BooleanArray(h)
        for (y in 0 until h) for (x in 0 until w) {
            if ((pixels[y * w + x] ushr 24) and 0xFF >= 128) { opaqueRows[y] = true; break }
        }
        val topRow = opaqueRows.indexOfFirst { it }.coerceAtLeast(0)
        for (xi in 0 until w) {
            if (rng.nextFloat() < 0.20f) {
                val yy = (topRow - 1 - rng.nextInt(2)).coerceAtLeast(0)
                val color = when (rng.nextInt(3)) {
                    0 -> 0xFFFFEE55.toInt()
                    1 -> 0xFFFF8833.toInt()
                    else -> 0xFFFFB23A.toInt()
                }
                out[yy * w + xi] = color
            }
        }
        return out
    }

    /** Cool tint + brighten + light frost speckles near edges. */
    private fun iceFrost(pixels: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val src = pixels[i]
            if ((src ushr 24) and 0xFF < 128) { out[i] = src; continue }
            val r = (((src shr 16) and 0xFF) - 18).coerceAtLeast(0)
            val g = (((src shr 8) and 0xFF) + 6).coerceAtMost(255)
            val b = ((src and 0xFF) + 30).coerceAtMost(255)
            out[i] = (src and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
        // White frost dots near silhouette edges
        val rng = java.util.Random()
        for (y in 0 until h) for (x in 0 until w) {
            if ((pixels[y * w + x] ushr 24) and 0xFF < 128) continue
            val edge = (x == 0 || y == 0 || x == w - 1 || y == h - 1) ||
                (pixels[y * w + (x - 1)] ushr 24) and 0xFF < 128 ||
                (pixels[y * w + (x + 1)] ushr 24) and 0xFF < 128 ||
                (pixels[(y - 1) * w + x] ushr 24) and 0xFF < 128 ||
                (pixels[(y + 1) * w + x] ushr 24) and 0xFF < 128
            if (edge && rng.nextFloat() < 0.25f) {
                out[y * w + x] = 0xFFEFFAFF.toInt()
            }
        }
        return out
    }

    /** Electric arcs: cyan-blue sparks scattered along edges + slight blue tint. */
    private fun electric(pixels: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val src = pixels[i]
            if ((src ushr 24) and 0xFF < 128) { out[i] = src; continue }
            val r = (((src shr 16) and 0xFF) - 10).coerceAtLeast(0)
            val g = ((src shr 8) and 0xFF)
            val b = ((src and 0xFF) + 25).coerceAtMost(255)
            out[i] = (src and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
        val rng = java.util.Random()
        // Branch a few short lightning-like paths starting from random edge pixels
        repeat((w * h / 24).coerceAtLeast(4)) {
            var x = rng.nextInt(w); var y = rng.nextInt(h)
            // Only start from opaque edge pixels
            if ((pixels[y * w + x] ushr 24) and 0xFF < 128) return@repeat
            val len = 3 + rng.nextInt(5)
            repeat(len) {
                if (x !in 0 until w || y !in 0 until h) return@repeat
                out[y * w + x] = if (rng.nextBoolean()) 0xFFAAEFFF.toInt() else 0xFF5588FF.toInt()
                x += rng.nextInt(3) - 1
                y += rng.nextInt(3) - 1
            }
        }
        return out
    }

    /** Hue rotation that increases with the y position — vertical rainbow tint. */
    private fun rainbowHue(pixels: IntArray, w: Int, h: Int): IntArray {
        val hsv = FloatArray(3)
        return IntArray(pixels.size) { i ->
            val src = pixels[i]
            if ((src ushr 24) and 0xFF < 128) return@IntArray src
            val y = i / w
            val deltaHue = (y.toFloat() / h.coerceAtLeast(1)) * 360f
            android.graphics.Color.colorToHSV(src, hsv)
            hsv[0] = (hsv[0] + deltaHue) % 360f
            val rgb = android.graphics.Color.HSVToColor(hsv) and 0x00FFFFFF
            (src and 0xFF000000.toInt()) or rgb
        }
    }

    /** Block-average every n×n cell. Useful for adding a chunkier look. */
    private fun pixelate(pixels: IntArray, w: Int, h: Int, block: Int): IntArray {
        val out = IntArray(pixels.size)
        var by = 0
        while (by < h) {
            var bx = 0
            while (bx < w) {
                var rs = 0L; var gs = 0L; var bs = 0L; var asum = 0L; var n = 0
                for (yy in by until minOf(by + block, h)) for (xx in bx until minOf(bx + block, w)) {
                    val c = pixels[yy * w + xx]
                    if ((c ushr 24) and 0xFF < 128) continue
                    asum += (c ushr 24) and 0xFF
                    rs += (c shr 16) and 0xFF
                    gs += (c shr 8) and 0xFF
                    bs += c and 0xFF
                    n++
                }
                val avg = if (n == 0) 0 else
                    (((asum / n).toInt() shl 24) or ((rs / n).toInt() shl 16) or
                     ((gs / n).toInt() shl 8) or (bs / n).toInt())
                for (yy in by until minOf(by + block, h)) for (xx in bx until minOf(bx + block, w)) {
                    out[yy * w + xx] = avg
                }
                bx += block
            }
            by += block
        }
        return out
    }

    /** Shift color temperature: positive=warmer (red+, blue-), negative=cooler. */
    private fun colorTemperature(pixels: IntArray, w: Int, h: Int, delta: Int): IntArray {
        return IntArray(pixels.size) { i ->
            val c = pixels[i]
            if ((c ushr 24) and 0xFF < 128) return@IntArray c
            val r = (((c shr 16) and 0xFF) + delta).coerceIn(0, 255)
            val b = ((c and 0xFF) - delta).coerceIn(0, 255)
            val g = (c shr 8) and 0xFF
            (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
        }
    }

    // Color blindness matrices (Brettel/Vienot model, simplified)
    private val CB_PROTAN = floatArrayOf(0.567f, 0.433f, 0f, 0.558f, 0.442f, 0f, 0f, 0.242f, 0.758f)
    private val CB_DEUTAN = floatArrayOf(0.625f, 0.375f, 0f, 0.7f, 0.3f, 0f, 0f, 0.3f, 0.7f)
    private val CB_TRITAN = floatArrayOf(0.95f, 0.05f, 0f, 0f, 0.433f, 0.567f, 0f, 0.475f, 0.525f)

    private fun colorBlindness(pixels: IntArray, w: Int, h: Int, m: FloatArray): IntArray {
        return IntArray(pixels.size) { i ->
            val c = pixels[i]
            if ((c ushr 24) and 0xFF < 128) return@IntArray c
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val nr = (m[0] * r + m[1] * g + m[2] * b).toInt().coerceIn(0, 255)
            val ng = (m[3] * r + m[4] * g + m[5] * b).toInt().coerceIn(0, 255)
            val nb = (m[6] * r + m[7] * g + m[8] * b).toInt().coerceIn(0, 255)
            (c and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
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
