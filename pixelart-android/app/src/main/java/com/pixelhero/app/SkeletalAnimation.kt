package com.pixelhero.app

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Animation system that moves pixels based on per-joint offsets, using the
 * skeleton placed by the user. Much more natural than bbox-based heuristics.
 */
object SkeletalAnimation {

    enum class Preset(val displayName: String, val frameCount: Int) {
        WALK("Marche (8 frames)", 8),
        RUN("Course (8)", 8),
        IDLE("Idle / respiration (4)", 4),
        WAVE("Salut (4)", 4),
        ATTACK("Attaque (4)", 4),
        SLASH("Coup d'épée (6)", 6),
        JUMP("Saut (6)", 6),
        CROUCH("Accroupir (4)", 4),
        HIT("Dégât pris (4)", 4),
        SPELL_CAST("Sort magique (4)", 4);

        override fun toString() = displayName
    }

    data class Offset(val dx: Int, val dy: Int) {
        operator fun plus(o: Offset) = Offset(dx + o.dx, dy + o.dy)
    }

    /** Per-frame offset for each joint type (missing = (0,0)). */
    private typealias Pose = Map<JointType, Offset>

    fun generate(src: Frame, skin: PixelSkin, preset: Preset, locomotion: LocomotionMode = LocomotionMode.WALKING,
                 easing: Easing.Curve = Easing.Curve.SINE, secondaryMotion: Boolean = true): List<Frame> {
        val basePoses = computePoses(src, preset)
        val easedPoses = applyEasing(basePoses, easing)
        val poses = applyLocomotion(easedPoses, locomotion, src.height, preset)
        val frames = poses.map { pose -> renderPose(src, skin, pose, preset.name.lowercase()) }.toMutableList()
        if (secondaryMotion) SecondaryMotion.apply(frames, intensity = 0.7f)
        return frames
    }

    /**
     * Apply an easing curve to the per-frame poses. The original poses use sine
     * implicitly; this remaps the timing so movement feels less mechanical.
     * We interpolate by re-sampling the poses at eased phase values.
     */
    private fun applyEasing(poses: List<Pose>, curve: Easing.Curve): List<Pose> {
        if (curve == Easing.Curve.SINE) return poses  // already sine-natural
        return poses.indices.map { i ->
            val tEased = Easing.applyPeriodic(curve, i.toFloat() / poses.size)
            val srcIdx = (tEased * poses.size).toInt().coerceIn(0, poses.size - 1)
            poses[srcIdx]
        }
    }

    /**
     * Post-process the per-frame poses based on the locomotion mode.
     * - WALKING: pass-through (legs step normally)
     * - FLOATING: kill leg horizontal motion (no stepping); add a global vertical bob to all parts;
     *             keep arms/torso/head animation intact (attack, idle, etc still work)
     * - HOVER: minimal change — just a soft bob; suppress most motion
     */
    private fun applyLocomotion(poses: List<Pose>, mode: LocomotionMode, height: Int, preset: Preset): List<Pose> {
        if (mode == LocomotionMode.WALKING) return poses
        val u = max(1, height / 18)
        val frameCount = poses.size
        return poses.mapIndexed { i, pose ->
            val phase = i.toFloat() / frameCount * 2f * PI.toFloat()
            // Global float bob (slower than walking bob, more graceful)
            val bobAmp = if (mode == LocomotionMode.HOVER) u else u * 1.5f
            val bob = (sin(phase) * bobAmp).roundToInt()

            val result = mutableMapOf<JointType, Offset>()
            for (jt in JointType.values()) {
                var existing = pose[jt] ?: Offset(0, 0)
                // For FLOATING/HOVER: cancel any horizontal leg movement (no stepping)
                if (jt == JointType.HIP_L || jt == JointType.HIP_R ||
                    jt == JointType.KNEE_L || jt == JointType.KNEE_R ||
                    jt == JointType.FOOT_L || jt == JointType.FOOT_R) {
                    existing = Offset(0, existing.dy)
                }
                // For HOVER: also dampen arm motion
                if (mode == LocomotionMode.HOVER) {
                    existing = Offset((existing.dx * 0.3f).toInt(), (existing.dy * 0.3f).toInt())
                }
                // Apply global float bob to everything
                result[jt] = Offset(existing.dx, existing.dy + bob)
            }
            // Legs hang down slightly (loose) for FLOATING — feet drift lower than hips
            if (mode == LocomotionMode.FLOATING) {
                val drift = (sin(phase + PI.toFloat() / 2f) * u * 0.5f).roundToInt()
                result[JointType.FOOT_L] = (result[JointType.FOOT_L] ?: Offset(0, 0)).let { Offset(it.dx, it.dy + drift) }
                result[JointType.FOOT_R] = (result[JointType.FOOT_R] ?: Offset(0, 0)).let { Offset(it.dx, it.dy + drift) }
                result[JointType.KNEE_L] = (result[JointType.KNEE_L] ?: Offset(0, 0)).let { Offset(it.dx, it.dy + drift / 2) }
                result[JointType.KNEE_R] = (result[JointType.KNEE_R] ?: Offset(0, 0)).let { Offset(it.dx, it.dy + drift / 2) }
            }
            result
        }
    }

    private fun computePoses(src: Frame, preset: Preset): List<Pose> {
        // Unit = how big body parts move (scales with sprite height)
        val u = max(1, src.height / 16)
        return when (preset) {
            Preset.WALK -> walkPoses(u)
            Preset.RUN -> runPoses(u)
            Preset.IDLE -> idlePoses(u)
            Preset.WAVE -> wavePoses(u)
            Preset.ATTACK -> attackPoses(u)
            Preset.SLASH -> slashPoses(u)
            Preset.JUMP -> jumpPoses(u)
            Preset.CROUCH -> crouchPoses(u)
            Preset.HIT -> hitPoses(u)
            Preset.SPELL_CAST -> spellPoses(u)
        }
    }

    private fun renderPose(src: Frame, skin: PixelSkin, pose: Pose, tag: String): Frame {
        val out = Frame(src.width, src.height)
        out.tag = tag
        val srcPixels = if (src.layers.size > 1) src.composited() else src.pixels
        // Build offset array indexed by jointOrder for fast lookup
        val offsets = IntArray(skin.jointOrder.size * 2)
        for ((i, jt) in skin.jointOrder.withIndex()) {
            val o = pose[jt] ?: Offset(0, 0)
            offsets[i * 2] = o.dx
            offsets[i * 2 + 1] = o.dy
        }
        for (y in 0 until src.height) for (x in 0 until src.width) {
            val idx = y * src.width + x
            val c = srcPixels[idx]
            if ((c ushr 24) and 0xFF < 128) continue
            val jointIdx = skin.assignment[idx]
            val dx = offsets[jointIdx * 2]
            val dy = offsets[jointIdx * 2 + 1]
            val tx = x + dx; val ty = y + dy
            if (tx in 0 until src.width && ty in 0 until src.height) {
                out.set(tx, ty, c)
            }
        }
        return out
    }

    // ========================================================================
    // Pose definitions: each animation = list of joint -> offset maps
    // ========================================================================

    private fun walkPoses(u: Int): List<Pose> {
        return (0 until 8).map { f ->
            val phase = f.toFloat() / 8f * 2f * PI.toFloat()
            // Body bob: 2 cycles per walk (one per foot contact)
            val bob = ((-cos(phase * 2f) + 1f) * 0.5f * u).roundToInt()
            val legSwing = sin(phase)
            // Right leg forward when legSwing > 0
            val rLegX = (legSwing * u * 1.2f).roundToInt()
            val lLegX = (-legSwing * u * 1.2f).roundToInt()
            // Leg lift on forward step
            val rLegY = if (legSwing > 0.3f) -u else 0
            val lLegY = if (legSwing < -0.3f) -u else 0
            // Knee follows hip+ but at half amplitude
            val rKneeX = rLegX / 2; val lKneeX = lLegX / 2
            // Arms swing OPPOSITE to legs
            val rArmX = (-legSwing * u * 1.0f).roundToInt()
            val lArmX = (legSwing * u * 1.0f).roundToInt()
            mapOf(
                JointType.HEAD to Offset(0, bob),
                JointType.NECK to Offset(0, bob),
                JointType.SHOULDER_L to Offset(lArmX / 3, bob),
                JointType.SHOULDER_R to Offset(rArmX / 3, bob),
                JointType.ELBOW_L to Offset(lArmX * 2 / 3, bob),
                JointType.ELBOW_R to Offset(rArmX * 2 / 3, bob),
                JointType.HAND_L to Offset(lArmX, bob),
                JointType.HAND_R to Offset(rArmX, bob),
                JointType.HIP_CENTER to Offset(0, bob),
                JointType.HIP_L to Offset(lLegX / 4, bob),
                JointType.HIP_R to Offset(rLegX / 4, bob),
                JointType.KNEE_L to Offset(lKneeX, bob + lLegY),
                JointType.KNEE_R to Offset(rKneeX, bob + rLegY),
                JointType.FOOT_L to Offset(lLegX, lLegY),
                JointType.FOOT_R to Offset(rLegX, rLegY)
            )
        }
    }

    private fun runPoses(u: Int): List<Pose> {
        return (0 until 8).map { f ->
            val phase = f.toFloat() / 8f * 2f * PI.toFloat()
            val bob = ((-cos(phase * 2f) + 1f) * 0.8f * u).roundToInt()
            val legSwing = sin(phase)
            val rLegX = (legSwing * u * 2f).roundToInt()
            val lLegX = (-legSwing * u * 2f).roundToInt()
            val rLegY = if (legSwing > 0.2f) -u * 2 else 0
            val lLegY = if (legSwing < -0.2f) -u * 2 else 0
            val rArmX = (-legSwing * u * 1.5f).roundToInt()
            val lArmX = (legSwing * u * 1.5f).roundToInt()
            val lean = u / 2  // forward lean
            mapOf(
                JointType.HEAD to Offset(lean, bob),
                JointType.NECK to Offset(lean, bob),
                JointType.SHOULDER_L to Offset(lean + lArmX / 3, bob),
                JointType.SHOULDER_R to Offset(lean + rArmX / 3, bob),
                JointType.ELBOW_L to Offset(lean + lArmX * 2 / 3, bob - u / 2),
                JointType.ELBOW_R to Offset(lean + rArmX * 2 / 3, bob - u / 2),
                JointType.HAND_L to Offset(lean + lArmX, bob - u),
                JointType.HAND_R to Offset(lean + rArmX, bob - u),
                JointType.HIP_CENTER to Offset(lean, bob),
                JointType.HIP_L to Offset(lean, bob),
                JointType.HIP_R to Offset(lean, bob),
                JointType.KNEE_L to Offset(lLegX / 2, bob + lLegY),
                JointType.KNEE_R to Offset(rLegX / 2, bob + rLegY),
                JointType.FOOT_L to Offset(lLegX, lLegY),
                JointType.FOOT_R to Offset(rLegX, rLegY)
            )
        }
    }

    private fun idlePoses(u: Int): List<Pose> {
        return (0 until 4).map { f ->
            val phase = f.toFloat() / 4f * 2f * PI.toFloat()
            val breath = (-sin(phase) * u).roundToInt()
            val head = breath + (sin(phase) * 0.5f * u).roundToInt()
            mapOf(
                JointType.HEAD to Offset(0, head),
                JointType.NECK to Offset(0, breath),
                JointType.SHOULDER_L to Offset(0, breath),
                JointType.SHOULDER_R to Offset(0, breath),
                JointType.ELBOW_L to Offset(0, breath),
                JointType.ELBOW_R to Offset(0, breath),
                JointType.HAND_L to Offset(0, breath),
                JointType.HAND_R to Offset(0, breath),
                JointType.HIP_CENTER to Offset(0, 0),
                JointType.HIP_L to Offset(0, 0),
                JointType.HIP_R to Offset(0, 0)
            )
        }
    }

    private fun wavePoses(u: Int): List<Pose> {
        return (0 until 4).map { f ->
            val phase = f.toFloat() / 4f * 2f * PI.toFloat()
            val handX = (sin(phase) * u * 1.5f).roundToInt()
            mapOf(
                JointType.SHOULDER_R to Offset(0, -u),
                JointType.ELBOW_R to Offset(0, -u * 2),
                JointType.HAND_R to Offset(handX, -u * 3)
            )
        }
    }

    private fun attackPoses(u: Int): List<Pose> {
        return listOf(
            // Anticipation: pull back
            mapOf(
                JointType.HEAD to Offset(-u, 0), JointType.NECK to Offset(-u, 0),
                JointType.SHOULDER_R to Offset(-u, 0),
                JointType.ELBOW_R to Offset(-u * 2, 0),
                JointType.HAND_R to Offset(-u * 3, -u),
                JointType.HIP_CENTER to Offset(-u / 2, 0)
            ),
            // Strike: full extension
            mapOf(
                JointType.HEAD to Offset(u, -u), JointType.NECK to Offset(u, -u / 2),
                JointType.SHOULDER_R to Offset(u, -u / 2),
                JointType.ELBOW_R to Offset(u * 2, -u),
                JointType.HAND_R to Offset(u * 4, -u),
                JointType.HIP_CENTER to Offset(u, 0)
            ),
            // Follow-through
            mapOf(
                JointType.HEAD to Offset(u, 0), JointType.NECK to Offset(u, 0),
                JointType.SHOULDER_R to Offset(u, 0),
                JointType.ELBOW_R to Offset(u * 2, 0),
                JointType.HAND_R to Offset(u * 3, 0)
            ),
            // Recover (neutral)
            emptyMap()
        )
    }

    private fun slashPoses(u: Int): List<Pose> {
        // 6-frame downward slash: wind-up high → apex → swing → hit → low → recover
        return listOf(
            mapOf(
                JointType.HEAD to Offset(-u, -u / 2),
                JointType.SHOULDER_R to Offset(-u / 2, -u),
                JointType.ELBOW_R to Offset(0, -u * 2),
                JointType.HAND_R to Offset(u, -u * 3)
            ),
            mapOf(
                JointType.HEAD to Offset(0, -u),
                JointType.SHOULDER_R to Offset(0, -u),
                JointType.ELBOW_R to Offset(u, -u * 2),
                JointType.HAND_R to Offset(u * 2, -u * 3)
            ),
            mapOf(
                JointType.HEAD to Offset(u / 2, -u / 2),
                JointType.SHOULDER_R to Offset(u / 2, 0),
                JointType.ELBOW_R to Offset(u * 2, -u),
                JointType.HAND_R to Offset(u * 3, -u)
            ),
            mapOf(
                JointType.HEAD to Offset(u, 0),
                JointType.SHOULDER_R to Offset(u, 0),
                JointType.ELBOW_R to Offset(u * 2, 0),
                JointType.HAND_R to Offset(u * 3, u)
            ),
            mapOf(
                JointType.HEAD to Offset(u / 2, u / 2),
                JointType.SHOULDER_R to Offset(u, u / 2),
                JointType.ELBOW_R to Offset(u, u),
                JointType.HAND_R to Offset(u, u * 2)
            ),
            emptyMap()
        )
    }

    private fun jumpPoses(u: Int): List<Pose> {
        return listOf(
            // Crouch
            mapOf(
                JointType.HEAD to Offset(0, u * 2), JointType.NECK to Offset(0, u * 2),
                JointType.SHOULDER_L to Offset(0, u * 2), JointType.SHOULDER_R to Offset(0, u * 2),
                JointType.ELBOW_L to Offset(-u, u * 2), JointType.ELBOW_R to Offset(u, u * 2),
                JointType.HAND_L to Offset(-u, u * 2), JointType.HAND_R to Offset(u, u * 2),
                JointType.HIP_CENTER to Offset(0, u),
                JointType.KNEE_L to Offset(-u / 2, 0), JointType.KNEE_R to Offset(u / 2, 0)
            ),
            // Launch
            mapOf(
                JointType.HEAD to Offset(0, -u * 2), JointType.NECK to Offset(0, -u * 2),
                JointType.SHOULDER_L to Offset(-u, -u * 3), JointType.SHOULDER_R to Offset(u, -u * 3),
                JointType.ELBOW_L to Offset(-u * 2, -u * 3), JointType.ELBOW_R to Offset(u * 2, -u * 3),
                JointType.HAND_L to Offset(-u * 2, -u * 4), JointType.HAND_R to Offset(u * 2, -u * 4),
                JointType.HIP_CENTER to Offset(0, -u * 2),
                JointType.KNEE_L to Offset(0, -u * 2), JointType.KNEE_R to Offset(0, -u * 2),
                JointType.FOOT_L to Offset(0, -u), JointType.FOOT_R to Offset(0, -u)
            ),
            // Apex (legs tucked)
            mapOf(
                JointType.HEAD to Offset(0, -u * 3), JointType.NECK to Offset(0, -u * 3),
                JointType.SHOULDER_L to Offset(-u, -u * 3), JointType.SHOULDER_R to Offset(u, -u * 3),
                JointType.HAND_L to Offset(-u * 2, -u * 2), JointType.HAND_R to Offset(u * 2, -u * 2),
                JointType.HIP_CENTER to Offset(0, -u * 3),
                JointType.KNEE_L to Offset(-u / 2, -u * 2), JointType.KNEE_R to Offset(u / 2, -u * 2),
                JointType.FOOT_L to Offset(0, -u), JointType.FOOT_R to Offset(0, -u)
            ),
            // Descent
            mapOf(
                JointType.HEAD to Offset(0, -u), JointType.NECK to Offset(0, -u),
                JointType.SHOULDER_L to Offset(0, -u), JointType.SHOULDER_R to Offset(0, -u),
                JointType.HIP_CENTER to Offset(0, -u),
                JointType.KNEE_L to Offset(0, -u / 2), JointType.KNEE_R to Offset(0, -u / 2)
            ),
            // Land squash
            mapOf(
                JointType.HEAD to Offset(0, u * 2), JointType.NECK to Offset(0, u * 2),
                JointType.SHOULDER_L to Offset(0, u * 2), JointType.SHOULDER_R to Offset(0, u * 2),
                JointType.HAND_L to Offset(-u, u * 2), JointType.HAND_R to Offset(u, u * 2),
                JointType.HIP_CENTER to Offset(0, u),
                JointType.KNEE_L to Offset(0, 0), JointType.KNEE_R to Offset(0, 0)
            ),
            // Stand
            emptyMap()
        )
    }

    private fun crouchPoses(u: Int): List<Pose> {
        return listOf(u, u * 2, u * 3, u * 3).map { lower ->
            mapOf(
                JointType.HEAD to Offset(0, lower), JointType.NECK to Offset(0, lower),
                JointType.SHOULDER_L to Offset(0, lower), JointType.SHOULDER_R to Offset(0, lower),
                JointType.ELBOW_L to Offset(0, lower), JointType.ELBOW_R to Offset(0, lower),
                JointType.HAND_L to Offset(-u, lower), JointType.HAND_R to Offset(u, lower),
                JointType.HIP_CENTER to Offset(0, lower / 2),
                JointType.KNEE_L to Offset(-u, 0), JointType.KNEE_R to Offset(u, 0)
            )
        }
    }

    private fun hitPoses(u: Int): List<Pose> {
        return listOf(
            // Impact: head back, body knocked
            mapOf(
                JointType.HEAD to Offset(-u * 2, -u * 2),
                JointType.NECK to Offset(-u * 2, -u),
                JointType.SHOULDER_L to Offset(-u * 2, -u),
                JointType.SHOULDER_R to Offset(-u, -u),
                JointType.ELBOW_L to Offset(-u * 3, 0), JointType.ELBOW_R to Offset(-u, 0),
                JointType.HAND_L to Offset(-u * 3, u), JointType.HAND_R to Offset(0, u),
                JointType.HIP_CENTER to Offset(-u, 0),
                JointType.KNEE_L to Offset(-u, 0), JointType.KNEE_R to Offset(-u, 0)
            ),
            mapOf(
                JointType.HEAD to Offset(-u, -u),
                JointType.NECK to Offset(-u, 0),
                JointType.SHOULDER_L to Offset(-u, 0), JointType.SHOULDER_R to Offset(0, 0),
                JointType.HIP_CENTER to Offset(-u, 0)
            ),
            mapOf(
                JointType.HEAD to Offset(0, -u / 2)
            ),
            emptyMap()
        )
    }

    private fun spellPoses(u: Int): List<Pose> {
        return listOf(
            // Charge: arms down, body bowed
            mapOf(
                JointType.HEAD to Offset(0, u / 2), JointType.NECK to Offset(0, u / 2),
                JointType.SHOULDER_L to Offset(0, u),
                JointType.SHOULDER_R to Offset(0, u),
                JointType.ELBOW_L to Offset(-u / 2, u),
                JointType.ELBOW_R to Offset(u / 2, u),
                JointType.HAND_L to Offset(-u, u * 2),
                JointType.HAND_R to Offset(u, u * 2),
                JointType.HIP_CENTER to Offset(0, u / 2)
            ),
            // Apex: arms raised to sky
            mapOf(
                JointType.HEAD to Offset(0, -u),
                JointType.NECK to Offset(0, -u),
                JointType.SHOULDER_L to Offset(-u, -u),
                JointType.SHOULDER_R to Offset(u, -u),
                JointType.ELBOW_L to Offset(-u, -u * 2),
                JointType.ELBOW_R to Offset(u, -u * 2),
                JointType.HAND_L to Offset(-u, -u * 4),
                JointType.HAND_R to Offset(u, -u * 4)
            ),
            // Release
            mapOf(
                JointType.SHOULDER_L to Offset(-u / 2, 0),
                JointType.SHOULDER_R to Offset(u / 2, 0),
                JointType.ELBOW_L to Offset(-u, u / 2),
                JointType.ELBOW_R to Offset(u, u / 2),
                JointType.HAND_L to Offset(-u * 2, 0),
                JointType.HAND_R to Offset(u * 2, 0)
            ),
            emptyMap()
        )
    }
}
