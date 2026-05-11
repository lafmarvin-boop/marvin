package com.pixelhero.app

/** Color-level operations that span all frames (replace, count, swap). */
object ColorOps {

    /** Replace [from] with [to] across all frames. Returns total pixels affected. */
    fun replaceColor(project: Project, from: Int, to: Int, allFrames: Boolean = true): Int {
        var total = 0
        val frames = if (allFrames) project.frames else listOf(project.currentFrame)
        frames.forEach { total += it.replaceColor(from, to) }
        return total
    }

    /** Return up to N most-used opaque colors across all frames. */
    fun mostUsedColors(project: Project, limit: Int = 32): List<Int> {
        val counts = HashMap<Int, Int>(256)
        for (f in project.frames) {
            for (c in f.pixels) {
                if ((c ushr 24) and 0xFF < 128) continue
                counts[c] = (counts[c] ?: 0) + 1
            }
        }
        return counts.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    }
}
