package com.pixelhero.app

/**
 * Pose-based tweening: interpolate joint positions between two skeletons
 * (keyframe A and keyframe B), generating N intermediate frames where each
 * pixel follows its assigned joint along a linear path.
 *
 * Unlike basic pixel-level tweening (which only cross-fades colors), this
 * actually MOVES the pixels with their joints, producing real motion tweening
 * like in pro 2D animation tools.
 */
object PoseTween {

    /**
     * Generate [steps] intermediate frames by interpolating between [skeletonA] and [skeletonB].
     * The pixel binding from frame [src] is used (computed against skeletonA).
     */
    fun generate(src: Frame, skeletonA: Skeleton, skeletonB: Skeleton, steps: Int): List<Frame> {
        val skin = PixelSkin(src.width, src.height, skeletonA)
        return (1..steps).map { i ->
            val t = i.toFloat() / (steps + 1)
            val interpolated = interpolate(skeletonA, skeletonB, t)
            renderFromSkeletons(src, skin, skeletonA, interpolated)
        }
    }

    /** Linear interpolation between two skeletons. Returns a new Skeleton. */
    private fun interpolate(a: Skeleton, b: Skeleton, t: Float): Skeleton {
        val out = Skeleton()
        for (jt in JointType.values()) {
            val ja = a.get(jt); val jb = b.get(jt)
            if (ja == null && jb == null) continue
            val ax = ja?.x ?: jb!!.x
            val ay = ja?.y ?: jb!!.y
            val bx = jb?.x ?: ax
            val by = jb?.y ?: ay
            out.set(jt, ax + (bx - ax) * t, ay + (by - ay) * t)
        }
        return out
    }

    /**
     * Build a frame by moving each pixel from its position in skeletonA
     * to the corresponding position in skeletonNow (offset by the joint delta).
     */
    private fun renderFromSkeletons(src: Frame, skin: PixelSkin, skelA: Skeleton, skelNow: Skeleton): Frame {
        val out = Frame(src.width, src.height)
        out.tag = "tween"
        val srcPixels = if (src.layers.size > 1) src.composited() else src.pixels
        // For each joint, compute (dx, dy) = current - reference
        val offsetX = IntArray(skin.jointOrder.size)
        val offsetY = IntArray(skin.jointOrder.size)
        for ((i, jt) in skin.jointOrder.withIndex()) {
            val a = skelA.get(jt) ?: continue
            val b = skelNow.get(jt) ?: continue
            offsetX[i] = (b.x - a.x).toInt()
            offsetY[i] = (b.y - a.y).toInt()
        }
        for (y in 0 until src.height) for (x in 0 until src.width) {
            val idx = y * src.width + x
            val c = srcPixels[idx]
            if ((c ushr 24) and 0xFF < 128) continue
            val jointIdx = skin.assignment[idx]
            val tx = x + offsetX[jointIdx]
            val ty = y + offsetY[jointIdx]
            if (tx in 0 until src.width && ty in 0 until src.height) {
                out.set(tx, ty, c)
            }
        }
        return out
    }
}
