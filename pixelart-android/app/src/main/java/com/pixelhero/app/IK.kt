package com.pixelhero.app

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Two-bone inverse kinematics solver.
 *
 * Given a root joint (fixed), a mid joint (elbow / knee), and an end joint
 * (hand / foot target), this computes the mid joint position automatically
 * so that the chain reaches the target while preserving the original bone
 * lengths.
 *
 * Used in the skeleton editor: when "Auto IK" is on, moving a hand/foot
 * automatically updates the elbow/knee to keep the limb anatomically valid.
 */
object IK {

    /**
     * Solve a single 2-bone chain: place [end] at (targetX, targetY) and compute [mid].
     * @param poleSide +1 or -1 selects which side the elbow bends toward.
     */
    fun solveTwoBone(
        skeleton: Skeleton,
        root: JointType, mid: JointType, end: JointType,
        targetX: Float, targetY: Float,
        poleSide: Float = 1f
    ): Boolean {
        val rootJ = skeleton.get(root) ?: return false
        val midJ = skeleton.get(mid) ?: return false
        val endJ = skeleton.get(end) ?: return false

        // Preserve original bone lengths
        val l1 = distance(rootJ.x, rootJ.y, midJ.x, midJ.y).coerceAtLeast(0.1f)
        val l2 = distance(midJ.x, midJ.y, endJ.x, endJ.y).coerceAtLeast(0.1f)

        // Direction from root to target
        val dx = targetX - rootJ.x
        val dy = targetY - rootJ.y
        val d = sqrt(dx * dx + dy * dy)

        // Clamp the reach: can't be shorter than |l1-l2| or longer than l1+l2
        val minReach = abs(l1 - l2) + 0.01f
        val maxReach = l1 + l2 - 0.01f
        val clampedD = d.coerceIn(minReach, maxReach)

        // New end position (clamped along the same direction)
        val newEndX: Float; val newEndY: Float
        if (d > 0.001f) {
            val s = clampedD / d
            newEndX = rootJ.x + dx * s
            newEndY = rootJ.y + dy * s
        } else {
            newEndX = rootJ.x + l1 + l2; newEndY = rootJ.y
        }

        // Compute mid via law of cosines
        val a = (l1 * l1 - l2 * l2 + clampedD * clampedD) / (2f * clampedD)
        val hSq = (l1 * l1 - a * a).coerceAtLeast(0f)
        val h = sqrt(hSq)

        // Midpoint along the root-to-end segment
        val mx = rootJ.x + (newEndX - rootJ.x) * a / clampedD
        val my = rootJ.y + (newEndY - rootJ.y) * a / clampedD

        // Perpendicular direction (normalized)
        val nx = -(newEndY - rootJ.y) / clampedD
        val ny = (newEndX - rootJ.x) / clampedD

        skeleton.set(mid, mx + nx * h * poleSide, my + ny * h * poleSide)
        skeleton.set(end, newEndX, newEndY)
        return true
    }

    /**
     * Re-solve all 4 limbs based on their current end-effector positions.
     * Useful as a "snap to anatomically valid" button after the user has
     * moved hands/feet freely.
     */
    fun applyAllLimbs(skeleton: Skeleton) {
        skeleton.get(JointType.HAND_R)?.let { h ->
            solveTwoBone(skeleton, JointType.SHOULDER_R, JointType.ELBOW_R, JointType.HAND_R, h.x, h.y, poleSide = +1f)
        }
        skeleton.get(JointType.HAND_L)?.let { h ->
            solveTwoBone(skeleton, JointType.SHOULDER_L, JointType.ELBOW_L, JointType.HAND_L, h.x, h.y, poleSide = -1f)
        }
        skeleton.get(JointType.FOOT_R)?.let { f ->
            solveTwoBone(skeleton, JointType.HIP_R, JointType.KNEE_R, JointType.FOOT_R, f.x, f.y, poleSide = +1f)
        }
        skeleton.get(JointType.FOOT_L)?.let { f ->
            solveTwoBone(skeleton, JointType.HIP_L, JointType.KNEE_L, JointType.FOOT_L, f.x, f.y, poleSide = -1f)
        }
    }

    /**
     * Identify the limb chain that contains [joint], and re-solve it if [joint]
     * is an end-effector. Used during interactive editing: when the user moves
     * a hand or foot, this triggers IK to update the elbow or knee.
     */
    fun applyIfEndEffector(skeleton: Skeleton, joint: JointType): Boolean {
        return when (joint) {
            JointType.HAND_R -> skeleton.get(joint)?.let {
                solveTwoBone(skeleton, JointType.SHOULDER_R, JointType.ELBOW_R, JointType.HAND_R, it.x, it.y, +1f)
            } ?: false
            JointType.HAND_L -> skeleton.get(joint)?.let {
                solveTwoBone(skeleton, JointType.SHOULDER_L, JointType.ELBOW_L, JointType.HAND_L, it.x, it.y, -1f)
            } ?: false
            JointType.FOOT_R -> skeleton.get(joint)?.let {
                solveTwoBone(skeleton, JointType.HIP_R, JointType.KNEE_R, JointType.FOOT_R, it.x, it.y, +1f)
            } ?: false
            JointType.FOOT_L -> skeleton.get(joint)?.let {
                solveTwoBone(skeleton, JointType.HIP_L, JointType.KNEE_L, JointType.FOOT_L, it.x, it.y, -1f)
            } ?: false
            else -> false
        }
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1; val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }
}
