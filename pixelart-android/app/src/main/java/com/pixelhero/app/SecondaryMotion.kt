package com.pixelhero.app

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Adds "secondary motion" to an animation — the small lag/follow-through
 * applied to flowing parts like hair, cape, scarf, robe edges.
 *
 * Identifies "trailing" regions (defined by Y position + sometimes color)
 * and applies a delayed copy of the motion that the main body had a few
 * frames before. This is what makes character animations feel ALIVE
 * instead of robotic.
 *
 * Integration: call addSecondaryMotion(frames) AFTER an animation generator
 * has produced its frame list. The function modifies the frames in place
 * to add the trailing motion.
 */
object SecondaryMotion {

    enum class TrailingPart {
        HAIR,       // pixels above the head joint
        CAPE_BACK,  // pixels behind the torso (opposite of facing direction)
        ROBE_BOTTOM // pixels around the legs that aren't legs (long robes)
    }

    /**
     * Apply secondary motion to a list of frames. Each "trailing" region of
     * each frame gets shifted by a delayed copy of the body's motion.
     *
     * @param frames the animation frames (will be modified)
     * @param skeleton optional skeleton — if provided, used to locate trailing regions
     * @param intensity 0..1 strength of the trailing motion (1 = full lag)
     */
    fun apply(frames: MutableList<Frame>, skeleton: Skeleton? = null, intensity: Float = 1f) {
        if (frames.size < 3) return
        val w = frames[0].width; val h = frames[0].height

        // Compute frame-to-frame body offset (approximated by center of mass of opaque pixels)
        val centers = frames.map { computeCenter(it, w, h) }

        // For each frame, compute the "velocity" = where the body MOVED FROM
        val velocities = computeVelocities(centers)

        // Determine trailing regions (hair = top, cape = bottom-back, robe = around legs)
        // Method: use upper 1/4 of bbox for hair, lower portion for robe
        val unit = max(1, h / 20)

        frames.forEachIndexed { i, frame ->
            // Lag = use the velocity from a few frames ago
            val lagFrames = 2  // hair/cape lags by 2 frames
            val srcVel = velocities[(i - lagFrames + frames.size) % frames.size]
            // The trailing motion is OPPOSITE the body's recent motion (drag effect)
            val dragX = (-srcVel.first * intensity * 1.5f).roundToInt()
            val dragY = (-srcVel.second * intensity * 0.8f).roundToInt()
            // Add a slight gravity effect on hair/cape (falls slightly)
            val gravityY = (intensity * 0.5f).roundToInt().coerceAtLeast(0)
            applyDragToHair(frame, w, h, dragX, dragY + gravityY)
        }
    }

    /** Center of mass of opaque pixels in a frame. */
    private fun computeCenter(frame: Frame, w: Int, h: Int): Pair<Float, Float> {
        val pixels = if (frame.layers.size > 1) frame.composited() else frame.pixels
        var sx = 0.0; var sy = 0.0; var n = 0
        for (y in 0 until h) for (x in 0 until w) {
            if ((pixels[y * w + x] ushr 24) and 0xFF >= 128) {
                sx += x; sy += y; n++
            }
        }
        if (n == 0) return 0f to 0f
        return (sx / n).toFloat() to (sy / n).toFloat()
    }

    /** Frame-to-frame velocity (dx, dy). */
    private fun computeVelocities(centers: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (centers.size < 2) return centers.map { 0f to 0f }
        return centers.indices.map { i ->
            val prev = centers[if (i == 0) centers.size - 1 else i - 1]
            val curr = centers[i]
            (curr.first - prev.first) to (curr.second - prev.second)
        }
    }

    /**
     * Shift the top portion of the character (assumed "hair") by (dx, dy).
     * Works by:
     *  1. Identify bbox of opaque pixels
     *  2. Take the top 1/5 — this is the "hair" region
     *  3. Shift it within a copy, blend back
     */
    private fun applyDragToHair(frame: Frame, w: Int, h: Int, dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        val pixels = if (frame.layers.size > 1) frame.composited() else frame.pixels
        // Find bbox
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) {
            if ((pixels[y * w + x] ushr 24) and 0xFF >= 128) {
                if (x < minX) minX = x; if (y < minY) minY = y
                if (x > maxX) maxX = x; if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return
        val bbH = maxY - minY + 1
        // Hair region: top 1/5 of bbox
        val hairBottom = minY + bbH / 5
        // Build a snapshot of the hair pixels
        val hairPixels = mutableListOf<IntArray>()  // [x, y, color]
        for (y in minY..hairBottom) {
            for (x in minX..maxX) {
                val c = pixels[y * w + x]
                if ((c ushr 24) and 0xFF >= 128) {
                    hairPixels.add(intArrayOf(x, y, c))
                }
            }
        }
        // Clear hair area on the frame, then re-paint with offset
        for (y in minY..hairBottom) {
            for (x in minX..maxX) {
                if ((pixels[y * w + x] ushr 24) and 0xFF >= 128) {
                    frame.set(x, y, 0)
                }
            }
        }
        for (entry in hairPixels) {
            val nx = entry[0] + dx
            val ny = entry[1] + dy
            if (nx in 0 until w && ny in 0 until h) {
                frame.set(nx, ny, entry[2])
            }
        }
    }
}
