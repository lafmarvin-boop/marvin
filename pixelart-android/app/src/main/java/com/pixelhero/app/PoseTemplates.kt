package com.pixelhero.app

import kotlin.math.max
import kotlin.math.min

/**
 * Pre-made pose templates that the user can load as a starting frame, then
 * paint over. Each template is rendered into the project's resolution using
 * a small ASCII-art-style sprite that gets scaled.
 *
 * Color legend (in ASCII templates):
 *   o = outline (dark gray for sketching)
 *   . = transparent
 *   + = joint marker (slightly brighter outline)
 */
object PoseTemplates {

    enum class Pose(val displayName: String) {
        HUMANOID_FRONT("Humanoïde face"),
        HUMANOID_SIDE("Humanoïde profil"),
        HUMANOID_BACK("Humanoïde dos"),
        DRAGON_SIDE("Dragon profil"),
        QUADRUPED("Quadrupède (chien/chat)"),
        BIRD("Oiseau"),
        FISH("Poisson");

        override fun toString() = displayName
    }

    private const val OUTLINE = 0xFF555566.toInt()
    private const val JOINT = 0xFF8888AA.toInt()

    /** Render [pose] into a (w,h) pixel array. Result has outline pixels only. */
    fun render(pose: Pose, w: Int, h: Int): IntArray {
        val sprite = when (pose) {
            Pose.HUMANOID_FRONT -> HUMANOID_FRONT_ART
            Pose.HUMANOID_SIDE -> HUMANOID_SIDE_ART
            Pose.HUMANOID_BACK -> HUMANOID_BACK_ART
            Pose.DRAGON_SIDE -> DRAGON_SIDE_ART
            Pose.QUADRUPED -> QUADRUPED_ART
            Pose.BIRD -> BIRD_ART
            Pose.FISH -> FISH_ART
        }
        return renderSprite(sprite, w, h)
    }

    /** Render an ASCII sprite array to a pixel canvas, fit + centered. */
    private fun renderSprite(art: Array<String>, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        val sH = art.size
        val sW = art.maxOf { it.length }
        // Fit while preserving aspect
        val scale = min(w.toFloat() / sW, h.toFloat() / sH)
        val dw = (sW * scale).toInt().coerceAtLeast(1)
        val dh = (sH * scale).toInt().coerceAtLeast(1)
        val dx = (w - dw) / 2
        val dy = (h - dh) / 2
        for (y in 0 until dh) for (x in 0 until dw) {
            val sx = (x.toFloat() / dw * sW).toInt().coerceIn(0, sW - 1)
            val sy = (y.toFloat() / dh * sH).toInt().coerceIn(0, sH - 1)
            val row = art[sy]
            val ch = if (sx < row.length) row[sx] else '.'
            val color = when (ch) {
                'o' -> OUTLINE
                '+' -> JOINT
                else -> 0
            }
            if (color != 0) {
                val px = dx + x; val py = dy + y
                if (px in 0 until w && py in 0 until h) out[py * w + px] = color
            }
        }
        return out
    }

    // ---- ASCII art sprites (any size, will be scaled to canvas) ----
    private val HUMANOID_FRONT_ART = arrayOf(
        ".....oooo.....",
        "....o....o....",
        "....o.++.o....",
        "....o....o....",
        ".....oooo.....",
        "......++......",
        "...oo.oo.oo...",
        "..o.o.oo.o.o..",
        "..o..o++o..o..",
        "..o..oooo..o..",
        ".....o..o.....",
        "....+o..o+....",
        "....o....o....",
        "....o....o....",
        "....o....o....",
        "....+o..o+....",
        ".....o..o....."
    )

    private val HUMANOID_SIDE_ART = arrayOf(
        ".....oooo....",
        "....o....o...",
        "....o.++.o...",
        "....o....o...",
        ".....oooo....",
        "......o......",
        ".....oo......",
        "....o++o.....",
        "....o..o.....",
        "....o..oo....",
        "....o..o.....",
        ".....++......",
        "....o..o.....",
        "....o..o.....",
        "....o..o.....",
        "....o..o.....",
        "....+..+....."
    )

    private val HUMANOID_BACK_ART = arrayOf(
        ".....oooo.....",
        "....o....o....",
        "....o....o....",
        "....o....o....",
        ".....oooo.....",
        "......++......",
        "...oo.oo.oo...",
        "..o.o.oo.o.o..",
        "..o..oooo..o..",
        "..o..oooo..o..",
        ".....o..o.....",
        "....+o..o+....",
        "....o....o....",
        "....o....o....",
        "....o....o....",
        "....+o..o+....",
        ".....o..o....."
    )

    private val DRAGON_SIDE_ART = arrayOf(
        "................",
        "..o.............",
        ".oo......oooo...",
        "ooo....oo+.o.o..",
        "ooooooooo..o.o..",
        ".o.o..oo+oo+o...",
        ".+.+oooo.ooo....",
        "..oooo..oo......",
        ".o.....o..o.....",
        ".+....+..+......",
        "................"
    )

    private val QUADRUPED_ART = arrayOf(
        "..........oo....",
        ".........o++o...",
        "........o....o..",
        ".....oooo....o..",
        "....oo+++ooo+o..",
        "....o.....o..o..",
        "....o.....o.....",
        "....o.....o.....",
        "....+.....+.....",
        "....o.....o....."
    )

    private val BIRD_ART = arrayOf(
        ".......oo.......",
        "......o++o......",
        ".....o+...o.....",
        "....o+..o.o.....",
        "...oo..ooo......",
        "..o.+oo+oo......",
        "..o..oo+.o......",
        "...oooo+oo......",
        ".....+o.........",
        ".....o.o........"
    )

    private val FISH_ART = arrayOf(
        ".......oooo.....",
        "....ooo....oo...",
        "..oo.........oo.",
        "oo.....+.......oo",
        ".oo..........oo.",
        "...ooo.....ooo..",
        ".....oooooo....."
    )
}
