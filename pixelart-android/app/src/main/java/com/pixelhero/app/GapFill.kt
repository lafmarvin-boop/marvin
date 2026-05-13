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

    /** Fill 1-pixel-wide gaps surrounded by opaque pixels. Multiple passes for thicker gaps. */
    fun apply(frame: Frame, passes: Int = 2) {
        val w = frame.width; val h = frame.height
        repeat(passes) {
            val snapshot = frame.pixels.copyOf()
            for (y in 0 until h) for (x in 0 until w) {
                val current = snapshot[y * w + x]
                if ((current ushr 24) and 0xFF >= 128) continue
                // Collect 4-neighbour opaque colours
                val ns = mutableListOf<Int>()
                if (y > 0) snapshot[(y - 1) * w + x].let { if ((it ushr 24) and 0xFF >= 128) ns.add(it) }
                if (y < h - 1) snapshot[(y + 1) * w + x].let { if ((it ushr 24) and 0xFF >= 128) ns.add(it) }
                if (x > 0) snapshot[y * w + (x - 1)].let { if ((it ushr 24) and 0xFF >= 128) ns.add(it) }
                if (x < w - 1) snapshot[y * w + (x + 1)].let { if ((it ushr 24) and 0xFF >= 128) ns.add(it) }
                // Fill only if at least 3 of 4 neighbours are opaque (it's a real gap)
                if (ns.size >= 3) {
                    // Mode color (most common among neighbours)
                    val mode = ns.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: continue
                    frame.set(x, y, mode)
                }
            }
        }
    }
}
