package com.pixelhero.app

import android.graphics.Rect

/**
 * Heuristic body-part detector for an opaque pixel-art sprite on a transparent
 * background. NOT machine learning — just bounding-box + horizontal histogram
 * splits. Works on humanoid sprites centered in the frame; degenerates on
 * non-humanoid silhouettes (slimes, dragons) where regions overlap arbitrarily.
 *
 * Used by skeletal animation / debug overlay to mark approximate head, torso,
 * arms, hips, legs. Eye detection finds the two darkest opaque pixels inside
 * the head box.
 */
object BodyDetector {

    data class Regions(
        val bbox: Rect,
        val head: Rect,
        val torso: Rect,
        val leftArm: Rect,
        val rightArm: Rect,
        val hips: Rect,
        val legs: Rect,
        val eyes: List<Pair<Int, Int>>
    )

    /** Returns null if the sprite is empty (all transparent). */
    fun detect(pixels: IntArray, w: Int, h: Int): Regions? {
        val bbox = tightBBox(pixels, w, h) ?: return null
        val bw = bbox.width()
        val bh = bbox.height()

        // Vertical splits (proportions roughly matching humanoid sprites):
        //  - head: top 28%
        //  - torso: 28-58%
        //  - hips: 58-72%
        //  - legs: 72-100%
        val headBottom = bbox.top + (bh * 0.28f).toInt()
        val torsoBottom = bbox.top + (bh * 0.58f).toInt()
        val hipsBottom = bbox.top + (bh * 0.72f).toInt()

        val head = Rect(bbox.left, bbox.top, bbox.right, headBottom)
        val hips = Rect(bbox.left, torsoBottom, bbox.right, hipsBottom)
        val legs = Rect(bbox.left, hipsBottom, bbox.right, bbox.bottom)

        // Torso + arms split: in the torso band, find the body's "central column"
        // (densest horizontal slice) and the left/right outer columns.
        val torsoBandTop = headBottom
        val torsoBandBottom = torsoBottom
        val widthHist = IntArray(bw)
        for (y in torsoBandTop until torsoBandBottom) {
            for (x in 0 until bw) {
                val idx = y * w + (bbox.left + x)
                if (idx in pixels.indices && isOpaque(pixels[idx])) widthHist[x]++
            }
        }
        // Find the densest contiguous central column — heuristic: pick the central
        // 40% of width as torso, the outer 30% on each side as arms.
        val torsoLeftOff = (bw * 0.30f).toInt()
        val torsoRightOff = (bw * 0.70f).toInt()
        val torso = Rect(bbox.left + torsoLeftOff, torsoBandTop, bbox.left + torsoRightOff, torsoBandBottom)
        val leftArm = Rect(bbox.left, torsoBandTop, bbox.left + torsoLeftOff, torsoBandBottom)
        val rightArm = Rect(bbox.left + torsoRightOff, torsoBandTop, bbox.right, torsoBandBottom)

        // Eye detection: find the 2 darkest opaque pixels in the head region,
        // then merge any duplicates within 2 pixels.
        val eyes = findDarkSpots(pixels, w, head, k = 2, minSeparation = 2)
        return Regions(bbox, head, torso, leftArm, rightArm, hips, legs, eyes)
    }

    private fun isOpaque(c: Int): Boolean = ((c ushr 24) and 0xFF) >= 128

    private fun tightBBox(pixels: IntArray, w: Int, h: Int): Rect? {
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE
        var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (isOpaque(pixels[y * w + x])) {
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return null
        return Rect(minX, minY, maxX + 1, maxY + 1)
    }

    private fun findDarkSpots(pixels: IntArray, w: Int, region: Rect, k: Int, minSeparation: Int): List<Pair<Int, Int>> {
        val candidates = ArrayList<Triple<Int, Int, Int>>()  // x, y, luminance
        for (y in region.top until region.bottom) for (x in region.left until region.right) {
            val idx = y * w + x
            if (idx !in pixels.indices) continue
            val c = pixels[idx]
            if (!isOpaque(c)) continue
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            val lum = (r * 299 + g * 587 + b * 114) / 1000
            if (lum < 80) candidates.add(Triple(x, y, lum))
        }
        candidates.sortBy { it.third }
        val picked = ArrayList<Pair<Int, Int>>()
        for ((x, y, _) in candidates) {
            if (picked.size >= k) break
            if (picked.all { (px, py) -> kotlin.math.abs(px - x) + kotlin.math.abs(py - y) >= minSeparation }) {
                picked.add(x to y)
            }
        }
        return picked
    }
}
