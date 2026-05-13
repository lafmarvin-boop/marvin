package com.pixelhero.app

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * Convert a photo to a King God Castle-style chibi pixel art character.
 *
 * Style characteristics targeted:
 *   - Chibi proportions: head ~45% of total height
 *   - Clean 1-pixel black outline around the silhouette
 *   - Limited palette (~12 colors), no dithering — flat-shaded look
 *   - Simple body shape using PoseTemplate.HUMANOID_FRONT
 *   - Face region cropped from the photo via skin-blob detection
 *
 * NOT a deep-learning portrait synthesis (we don't have on-device ML). It's
 * a procedural composition: detect face in photo → upscale into top half of
 * canvas → procedural body below with clothing colours sampled from the photo.
 */
object PhotoToCharacter {

    fun convert(source: Bitmap, w: Int, h: Int): Pair<IntArray, IntArray> {
        val srcW = source.width; val srcH = source.height
        val srcPixels = IntArray(srcW * srcH)
        source.getPixels(srcPixels, 0, srcW, 0, 0, srcW, srcH)

        // 1. Detect face region (skin-blob bounding box of upper area)
        val face = detectFaceRect(srcPixels, srcW, srcH)

        // 2. Sample 2 clothing colours from below the face
        val shirtSampleY = (face.bottom + (srcH - face.bottom) * 0.2f).toInt().coerceAtMost(srcH - 1)
        val pantsSampleY = (face.bottom + (srcH - face.bottom) * 0.6f).toInt().coerceAtMost(srcH - 1)
        val shirtColor = sampleDominantColor(srcPixels, srcW, srcH, face.left, shirtSampleY, face.right, shirtSampleY + 10)
            .takeIf { it != 0 } ?: 0xFF3366FF.toInt()
        val pantsColor = sampleDominantColor(srcPixels, srcW, srcH, face.left, pantsSampleY, face.right, pantsSampleY + 10)
            .takeIf { it != 0 } ?: 0xFF333366.toInt()
        val hairColor = sampleDominantNonSkinTop(srcPixels, srcW, srcH, face)
            .takeIf { it != 0 } ?: 0xFF2E1A0B.toInt()
        val skinColor = sampleSkin(srcPixels, srcW, srcH, face)

        // 3. Build the output: head crop on top, simple body shape below
        val headBottom = (h * 0.45f).toInt().coerceAtLeast(8)
        val torsoBottom = (h * 0.78f).toInt().coerceAtLeast(headBottom + 4)
        val pixels = IntArray(w * h)

        // 3a. Crop the face from the source, fit into top portion of canvas
        // Make the face crop SQUARE and slightly enlarged (chibi has big head)
        val faceW = face.right - face.left + 1
        val faceH = face.bottom - face.top + 1
        val cropSize = max(faceW, faceH)
        val cropCx = (face.left + face.right) / 2
        val cropCy = (face.top + face.bottom) / 2 - faceH / 6  // bias up slightly
        val cropL = (cropCx - cropSize / 2).coerceAtLeast(0)
        val cropT = (cropCy - cropSize / 2).coerceAtLeast(0)
        val cropR = (cropL + cropSize).coerceAtMost(srcW)
        val cropB = (cropT + cropSize).coerceAtMost(srcH)
        val cropBmp = try {
            Bitmap.createBitmap(source, cropL, cropT, cropR - cropL, cropB - cropT)
        } catch (e: Exception) { source }

        // 3b. Downscale the face crop into the head region (centered)
        val headRegionW = (w * 0.9f).toInt().coerceAtMost(w)
        val headRegionH = headBottom
        val headPixels = ImageToPixelArt.downscale(cropBmp, headRegionW, headRegionH, BgFitMode.COVER)
        val headOffsetX = (w - headRegionW) / 2
        for (y in 0 until headRegionH) for (x in 0 until headRegionW) {
            pixels[y * w + (x + headOffsetX)] = headPixels[y * headRegionW + x]
        }

        // 4. Draw a simple chibi body below
        drawChibiBody(pixels, w, h, headBottom, torsoBottom, shirtColor, pantsColor, skinColor)

        // 5. Quantize: median cut to ~12 colors
        var palette = SmartPixelize.medianCut(pixels, 12)
        if (palette.isNotEmpty()) {
            palette = SmartPixelize.kmeansRefine(pixels, palette, 3)
            // Apply nearest-color (no dither: hard-edge shading like KGC)
            for (i in pixels.indices) {
                pixels[i] = nearestColorRgb(pixels[i], palette)
            }
        }

        // 6. Add 1-pixel black outline around the silhouette
        addOutline(pixels, w, h)

        // 7. Ensure black is in the palette (it likely is from the outline)
        val finalPalette = if (palette.any { (it and 0xFFFFFF) < 0x202020 })
            palette else palette + intArrayOf(0xFF1A1428.toInt())

        return pixels to finalPalette
    }

    // ========================================================================
    // Face detection (skin-blob bounding box, upper portion)
    // ========================================================================
    data class FaceRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun detectFaceRect(pixels: IntArray, w: Int, h: Int): FaceRect {
        // Scan only upper 70% of the image (face usually in upper portion)
        val scanH = (h * 0.7f).toInt()
        var minX = w; var maxX = -1; var minY = scanH; var maxY = -1; var count = 0
        for (y in 0 until scanH) for (x in 0 until w) {
            if (isSkinLike(pixels[y * w + x])) {
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
                count++
            }
        }
        if (count < 50 || maxX < 0) {
            // Fallback: center crop assuming portrait
            val s = min(w, h) * 2 / 3
            val cx = w / 2; val cy = h / 3
            return FaceRect(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
        }
        // Restrict to upper portion of skin region (face vs hands/neck)
        val faceBottom = minY + (maxY - minY + 1) * 2 / 3
        return FaceRect(minX, minY, maxX, faceBottom)
    }

    private fun isSkinLike(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return r in 80..255 && r > g && g > b && (r - g) in 6..90 && (g - b) in 0..70
    }

    // ========================================================================
    // Color sampling helpers
    // ========================================================================
    private fun sampleDominantColor(pixels: IntArray, w: Int, h: Int,
                                     x0: Int, y0: Int, x1: Int, y1: Int): Int {
        val xa = x0.coerceIn(0, w - 1); val xb = x1.coerceIn(0, w - 1)
        val ya = y0.coerceIn(0, h - 1); val yb = y1.coerceIn(0, h - 1)
        if (xa >= xb || ya >= yb) return 0
        val counts = HashMap<Int, Int>()
        for (y in ya..yb) for (x in xa..xb) {
            val c = pixels[y * w + x]
            if ((c ushr 24) and 0xFF < 128) continue
            // Quantize to 5 bits for grouping
            val r = ((c shr 16) and 0xFF) shr 3
            val g = ((c shr 8) and 0xFF) shr 3
            val b = (c and 0xFF) shr 3
            val key = (r shl 10) or (g shl 5) or b
            counts[key] = (counts[key] ?: 0) + 1
        }
        val best = counts.entries.maxByOrNull { it.value } ?: return 0
        val key = best.key
        val r = ((key shr 10) and 0x1F) shl 3
        val g = ((key shr 5) and 0x1F) shl 3
        val b = (key and 0x1F) shl 3
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }

    private fun sampleDominantNonSkinTop(pixels: IntArray, w: Int, h: Int, face: FaceRect): Int {
        // Hair: dominant non-skin color in the top of the face rect
        val topBand = face.top.coerceAtLeast(0)..((face.top + (face.bottom - face.top) / 3).coerceAtMost(h - 1))
        val counts = HashMap<Int, Int>()
        for (y in topBand) for (x in face.left..face.right) {
            if (x !in 0 until w) continue
            val c = pixels[y * w + x]
            if ((c ushr 24) and 0xFF < 128) continue
            if (isSkinLike(c)) continue
            val r = ((c shr 16) and 0xFF) shr 3
            val g = ((c shr 8) and 0xFF) shr 3
            val b = (c and 0xFF) shr 3
            val key = (r shl 10) or (g shl 5) or b
            counts[key] = (counts[key] ?: 0) + 1
        }
        val best = counts.entries.maxByOrNull { it.value } ?: return 0
        val k = best.key
        val r = ((k shr 10) and 0x1F) shl 3
        val g = ((k shr 5) and 0x1F) shl 3
        val b = (k and 0x1F) shl 3
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }

    private fun sampleSkin(pixels: IntArray, w: Int, h: Int, face: FaceRect): Int {
        var rSum = 0L; var gSum = 0L; var bSum = 0L; var n = 0
        for (y in face.top..face.bottom) for (x in face.left..face.right) {
            if (x !in 0 until w || y !in 0 until h) continue
            val c = pixels[y * w + x]
            if (!isSkinLike(c)) continue
            rSum += (c shr 16) and 0xFF
            gSum += (c shr 8) and 0xFF
            bSum += c and 0xFF
            n++
        }
        if (n == 0) return 0xFFF2C09B.toInt()
        return 0xFF000000.toInt() or
            ((rSum / n).toInt() shl 16) or ((gSum / n).toInt() shl 8) or (bSum / n).toInt()
    }

    // ========================================================================
    // Body composition
    // ========================================================================
    private fun drawChibiBody(pixels: IntArray, w: Int, h: Int,
                              headBottom: Int, torsoBottom: Int,
                              shirtColor: Int, pantsColor: Int, skinColor: Int) {
        val cx = w / 2
        // Torso: rectangle 60% of canvas width
        val torsoHalf = (w * 0.30f).toInt().coerceAtLeast(2)
        // Neck: small skin band at top of torso
        val neckTop = headBottom
        val neckBottom = (headBottom + (torsoBottom - headBottom) * 0.15f).toInt()
        for (y in neckTop..neckBottom) for (x in (cx - torsoHalf/3)..(cx + torsoHalf/3)) {
            setPx(pixels, w, h, x, y, skinColor)
        }
        // Shirt
        for (y in (neckBottom + 1)..torsoBottom) for (x in (cx - torsoHalf)..(cx + torsoHalf)) {
            setPx(pixels, w, h, x, y, shirtColor)
        }
        // Arms (just hint: skin colored bands on each side)
        for (y in (neckBottom + 2)..(neckBottom + (torsoBottom - neckBottom) * 2 / 3)) {
            for (dx in 0..1) {
                setPx(pixels, w, h, cx - torsoHalf - 1 - dx, y, skinColor)
                setPx(pixels, w, h, cx + torsoHalf + 1 + dx, y, skinColor)
            }
        }
        // Legs (two columns of pants color)
        val legHalfGap = max(1, w / 24)
        val legWidth = max(2, w / 10)
        for (y in (torsoBottom + 1) until h) {
            for (dx in 0 until legWidth) {
                setPx(pixels, w, h, cx - legHalfGap - dx, y, pantsColor)
                setPx(pixels, w, h, cx + legHalfGap + dx, y, pantsColor)
            }
        }
    }

    private fun setPx(pixels: IntArray, w: Int, h: Int, x: Int, y: Int, c: Int) {
        if (x in 0 until w && y in 0 until h) pixels[y * w + x] = c
    }

    // ========================================================================
    // Outline + nearest color
    // ========================================================================
    private fun nearestColorRgb(c: Int, palette: IntArray): Int {
        if ((c ushr 24) and 0xFF < 128) return 0
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

    /** Add a 1-px dark outline around all opaque regions. */
    private fun addOutline(pixels: IntArray, w: Int, h: Int) {
        val outline = 0xFF1A1428.toInt()
        val mask = BooleanArray(pixels.size)
        for (y in 0 until h) for (x in 0 until w) {
            val c = pixels[y * w + x]
            if ((c ushr 24) and 0xFF >= 128) continue
            // Look for opaque neighbor
            val n = (y > 0 && (pixels[(y - 1) * w + x] ushr 24) and 0xFF >= 128) ||
                    (y < h - 1 && (pixels[(y + 1) * w + x] ushr 24) and 0xFF >= 128) ||
                    (x > 0 && (pixels[y * w + (x - 1)] ushr 24) and 0xFF >= 128) ||
                    (x < w - 1 && (pixels[y * w + (x + 1)] ushr 24) and 0xFF >= 128)
            if (n) mask[y * w + x] = true
        }
        for (i in mask.indices) if (mask[i]) pixels[i] = outline
    }
}
