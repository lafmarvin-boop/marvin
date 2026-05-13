package com.pixelhero.app

import kotlin.math.roundToInt

/**
 * Heuristic transformations between character views (front, back, profile, 3/4).
 * Produces a starting frame the artist refines — NOT a perfect render.
 *
 * The transformations apply geometric warps + simple color heuristics:
 *   - "to back": flip horizontally + replace face-skin pixels in head with hair color
 *   - "to profile": squash horizontally toward 50% width
 *   - "from profile": stretch back
 *   - "to 3/4": squash to 75% width
 */
object ViewTransform {

    enum class View(val displayName: String) {
        FRONT("Face"), BACK("Dos"),
        SIDE_LEFT("Profil gauche"), SIDE_RIGHT("Profil droit"),
        THREE_QUARTER_LEFT("3/4 gauche"), THREE_QUARTER_RIGHT("3/4 droit")
    }

    data class Transform(val from: View, val to: View) {
        val displayName: String get() = "${from.displayName}  →  ${to.displayName}"
    }

    /** All 30 from/to combinations (skipping identity). */
    fun allTransforms(): List<Transform> = View.values().flatMap { from ->
        View.values().filter { it != from }.map { to -> Transform(from, to) }
    }

    fun apply(frame: Frame, t: Transform): Frame {
        // Apply 2 steps: orient + scale
        var work = frame
        // Step 1: handle horizontal flip if direction changes (left ↔ right)
        if (orientation(t.from) != orientation(t.to)) {
            work = work.flipHorizontal()
        }
        // Step 2: scale width (front/back = full, 3/4 = 0.75, profile = 0.5)
        val targetWidthRatio = widthRatio(t.to) / widthRatio(t.from)
        if (targetWidthRatio != 1f) {
            work = scaleWidth(work, targetWidthRatio)
        }
        // Step 3: handle face/back transition (replace face features when going to BACK)
        if (t.from != View.BACK && t.to == View.BACK) {
            work = removeFaceFeatures(work)
        }
        if (t.from == View.BACK && t.to != View.BACK) {
            // Going from back to a non-back view; we have no original face details so
            // leave the head as-is (user will need to redraw face)
        }
        return work
    }

    /** -1 = facing left, 0 = facing camera/away, +1 = facing right */
    private fun orientation(view: View): Int = when (view) {
        View.FRONT, View.BACK -> 0
        View.SIDE_LEFT, View.THREE_QUARTER_LEFT -> -1
        View.SIDE_RIGHT, View.THREE_QUARTER_RIGHT -> 1
    }

    /** Visible width ratio. Profile is narrower than front/back. */
    private fun widthRatio(view: View): Float = when (view) {
        View.FRONT, View.BACK -> 1.0f
        View.THREE_QUARTER_LEFT, View.THREE_QUARTER_RIGHT -> 0.75f
        View.SIDE_LEFT, View.SIDE_RIGHT -> 0.55f
    }

    /** Scale the frame horizontally by [ratio], centered. */
    private fun scaleWidth(frame: Frame, ratio: Float): Frame {
        val w = frame.width; val h = frame.height
        val out = Frame(w, h)
        out.tag = frame.tag; out.delayMs = frame.delayMs
        val srcPixels = if (frame.layers.size > 1) frame.composited() else frame.pixels
        // Find horizontal bounding box of opaque pixels
        var minX = w; var maxX = -1
        for (y in 0 until h) for (x in 0 until w) {
            if ((srcPixels[y * w + x] ushr 24) and 0xFF >= 128) {
                if (x < minX) minX = x; if (x > maxX) maxX = x
            }
        }
        if (maxX < 0) return frame.copy()
        val bbW = maxX - minX + 1
        val newBbW = (bbW * ratio).roundToInt().coerceAtLeast(1)
        val cx = (minX + maxX) / 2f
        for (y in 0 until h) for (x in 0 until w) {
            val srcC = srcPixels[y * w + x]
            if ((srcC ushr 24) and 0xFF < 128) continue
            // Compute new X relative to center, scaled
            val newX = (cx + (x - cx) * ratio).roundToInt()
            if (newX in 0 until w) {
                // If we're shrinking, multiple source pixels may map to the same destination.
                // We just overwrite (last one wins, which is OK for shrink).
                // If we're stretching, leave a gap which we'll fill via simple horizontal blur below.
                out.set(newX, y, srcC)
            }
        }
        // If stretching (ratio > 1), fill gaps by horizontal nearest-fill
        if (ratio > 1.05f) {
            val pixels = out.pixels
            for (y in 0 until h) {
                var lastOpaque = 0
                var lastX = -10
                for (x in 0 until w) {
                    val c = pixels[y * w + x]
                    if ((c ushr 24) and 0xFF >= 128) {
                        // Fill the gap from lastX+1 to x-1 with lastOpaque if gap is small
                        if (lastX in 0 until x && x - lastX in 2..3) {
                            for (xx in lastX + 1 until x) pixels[y * w + xx] = lastOpaque
                        }
                        lastOpaque = c
                        lastX = x
                    }
                }
            }
        }
        return out
    }

    /**
     * Replace "face" features in the head region with hair-like color.
     * Heuristic: find skin-colored pixels (light, warm) in the top 1/3 of opaque area
     * and replace them with the surrounding darker color (assumed to be hair/outline).
     */
    private fun removeFaceFeatures(frame: Frame): Frame {
        val w = frame.width; val h = frame.height
        val out = frame.copy()
        val srcPixels = if (frame.layers.size > 1) frame.composited() else frame.pixels
        // Find bounding box and head region (top 1/3)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) {
            if ((srcPixels[y * w + x] ushr 24) and 0xFF >= 128) {
                if (x < minX) minX = x; if (y < minY) minY = y
                if (x > maxX) maxX = x; if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return out
        val headBottom = minY + (maxY - minY + 1) / 3
        // Find most common color in the head region that is NOT skin-like
        // (we'll use that as the "hair color" replacement)
        val headColors = HashMap<Int, Int>()
        for (y in minY..headBottom) for (x in minX..maxX) {
            val c = srcPixels[y * w + x]
            if ((c ushr 24) and 0xFF < 128) continue
            if (isSkinLike(c)) continue
            headColors[c] = (headColors[c] ?: 0) + 1
        }
        val hairColor = headColors.entries.maxByOrNull { it.value }?.key ?: 0xFF3A2A1A.toInt()
        // Replace skin-colored pixels in head with hairColor
        for (y in minY..headBottom) for (x in minX..maxX) {
            val c = srcPixels[y * w + x]
            if ((c ushr 24) and 0xFF < 128) continue
            if (isSkinLike(c)) {
                out.set(x, y, hairColor)
            }
        }
        return out
    }

    /** Heuristic skin tone detection: R > G > B, R high, not too dark/light. */
    private fun isSkinLike(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        if (r < 90 || r > 250) return false
        if (r <= g || g <= b) return false
        val rg = r - g; val gb = g - b
        return rg in 8..80 && gb in 4..60
    }
}
