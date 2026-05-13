package com.pixelhero.app

/**
 * Post-process step for forward-warp animation rendering.
 *
 * When pixels are moved by different offsets (e.g., legs shift one way, torso
 * another), small transparent gaps appear at body part boundaries. This pass
 * fills those gaps using the most common opaque neighbor color, eliminating
 * visible seams without affecting the intended motion.
 */
object GapFill {

    /**
     * Fill ONLY single-pixel holes completely surrounded by opaque pixels of
     * the SAME color. Avoids closing intentional details (eyes, mouth, gaps
     * between fingers) which typically don't have 4 same-color neighbours.
     */
    fun apply(frame: Frame, passes: Int = 1) {
        val w = frame.width; val h = frame.height
        repeat(passes) {
            val snapshot = frame.pixels.copyOf()
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val current = snapshot[y * w + x]
                if ((current ushr 24) and 0xFF >= 128) continue
                val n1 = snapshot[(y - 1) * w + x]
                val n2 = snapshot[(y + 1) * w + x]
                val n3 = snapshot[y * w + (x - 1)]
                val n4 = snapshot[y * w + (x + 1)]
                val o1 = (n1 ushr 24) and 0xFF >= 128
                val o2 = (n2 ushr 24) and 0xFF >= 128
                val o3 = (n3 ushr 24) and 0xFF >= 128
                val o4 = (n4 ushr 24) and 0xFF >= 128
                // All 4 must be opaque (truly enclosed pixel)
                if (!o1 || !o2 || !o3 || !o4) continue
                // And the 4 colors must agree on the dominant: require at least
                // 3 of 4 same color before filling. This avoids erasing eye whites
                // or mouth gaps that border multiple different colors.
                val sameCounts = HashMap<Int, Int>()
                sameCounts[n1] = (sameCounts[n1] ?: 0) + 1
                sameCounts[n2] = (sameCounts[n2] ?: 0) + 1
                sameCounts[n3] = (sameCounts[n3] ?: 0) + 1
                sameCounts[n4] = (sameCounts[n4] ?: 0) + 1
                val best = sameCounts.entries.maxByOrNull { it.value } ?: continue
                if (best.value >= 3) frame.set(x, y, best.key)
            }
        }
    }
}
