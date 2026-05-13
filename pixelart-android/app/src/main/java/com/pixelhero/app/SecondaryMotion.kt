package com.pixelhero.app

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Differentiates body (rigid: bones, skin, armor) from cloth (flowing: cape, hair,
 * long robe) and applies proper secondary motion:
 *  - Cloth pixels do NOT follow the legs/arms; instead they follow the body
 *    center with a 2-frame lag plus a small gravity drift.
 *  - The animation system normally moves cape pixels with whatever joint they
 *    sit near. This post-processor RE-paints those pixels at the right place
 *    after the main animation has run.
 *
 * Detection heuristics (color-based):
 *  - Cape: dominant non-skin color in the bottom half OUTSIDE the torso width
 *  - Hair: dominant non-skin color in the top 1/5 of the bounding box
 */
object SecondaryMotion {

    /**
     * Apply secondary motion to [frames]. The [source] frame is the reference
     * pose that the animation was generated from — used to identify cape/hair
     * colors that should not follow joint motion.
     *
     * @param intensity 0..1 strength of the trailing motion (1 = strong drag)
     */
    fun apply(source: Frame, frames: MutableList<Frame>, intensity: Float = 0.7f) {
        if (frames.size < 2) return
        val w = source.width; val h = source.height
        val srcPixels = if (source.layers.size > 1) source.composited() else source.pixels

        // Detect cape and hair colors from the source pose
        val capeColor = detectCapeColor(srcPixels, w, h)
        val hairColor = detectHairColor(srcPixels, w, h)
        if (capeColor == 0 && hairColor == 0) return  // nothing to do

        // Compute body centers per frame so we know how far the body has moved
        val sourceCenter = computeCenter(srcPixels, w, h)
        val centers = frames.map {
            val px = if (it.layers.size > 1) it.composited() else it.pixels
            computeCenter(px, w, h)
        }
        val velocities = computeVelocities(centers)

        frames.forEachIndexed { i, frame ->
            // Lag the velocity by 2 frames -> cloth reacts late
            val lagIdx = (i - 2 + frames.size) % frames.size
            val srcVel = velocities[lagIdx]
            val dragX = (-srcVel.first * intensity * 1.4f).roundToInt()
            val dragY = (-srcVel.second * intensity * 0.6f).roundToInt()
            val gravityY = max(1, (intensity * 1.2f).roundToInt())  // cloth falls a bit

            // Body shift for this frame (where the body moved vs source)
            val bodyShiftX = (centers[i].first - sourceCenter.first).roundToInt()
            val bodyShiftY = (centers[i].second - sourceCenter.second).roundToInt()

            if (capeColor != 0) {
                // Erase pixels that match cape color currently in this frame
                // (they're at the WRONG position because legs moved them).
                val targetPixels = frame.pixels
                for (k in targetPixels.indices) {
                    if (targetPixels[k] == capeColor) targetPixels[k] = 0
                }
                // Re-paint cape pixels at body-shift + cape lag (heavier drag, gravity)
                val capeDx = bodyShiftX + dragX
                val capeDy = bodyShiftY + dragY + gravityY
                paintColorFromSource(srcPixels, frame, w, h, capeColor, capeDx, capeDy)
            }

            if (hairColor != 0) {
                val targetPixels = frame.pixels
                for (k in targetPixels.indices) {
                    if (targetPixels[k] == hairColor) targetPixels[k] = 0
                }
                // Hair: lighter lag (half the cape drag), no gravity
                val hairDx = bodyShiftX + dragX / 2
                val hairDy = bodyShiftY + dragY / 2
                paintColorFromSource(srcPixels, frame, w, h, hairColor, hairDx, hairDy)
            }
        }
    }

    /** Paint pixels of [color] from [srcPixels] into [frame] offset by (dx, dy). */
    private fun paintColorFromSource(
        srcPixels: IntArray, frame: Frame, w: Int, h: Int,
        color: Int, dx: Int, dy: Int
    ) {
        for (y in 0 until h) for (x in 0 until w) {
            if (srcPixels[y * w + x] != color) continue
            val tx = x + dx; val ty = y + dy
            if (tx in 0 until w && ty in 0 until h) {
                frame.set(tx, ty, color)
            }
        }
    }

    /** Center of mass of opaque pixels. */
    private fun computeCenter(pixels: IntArray, w: Int, h: Int): Pair<Float, Float> {
        var sx = 0.0; var sy = 0.0; var n = 0
        for (y in 0 until h) for (x in 0 until w) {
            if ((pixels[y * w + x] ushr 24) and 0xFF >= 128) {
                sx += x; sy += y; n++
            }
        }
        if (n == 0) return 0f to 0f
        return (sx / n).toFloat() to (sy / n).toFloat()
    }

    private fun computeVelocities(centers: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (centers.size < 2) return centers.map { 0f to 0f }
        return centers.indices.map { i ->
            val prev = centers[if (i == 0) centers.size - 1 else i - 1]
            val curr = centers[i]
            (curr.first - prev.first) to (curr.second - prev.second)
        }
    }

    /**
     * Detect cape color: dominant non-skin color in the bottom half, in pixels
     * OUTSIDE the torso width. Cape pixels typically extend beyond the body.
     */
    private fun detectCapeColor(pixels: IntArray, w: Int, h: Int): Int {
        val bbox = boundingBox(pixels, w, h) ?: return 0
        val (minX, minY, maxX, maxY) = bbox
        val bbW = maxX - minX + 1; val bbH = maxY - minY + 1
        val midY = minY + bbH / 2
        val cx = (minX + maxX) / 2
        val torsoHalf = bbW / 4
        val counts = HashMap<Int, Int>()
        for (y in midY..maxY) for (x in minX..maxX) {
            // Only consider pixels OUTSIDE torso width (likely cape edges)
            if (x in (cx - torsoHalf)..(cx + torsoHalf)) continue
            val c = pixels[y * w + x]
            if ((c ushr 24) and 0xFF < 128) continue
            if (isSkinLike(c)) continue
            counts[c] = (counts[c] ?: 0) + 1
        }
        val best = counts.entries.maxByOrNull { it.value } ?: return 0
        return if (best.value >= 5) best.key else 0
    }

    /** Detect hair color: dominant non-skin in top 1/5 of bbox. */
    private fun detectHairColor(pixels: IntArray, w: Int, h: Int): Int {
        val bbox = boundingBox(pixels, w, h) ?: return 0
        val (minX, minY, maxX, maxY) = bbox
        val bbH = maxY - minY + 1
        val hairBottom = minY + bbH / 5
        val counts = HashMap<Int, Int>()
        for (y in minY..hairBottom) for (x in minX..maxX) {
            val c = pixels[y * w + x]
            if ((c ushr 24) and 0xFF < 128) continue
            if (isSkinLike(c)) continue
            counts[c] = (counts[c] ?: 0) + 1
        }
        val best = counts.entries.maxByOrNull { it.value } ?: return 0
        return if (best.value >= 3) best.key else 0
    }

    private fun boundingBox(pixels: IntArray, w: Int, h: Int): IntArray? {
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) {
            if ((pixels[y * w + x] ushr 24) and 0xFF >= 128) {
                if (x < minX) minX = x; if (y < minY) minY = y
                if (x > maxX) maxX = x; if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return null
        return intArrayOf(minX, minY, maxX, maxY)
    }

    private operator fun IntArray.component1() = this[0]
    private operator fun IntArray.component2() = this[1]
    private operator fun IntArray.component3() = this[2]
    private operator fun IntArray.component4() = this[3]

    /** Skin tone heuristic: R > G > B, R in [90..250], rg gap small. */
    private fun isSkinLike(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return r in 90..250 && r > g && g > b && (r - g) in 8..80
    }
}
