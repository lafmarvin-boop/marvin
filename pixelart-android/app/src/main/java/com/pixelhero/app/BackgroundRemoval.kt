package com.pixelhero.app

import android.graphics.Bitmap

/**
 * Background removal via corner-seeded flood fill.
 *
 * The 4 corners (+ a few edge points) are assumed to be background. Their
 * colors are sampled as "background seeds". A flood fill then spreads from
 * those seeds, marking any pixel within a color tolerance as transparent.
 *
 * Works well on:
 *  - Selfies on uniform walls
 *  - Photos with sky / studio backgrounds
 *  - Subjects visibly distinct from background
 *
 * Fails on:
 *  - Cluttered backgrounds
 *  - Subject colors matching the background
 *  - Hair / fine details (will leave halo or eat into the subject)
 *
 * Adjust [tolerance] to be more or less aggressive. Default 40 works for
 * most casual portraits.
 */
object BackgroundRemoval {

    /**
     * Return a new Bitmap where background pixels are transparent.
     * @param tolerance color distance (0-441) within which a pixel is considered background
     * @param featherEdges if true, applies a 1-pixel alpha feather at the edge to reduce hard cuts
     */
    fun removeBackground(source: Bitmap, tolerance: Int = 40, featherEdges: Boolean = true): Bitmap {
        val w = source.width
        val h = source.height
        if (w < 4 || h < 4) return source.copy(Bitmap.Config.ARGB_8888, false)

        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        // Sample background seeds from the perimeter:
        //   4 corners + midpoints of each edge = 8 seeds
        val seedIdxs = intArrayOf(
            0,
            w - 1,
            (h - 1) * w,
            (h - 1) * w + (w - 1),
            w / 2,                          // top edge midpoint
            (h - 1) * w + w / 2,            // bottom edge midpoint
            (h / 2) * w,                    // left edge midpoint
            (h / 2) * w + (w - 1)           // right edge midpoint
        )
        val seeds = seedIdxs.map { pixels[it] }

        val isBg = BooleanArray(w * h)
        val visited = BooleanArray(w * h)
        val tolSq = tolerance.toLong() * tolerance.toLong()
        val queue = ArrayDeque<Int>()

        // Seed the queue from the perimeter
        for (i in seedIdxs) {
            if (!visited[i]) { visited[i] = true; queue.addLast(i); isBg[i] = true }
        }

        while (queue.isNotEmpty()) {
            val idx = queue.removeFirst()
            val cur = pixels[idx]
            val x = idx % w
            val y = idx / w
            // Try expanding to 4 neighbors
            val nbrs = intArrayOf(
                if (x > 0) idx - 1 else -1,
                if (x < w - 1) idx + 1 else -1,
                if (y > 0) idx - w else -1,
                if (y < h - 1) idx + w else -1
            )
            for (n in nbrs) {
                if (n < 0 || visited[n]) continue
                visited[n] = true
                // Check distance to either the neighbour or any seed - whichever is smaller.
                // Using neighbour distance allows gradient backgrounds (sky) to be flood-filled.
                val nColor = pixels[n]
                val distToCur = colorDistSq(cur, nColor)
                if (distToCur <= tolSq) {
                    isBg[n] = true
                    queue.addLast(n)
                } else {
                    // Fall back: check against any seed (handles non-contiguous bg patches)
                    var matchesSeed = false
                    for (s in seeds) {
                        if (colorDistSq(nColor, s) <= tolSq) { matchesSeed = true; break }
                    }
                    if (matchesSeed) {
                        isBg[n] = true
                        queue.addLast(n)
                    }
                }
            }
        }

        // Build output: bg pixels -> transparent, others -> opaque
        val out = IntArray(w * h)
        for (i in pixels.indices) {
            out[i] = if (isBg[i]) 0 else (pixels[i] or 0xFF000000.toInt())
        }

        // Optional: feather the edge for smoother silhouette
        if (featherEdges) featherSilhouette(out, w, h)

        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun colorDistSq(a: Int, b: Int): Long {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return (dr * dr + dg * dg + db * db).toLong()
    }

    /** Reduce alpha to 50% on edge pixels (those bordering transparent ones). */
    private fun featherSilhouette(pixels: IntArray, w: Int, h: Int) {
        val snapshot = pixels.copyOf()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val idx = y * w + x
            if ((snapshot[idx] ushr 24) and 0xFF < 128) continue
            // Check if any 4-neighbour is transparent
            val n1 = (snapshot[idx - 1] ushr 24) and 0xFF
            val n2 = (snapshot[idx + 1] ushr 24) and 0xFF
            val n3 = (snapshot[idx - w] ushr 24) and 0xFF
            val n4 = (snapshot[idx + w] ushr 24) and 0xFF
            if (n1 < 128 || n2 < 128 || n3 < 128 || n4 < 128) {
                // Edge pixel: halve alpha
                val rgb = snapshot[idx] and 0x00FFFFFF
                pixels[idx] = (0x80 shl 24) or rgb
            }
        }
    }
}
