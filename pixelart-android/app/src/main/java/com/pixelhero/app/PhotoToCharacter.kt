package com.pixelhero.app

import android.graphics.Bitmap
import kotlin.math.min

/**
 * Convert a photo to a King God Castle-style chibi character.
 *
 * IMPORTANT: this is NOT face-from-photo synthesis. Real photorealistic
 * portrait -> pixel art conversion requires an ML model (Stable Diffusion,
 * etc.) which we cannot embed on-device. What this DOES:
 *   1. Sample skin / hair / shirt / pants colors from the photo
 *   2. Render a hand-crafted chibi sprite template using those colors
 *   3. Result: a clean KGC-style chibi with YOUR photo's color palette
 *      (not your actual face features)
 *
 * The template is a 16x24 ASCII sprite drawn to look like King God Castle's
 * chibi style: oversized head, big eyes, small torso, short legs, clear
 * outline. Scaled to the project canvas with nearest-neighbor.
 */
object PhotoToCharacter {

    // ---- Chibi sprite template (16 wide x 24 tall) ----
    // O = outline   S = skin       H = hair
    // E = eye dark  M = mouth      C = shirt
    // B = belt      P = pants      F = boot
    // . = transparent
    private val TEMPLATE = arrayOf(
        "....OOOOOO......",  //  0
        "..OOHHHHHHOO....",  //  1
        ".OHHHHHHHHHHO...",  //  2
        ".OHHHHHHHHHHO...",  //  3
        ".OHSSSSSSSSSHO..",  //  4
        ".OHSSSSSSSSSHO..",  //  5
        ".OHSEESSSEESHO..",  //  6  eyes
        ".OHSEESSSEESHO..",  //  7
        ".OHSSSSSSSSSHO..",  //  8
        ".OHSSSSMMSSSHO..",  //  9  mouth
        ".OHSSSSSSSSSHO..",  // 10
        "..OHHSSSSSSHHO..",  // 11
        "...OOSSSSSSO....",  // 12  chin
        "....OSSSSO......",  // 13  neck
        "...OCCCCCCCCO...",  // 14  shoulders
        "..OCCCCCCCCCCO..",  // 15
        "..OCCCCCCCCCCO..",  // 16
        "..OBBBBBBBBBBO..",  // 17  belt
        "..OCCCCCCCCCCO..",  // 18
        "..OPPPP..PPPPO..",  // 19  legs gap
        "..OPPPP..PPPPO..",  // 20
        "..OPPPP..PPPPO..",  // 21
        "..OFFFFOOFFFFO..",  // 22  boots
        "...OOOOOOOOOO..."   // 23
    )
    private const val TPL_W = 16
    private const val TPL_H = 24

    /** Skin / hair / shirt / pants colors sampled from a photo (ARGB ints). */
    data class Colors(val skin: Int, val hair: Int, val shirt: Int, val pants: Int)

    /** Public color sampler — useful for building AI prompts from a photo. */
    fun sampleColors(source: Bitmap): Colors {
        val srcW = source.width; val srcH = source.height
        val srcPixels = IntArray(srcW * srcH)
        source.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)
        val skin = sampleSkin(srcPixels, srcW, srcH) ?: 0xFFF2C09B.toInt()
        val hair = sampleHair(srcPixels, srcW, srcH) ?: 0xFF2E1A0B.toInt()
        val shirt = sampleDominantBand(srcPixels, srcW, srcH, 0.45f, 0.65f, listOf(skin, hair))
            ?: 0xFF3366FF.toInt()
        val pants = sampleDominantBand(srcPixels, srcW, srcH, 0.7f, 0.95f, listOf(skin, hair, shirt))
            ?: 0xFF333366.toInt()
        return Colors(skin, hair, shirt, pants)
    }

    fun convert(source: Bitmap, w: Int, h: Int): Pair<IntArray, IntArray> {
        val c = sampleColors(source)
        val skin = c.skin; val hair = c.hair; val shirt = c.shirt; val pants = c.pants

        val outline = 0xFF1A1428.toInt()
        val eye = 0xFF1A1428.toInt()
        val mouth = darken(skin, 0.55f)
        val belt = darken(shirt, 0.55f)
        val boot = darken(pants, 0.55f)

        val charToColor = mapOf(
            '.' to 0,
            'O' to outline,
            'S' to skin,
            'H' to hair,
            'E' to eye,
            'M' to mouth,
            'C' to shirt,
            'B' to belt,
            'P' to pants,
            'F' to boot
        )

        val pixels = IntArray(w * h)
        val scale = min(w.toFloat() / TPL_W, h.toFloat() / TPL_H)
        val renderW = (TPL_W * scale).toInt().coerceAtLeast(1)
        val renderH = (TPL_H * scale).toInt().coerceAtLeast(1)
        val offX = (w - renderW) / 2
        val offY = (h - renderH) / 2
        for (y in 0 until renderH) for (x in 0 until renderW) {
            val tx = (x.toFloat() / renderW * TPL_W).toInt().coerceIn(0, TPL_W - 1)
            val ty = (y.toFloat() / renderH * TPL_H).toInt().coerceIn(0, TPL_H - 1)
            val ch = TEMPLATE[ty][tx]
            val color = charToColor[ch] ?: 0
            if (color != 0) {
                pixels[(offY + y) * w + (offX + x)] = color
            }
        }

        val palette = pixels.toSet().filter { (it ushr 24) and 0xFF >= 128 }.toIntArray()
        return pixels to palette
    }

    // ========================================================================
    // Color sampling
    // ========================================================================

    private fun isSkinLike(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return r in 80..255 && r > g && g > b && (r - g) in 6..90 && (g - b) in 0..70
    }

    private fun sampleSkin(pixels: IntArray, w: Int, h: Int): Int? {
        val scanH = (h * 0.6f).toInt()
        var rSum = 0L; var gSum = 0L; var bSum = 0L; var n = 0
        for (y in 0 until scanH) for (x in 0 until w) {
            val c = pixels[y * w + x]
            if (!isSkinLike(c)) continue
            rSum += (c shr 16) and 0xFF
            gSum += (c shr 8) and 0xFF
            bSum += c and 0xFF
            n++
        }
        if (n < 30) return null
        return 0xFF000000.toInt() or
            ((rSum / n).toInt() shl 16) or ((gSum / n).toInt() shl 8) or (bSum / n).toInt()
    }

    private fun sampleHair(pixels: IntArray, w: Int, h: Int): Int? {
        val scanH = (h * 0.3f).toInt()
        return dominantColorInBand(pixels, w, h, 0, scanH, exclude = null, excludeSkin = true)
    }

    private fun sampleDominantBand(pixels: IntArray, w: Int, h: Int,
                                    fromFrac: Float, toFrac: Float,
                                    excludeColors: List<Int>): Int? {
        val fromY = (h * fromFrac).toInt().coerceIn(0, h - 1)
        val toY = (h * toFrac).toInt().coerceIn(0, h - 1)
        if (toY <= fromY) return null
        return dominantColorInBand(pixels, w, h, fromY, toY, exclude = excludeColors, excludeSkin = true)
    }

    private fun dominantColorInBand(pixels: IntArray, w: Int, h: Int,
                                    fromY: Int, toY: Int,
                                    exclude: List<Int>?,
                                    excludeSkin: Boolean): Int? {
        val excludeKeys = exclude?.map { bucketKey(it) }?.toSet() ?: emptySet()
        val counts = HashMap<Int, Int>()
        for (y in fromY..toY) for (x in 0 until w) {
            val c = pixels[y * w + x]
            if ((c ushr 24) and 0xFF < 128) continue
            if (excludeSkin && isSkinLike(c)) continue
            val key = bucketKey(c)
            if (key in excludeKeys) continue
            counts[key] = (counts[key] ?: 0) + 1
        }
        val best = counts.entries.maxByOrNull { it.value } ?: return null
        if (best.value < 8) return null
        return bucketToColor(best.key)
    }

    private fun bucketKey(c: Int): Int {
        val r = ((c shr 16) and 0xFF) shr 3
        val g = ((c shr 8) and 0xFF) shr 3
        val b = (c and 0xFF) shr 3
        return (r shl 10) or (g shl 5) or b
    }

    private fun bucketToColor(key: Int): Int {
        val r = ((key shr 10) and 0x1F) shl 3
        val g = ((key shr 5) and 0x1F) shl 3
        val b = (key and 0x1F) shl 3
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }

    private fun darken(c: Int, factor: Float): Int {
        val r = (((c shr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = (((c shr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val b = ((c and 0xFF) * factor).toInt().coerceIn(0, 255)
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }
}
