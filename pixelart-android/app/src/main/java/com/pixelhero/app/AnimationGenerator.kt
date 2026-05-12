package com.pixelhero.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pro-grade animation generator. Each preset uses sine-based timing
 * and animates 4 body parts (head, torso, leftLeg, rightLeg) with counter-phase
 * motion and squash-and-stretch deformation.
 *
 * The resulting frames feel natural — closer to real game character animation
 * than the previous rigid translations.
 */
object AnimationGenerator {

    enum class Preset(val displayName: String, val frameCount: Int) {
        WALK("Marche ← / → (4 frames)", 4),
        WALK8("Marche fluide ← / → (8)", 8),
        WALK_DOWN("Marche ↓ face (4)", 4),
        WALK_UP("Marche ↑ dos (4)", 4),
        RUN("Course (6)", 6),
        IDLE("Idle / respiration (4)", 4),
        ATTACK("Attaque / coup (4)", 4),
        SLASH("Coup d'épée (6)", 6),
        SPELL_CAST("Sort de magie (4)", 4),
        HIT("Dégât pris / impact (4)", 4),
        DEATH("Mort / chute KO (4)", 4),
        FALL("Chute libre (4)", 4),
        CLIMB("Escalade (4)", 4),
        DODGE("Esquive (4)", 4),
        JUMP("Saut (6)", 6),
        CROUCH("Accroupir (4)", 4),
        DEFENSE("Défense / tremblement (4)", 4),
        TURN("Rotation gauche/droite (4)", 4),
        BOB("Flottement (4)", 4),
        WAVE("Salut (4)", 4);

        override fun toString() = displayName
    }

    fun generate(base: Frame, preset: Preset): List<Frame> {
        val bbox = computeBoundingBox(base) ?: return List(preset.frameCount) { base.copy() }
        return when (preset) {
            Preset.WALK -> walk(base, bbox, 4)
            Preset.WALK8 -> walk(base, bbox, 8)
            Preset.WALK_DOWN -> walkDown(base, bbox)
            Preset.WALK_UP -> walkUp(base, bbox)
            Preset.RUN -> run(base, bbox)
            Preset.IDLE -> idle(base, bbox)
            Preset.ATTACK -> attack(base, bbox)
            Preset.SLASH -> slash(base, bbox)
            Preset.SPELL_CAST -> spellCast(base, bbox)
            Preset.HIT -> hit(base, bbox)
            Preset.DEATH -> death(base, bbox)
            Preset.FALL -> fall(base, bbox)
            Preset.CLIMB -> climb(base, bbox)
            Preset.DODGE -> dodge(base, bbox)
            Preset.JUMP -> jump(base, bbox)
            Preset.CROUCH -> crouch(base, bbox)
            Preset.DEFENSE -> defense(base, bbox)
            Preset.TURN -> turn(base, bbox)
            Preset.BOB -> bob(base, bbox)
            Preset.WAVE -> wave(base, bbox)
        }
    }

    // ========================================================================
    // Body part regions
    // ========================================================================
    data class BBox(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        val width get() = x1 - x0 + 1
        val height get() = y1 - y0 + 1
        val cx get() = (x0 + x1) / 2
        val cy get() = (y0 + y1) / 2
        val headBottom get() = y0 + height / 4
        val bodyBottom get() = y0 + (height * 5) / 8
        val hipY get() = y0 + (height * 5) / 8
        val legsBottom get() = y1
        // For arm columns (outer 1/4 of width on each side)
        val torsoLeft get() = x0 + width / 4
        val torsoRight get() = x1 - width / 4
    }

    private fun computeBoundingBox(frame: Frame): BBox? {
        var minX = frame.width; var minY = frame.height
        var maxX = -1; var maxY = -1
        val pixels = if (frame.layers.size > 1) frame.composited() else frame.pixels
        for (y in 0 until frame.height) for (x in 0 until frame.width) {
            if ((pixels[y * frame.width + x] ushr 24) and 0xFF >= 128) {
                if (x < minX) minX = x; if (y < minY) minY = y
                if (x > maxX) maxX = x; if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return null
        return BBox(minX, minY, maxX, maxY)
    }

    data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** Body parts derived from BBox. Each rect is inclusive on all sides. */
    private fun headRegion(b: BBox) = Rect(b.x0, b.y0, b.x1, b.headBottom)
    /** Inner torso (excludes the arm columns to avoid double-paint). */
    private fun torsoRegion(b: BBox) = Rect(b.torsoLeft, b.headBottom + 1, b.torsoRight, b.bodyBottom)
    private fun leftLegRegion(b: BBox) = Rect(b.x0, b.bodyBottom + 1, b.cx, b.legsBottom)
    private fun rightLegRegion(b: BBox) = Rect(b.cx + 1, b.bodyBottom + 1, b.x1, b.legsBottom)
    /** Arms = outer columns of the torso vertical band. */
    private fun leftArmRegion(b: BBox) = Rect(b.x0, b.headBottom + 1, b.torsoLeft - 1, b.bodyBottom)
    private fun rightArmRegion(b: BBox) = Rect(b.torsoRight + 1, b.headBottom + 1, b.x1, b.bodyBottom)
    private fun fullRegion(b: BBox) = Rect(b.x0, b.y0, b.x1, b.y1)

    // ========================================================================
    // Frame composer: build a frame by copying source rects with offsets
    // ========================================================================
    private data class PartOffset(val region: Rect, val dx: Int, val dy: Int)

    /**
     * Build a frame from the source by copying each part region to the output
     * with its own (dx, dy) offset. The order in the list defines paint order:
     * later parts overwrite earlier ones (legs after torso typical).
     */
    private fun buildFrame(src: Frame, parts: List<PartOffset>): Frame {
        val out = Frame(src.width, src.height)
        out.tag = src.tag
        val srcPixels = if (src.layers.size > 1) src.composited() else src.pixels
        for (po in parts) {
            for (y in po.region.top..po.region.bottom) {
                for (x in po.region.left..po.region.right) {
                    if (x !in 0 until src.width || y !in 0 until src.height) continue
                    val c = srcPixels[y * src.width + x]
                    if ((c ushr 24) and 0xFF < 128) continue
                    val tx = x + po.dx; val ty = y + po.dy
                    if (tx in 0 until out.width && ty in 0 until out.height) {
                        out.set(tx, ty, c)
                    }
                }
            }
        }
        return out
    }

    // ========================================================================
    // ANIMATION PRESETS
    // ========================================================================

    /** Side-view walk cycle with proper limb alternation. */
    private fun walk(base: Frame, b: BBox, frameCount: Int): List<Frame> {
        val unit = max(1, b.height / 16)
        return (0 until frameCount).map { f ->
            val phase = f.toFloat() / frameCount * 2f * PI.toFloat()
            // Body bob: drops twice per cycle (each foot contact)
            val bodyBob = ((-cos(phase * 2f) + 1f) * 0.5f * unit).roundToInt()
            // Head bobs slightly more than body
            val headBob = bodyBob + (sin(phase * 2f) * 0.5f * unit).roundToInt()
            // Leg phase: legs alternate, one forward when the other is back
            val legSwing = sin(phase)
            val rightLegFwd = (legSwing * unit).roundToInt()
            val leftLegFwd = (-legSwing * unit).roundToInt()
            // Each leg lifts when stepping forward
            val rightLegLift = if (legSwing > 0.3f) -unit else 0
            val leftLegLift = if (legSwing < -0.3f) -unit else 0
            // Arms swing OPPOSITE to legs (counter-phase, real walking)
            val rightArmFwd = (-legSwing * unit).roundToInt()
            val leftArmFwd = (legSwing * unit).roundToInt()
            // Torso has slight forward lean
            val torsoLean = 0
            val out = buildFrame(base, listOf(
                PartOffset(torsoRegion(b), torsoLean, bodyBob),
                PartOffset(leftArmRegion(b), leftArmFwd, bodyBob),
                PartOffset(rightArmRegion(b), rightArmFwd, bodyBob),
                PartOffset(headRegion(b), 0, headBob),
                PartOffset(leftLegRegion(b), leftLegFwd, leftLegLift),
                PartOffset(rightLegRegion(b), rightLegFwd, rightLegLift)
            ))
            out.tag = "walk"; out
        }
    }

    /** Walk facing the camera (down). Sway side-to-side + alternating feet. */
    private fun walkDown(base: Frame, b: BBox): List<Frame> {
        val unit = max(1, b.height / 16)
        return (0 until 4).map { f ->
            val phase = f.toFloat() / 4f * 2f * PI.toFloat()
            val bodySway = (sin(phase) * unit).roundToInt()
            val bodyBob = ((-cos(phase * 2f) + 1f) * 0.5f * unit).roundToInt()
            // Legs alternate up-down
            val legSwing = sin(phase)
            val leftLegLift = if (legSwing > 0.3f) -unit else 0
            val rightLegLift = if (legSwing < -0.3f) -unit else 0
            val out = buildFrame(base, listOf(
                PartOffset(torsoRegion(b), bodySway, bodyBob),
                PartOffset(leftArmRegion(b), bodySway, bodyBob),
                PartOffset(rightArmRegion(b), bodySway, bodyBob),
                PartOffset(headRegion(b), bodySway, bodyBob),
                PartOffset(leftLegRegion(b), 0, leftLegLift),
                PartOffset(rightLegRegion(b), 0, rightLegLift)
            ))
            out.tag = "walk_down"; out
        }
    }

    /** Walk facing away (up). Subtler swing — arms hidden behind body. */
    private fun walkUp(base: Frame, b: BBox): List<Frame> {
        val unit = max(1, b.height / 16)
        return (0 until 4).map { f ->
            val phase = f.toFloat() / 4f * 2f * PI.toFloat()
            val bodySway = (sin(phase) * unit).roundToInt()
            val bodyBob = ((-cos(phase * 2f) + 1f) * 0.5f * unit).roundToInt()
            val legSwing = sin(phase)
            val leftLegLift = if (legSwing > 0.3f) -unit else 0
            val rightLegLift = if (legSwing < -0.3f) -unit else 0
            val out = buildFrame(base, listOf(
                PartOffset(torsoRegion(b), bodySway, bodyBob),
                PartOffset(leftArmRegion(b), bodySway, bodyBob),
                PartOffset(rightArmRegion(b), bodySway, bodyBob),
                PartOffset(headRegion(b), bodySway, bodyBob),
                PartOffset(leftLegRegion(b), 0, leftLegLift),
                PartOffset(rightLegRegion(b), 0, rightLegLift)
            ))
            out.tag = "walk_up"; out
        }
    }

    /** Run: like walk but bigger amplitudes + forward body lean. */
    private fun run(base: Frame, b: BBox): List<Frame> {
        val unit = max(1, b.height / 12)
        return (0 until 6).map { f ->
            val phase = f.toFloat() / 6f * 2f * PI.toFloat()
            val bodyBob = ((-cos(phase * 2f) + 1f) * 0.7f * unit).roundToInt()
            val legSwing = sin(phase)
            val rightLegFwd = (legSwing * unit * 1.5f).roundToInt()
            val leftLegFwd = (-legSwing * unit * 1.5f).roundToInt()
            val rightLegLift = if (legSwing > 0.2f) -unit * 2 else 0
            val leftLegLift = if (legSwing < -0.2f) -unit * 2 else 0
            val rightArmFwd = (-legSwing * unit * 1.2f).roundToInt()
            val leftArmFwd = (legSwing * unit * 1.2f).roundToInt()
            // Forward lean: torso shifts +1px
            val torsoLean = 1
            val out = buildFrame(base, listOf(
                PartOffset(torsoRegion(b), torsoLean, bodyBob),
                PartOffset(leftArmRegion(b), leftArmFwd + torsoLean, bodyBob),
                PartOffset(rightArmRegion(b), rightArmFwd + torsoLean, bodyBob),
                PartOffset(headRegion(b), torsoLean, bodyBob),
                PartOffset(leftLegRegion(b), leftLegFwd, leftLegLift),
                PartOffset(rightLegRegion(b), rightLegFwd, rightLegLift)
            ))
            out.tag = "run"; out
        }
    }

    /** Idle: gentle breathing with shoulder rise. */
    private fun idle(base: Frame, b: BBox): List<Frame> {
        val unit = max(1, b.height / 20)
        return (0 until 4).map { f ->
            val phase = f.toFloat() / 4f * 2f * PI.toFloat()
            val breath = (-sin(phase) * unit).roundToInt() // chest rises
            val headBob = (sin(phase) * 0.5f * unit).roundToInt()
            val out = buildFrame(base, listOf(
                PartOffset(torsoRegion(b), 0, breath),
                PartOffset(leftArmRegion(b), 0, breath),
                PartOffset(rightArmRegion(b), 0, breath),
                PartOffset(headRegion(b), 0, headBob + breath),
                PartOffset(leftLegRegion(b), 0, 0),
                PartOffset(rightLegRegion(b), 0, 0)
            ))
            out.tag = "idle"; out
        }
    }

    /** Attack: anticipation → strike → recovery → return. Right arm extends. */
    private fun attack(base: Frame, b: BBox): List<Frame> {
        val unit = max(1, b.height / 12)
        val anticipation = buildFrame(base, listOf(
            PartOffset(torsoRegion(b), -unit, 0),
            PartOffset(leftArmRegion(b), -unit, 0),
            PartOffset(rightArmRegion(b), -unit * 2, 0),
            PartOffset(headRegion(b), -unit, 0),
            PartOffset(leftLegRegion(b), 0, 0),
            PartOffset(rightLegRegion(b), 0, 0)
        )).also { it.tag = "attack" }
        val strike = buildFrame(base, listOf(
            PartOffset(torsoRegion(b), unit, -unit),
            PartOffset(leftArmRegion(b), 0, 0),
            PartOffset(rightArmRegion(b), unit * 3, -unit),
            PartOffset(headRegion(b), unit, -unit),
            PartOffset(leftLegRegion(b), 0, 0),
            PartOffset(rightLegRegion(b), 0, 0)
        )).also { it.tag = "attack" }
        val followThrough = buildFrame(base, listOf(
            PartOffset(torsoRegion(b), unit, 0),
            PartOffset(leftArmRegion(b), unit, 0),
            PartOffset(rightArmRegion(b), unit * 2, 0),
            PartOffset(headRegion(b), unit, 0),
            PartOffset(leftLegRegion(b), 0, 0),
            PartOffset(rightLegRegion(b), 0, 0)
        )).also { it.tag = "attack" }
        val recover = buildFrame(base, listOf(
            PartOffset(fullRegion(b), 0, 0)
        )).also { it.tag = "attack" }
        return listOf(anticipation, strike, followThrough, recover)
    }

    /** Sword slash: 6-frame full arc. */
    private fun slash(base: Frame, b: BBox): List<Frame> {
        val unit = max(1, b.height / 14)
        return listOf(
            // Wind up high
            buildFrame(base, listOf(
                PartOffset(torsoRegion(b), -unit, -unit / 2),
                PartOffset(leftArmRegion(b), -unit, 0),
                PartOffset(rightArmRegion(b), -unit, -unit * 2),
                PartOffset(headRegion(b), -unit, -unit / 2),
                PartOffset(leftLegRegion(b), 0, 0),
                PartOffset(rightLegRegion(b), 0, 0)
            )).also { it.tag = "slash" },
            // Apex
            buildFrame(base, listOf(
                PartOffset(torsoRegion(b), 0, -unit),
                PartOffset(leftArmRegion(b), 0, -unit),
                PartOffset(rightArmRegion(b), unit, -unit * 2),
                PartOffset(headRegion(b), 0, -unit),
                PartOffset(leftLegRegion(b), 0, 0),
                PartOffset(rightLegRegion(b), 0, 0)
            )).also { it.tag = "slash" },
            // Mid swing
            buildFrame(base, listOf(
                PartOffset(torsoRegion(b), unit, -unit / 2),
                PartOffset(leftArmRegion(b), unit, 0),
                PartOffset(rightArmRegion(b), unit * 2, -unit / 2),
                PartOffset(headRegion(b), unit, -unit / 2),
                PartOffset(leftLegRegion(b), 0, 0),
                PartOffset(rightLegRegion(b), 0, 0)
            )).also { it.tag = "slash" },
            // Hit
            buildFrame(base, listOf(
                PartOffset(torsoRegion(b), unit * 2, 0),
                PartOffset(leftArmRegion(b), unit, 0),
                PartOffset(rightArmRegion(b), unit * 3, 0),
                PartOffset(headRegion(b), unit * 2, 0),
                PartOffset(leftLegRegion(b), 0, 0),
                PartOffset(rightLegRegion(b), 0, 0)
            )).also { it.tag = "slash" },
            // Follow through (down)
            buildFrame(base, listOf(
                PartOffset(torsoRegion(b), unit, unit / 2),
                PartOffset(leftArmRegion(b), 0, unit / 2),
                PartOffset(rightArmRegion(b), unit * 2, unit),
                PartOffset(headRegion(b), unit, unit / 2),
                PartOffset(leftLegRegion(b), 0, 0),
                PartOffset(rightLegRegion(b), 0, 0)
            )).also { it.tag = "slash" },
            // Recover
            base.copy().also { it.tag = "slash" }
        )
    }

    /** Spell cast: arms raise to sky, body bows, then return. */
    private fun spellCast(base: Frame, b: BBox): List<Frame> {
        val unit = max(1, b.height / 14)
        // Charge: arms raise + body slight down
        val charge = buildFrame(base, listOf(
            PartOffset(torsoRegion(b), 0, unit / 2),
            PartOffset(leftArmRegion(b), -unit / 2, -unit * 2),
            PartOffset(rightArmRegion(b), unit / 2, -unit * 2),
            PartOffset(headRegion(b), 0, -unit / 2),
            PartOffset(leftLegRegion(b), 0, 0),
            PartOffset(rightLegRegion(b), 0, 0)
        )).also { it.tag = "spell" }
        // Apex: max extension
        val apex = buildFrame(base, listOf(
            PartOffset(torsoRegion(b), 0, -unit),
            PartOffset(leftArmRegion(b), -unit, -unit * 3),
            PartOffset(rightArmRegion(b), unit, -unit * 3),
            PartOffset(headRegion(b), 0, -unit * 2),
            PartOffset(leftLegRegion(b), 0, 0),
            PartOffset(rightLegRegion(b), 0, 0)
        )).also { it.tag = "spell" }
        // Release: arms come down forward
        val release = buildFrame(base, listOf(
            PartOffset(torsoRegion(b), unit, 0),
            PartOffset(leftArmRegion(b), unit, 0),
            PartOffset(rightArmRegion(b), unit * 2, 0),
            PartOffset(headRegion(b), unit, 0),
            PartOffset(leftLegRegion(b), 0, 0),
            PartOffset(rightLegRegion(b), 0, 0)
        )).also { it.tag = "spell" }
        val recover = base.copy().also { it.tag = "spell" }
        return listOf(charge, apex, release, recover)
    }

    /** Hit reaction: knockback + body twist. */
    private fun hit(base: Frame, b: BBox): List<Frame> {
        val unit = max(1, b.height / 12)
        return listOf(
            // Impact
            buildFrame(base, listOf(
                PartOffset(torsoRegion(b), -unit * 2, -unit),
                PartOffset(leftArmRegion(b), -unit * 3, -unit / 2),
                PartOffset(rightArmRegion(b), -unit, -unit),
                PartOffset(headRegion(b), -unit * 2, -unit * 2),
                PartOffset(leftLegRegion(b), -unit, 0),
                PartOffset(rightLegRegion(b), -unit, 0)
            )).also { it.tag = "hit" },
            // Stagger back
            buildFrame(base, listOf(
                PartOffset(torsoRegion(b), -unit, 0),
                PartOffset(leftArmRegion(b), -unit * 2, 0),
                PartOffset(rightArmRegion(b), 0, 0),
                PartOffset(headRegion(b), -unit, -unit),
                PartOffset(leftLegRegion(b), -unit / 2, 0),
                PartOffset(rightLegRegion(b), -unit / 2, 0)
            )).also { it.tag = "hit" },
            // Half-recovered
            buildFrame(base, listOf(
                PartOffset(torsoRegion(b), -unit / 2, 0),
                PartOffset(leftArmRegion(b), -unit, 0),
                PartOffset(rightArmRegion(b), 0, 0),
                PartOffset(headRegion(b), -unit / 2, -unit / 2),
                PartOffset(leftLegRegion(b), 0, 0),
                PartOffset(rightLegRegion(b), 0, 0)
            )).also { it.tag = "hit" },
            base.copy().also { it.tag = "hit" }
        )
    }

    /** Death: stagger → fall over → lie. */
    private fun death(base: Frame, b: BBox): List<Frame> {
        val unit = max(1, b.height / 12)
        return listOf(
            // Stagger
            buildFrame(base, listOf(
                PartOffset(torsoRegion(b), 0, unit),
                PartOffset(leftArmRegion(b), -unit, unit),
                PartOffset(rightArmRegion(b), unit, unit),
                PartOffset(headRegion(b), 0, unit),
                PartOffset(leftLegRegion(b), 0, 0),
                PartOffset(rightLegRegion(b), 0, 0)
            )).also { it.tag = "death" },
            // Knees buckle
            buildFrame(base, listOf(
                PartOffset(torsoRegion(b), 0, unit * 2),
                PartOffset(leftArmRegion(b), -unit, unit * 2),
                PartOffset(rightArmRegion(b), unit, unit * 2),
                PartOffset(headRegion(b), 0, unit * 2),
                PartOffset(leftLegRegion(b), 0, 0),
                PartOffset(rightLegRegion(b), 0, 0)
            )).also { it.tag = "death" },
            // Falling sideways
            buildFrame(base, listOf(
                PartOffset(headRegion(b), -unit * 3, unit * 2),
                PartOffset(torsoRegion(b), -unit, unit * 3),
                PartOffset(leftArmRegion(b), -unit * 4, unit * 3),
                PartOffset(rightArmRegion(b), 0, unit * 3),
                PartOffset(leftLegRegion(b), unit, 0),
                PartOffset(rightLegRegion(b), unit, 0)
            )).also { it.tag = "death" },
            // Final rest
            buildFrame(base, listOf(
                PartOffset(headRegion(b), -unit * 4, b.height / 3),
                PartOffset(torsoRegion(b), -unit * 2, b.height / 4),
                PartOffset(leftArmRegion(b), -unit * 5, b.height / 4),
                PartOffset(rightArmRegion(b), unit, b.height / 4),
                PartOffset(leftLegRegion(b), unit * 2, b.height / 8),
                PartOffset(rightLegRegion(b), unit * 2, b.height / 8)
            )).also { it.tag = "death" }
        )
    }

    /** Fall: body twists in air, arms out. */
    private fun fall(base: Frame, b: BBox): List<Frame> {
        val unit = max(1, b.height / 14)
        return (0 until 4).map { f ->
            val phase = f.toFloat() / 4f * 2f * PI.toFloat()
            val tilt = (sin(phase) * unit).roundToInt()
            val out = buildFrame(base, listOf(
                PartOffset(torsoRegion(b), tilt, 0),
                PartOffset(leftArmRegion(b), tilt - unit, -unit),
                PartOffset(rightArmRegion(b), tilt + unit, -unit),
                PartOffset(headRegion(b), tilt, -unit / 2),
                PartOffset(leftLegRegion(b), tilt - unit / 2, unit),
                PartOffset(rightLegRegion(b), tilt + unit / 2, unit)
            ))
            out.tag = "fall"; out
        }
    }

    /** Climb: alternating arm reach + body sway. */
    private fun climb(base: Frame, b: BBox): List<Frame> {
        val unit = max(1, b.height / 12)
        return (0 until 4).map { f ->
            val phase = f.toFloat() / 4f * 2f * PI.toFloat()
            val armReach = sin(phase)
            val leftArmDy = if (armReach > 0) -unit * 2 else 0
            val rightArmDy = if (armReach < 0) -unit * 2 else 0
            val bodyShift = (cos(phase) * unit * 0.5f).roundToInt()
            val out = buildFrame(base, listOf(
                PartOffset(torsoRegion(b), bodyShift, 0),
                PartOffset(leftArmRegion(b), bodyShift, leftArmDy),
                PartOffset(rightArmRegion(b), bodyShift, rightArmDy),
                PartOffset(headRegion(b), bodyShift, 0),
                PartOffset(leftLegRegion(b), bodyShift, if (rightArmDy != 0) -unit else 0),
                PartOffset(rightLegRegion(b), bodyShift, if (leftArmDy != 0) -unit else 0)
            ))
            out.tag = "climb"; out
        }
    }

    /** Dodge: quick sidestep with anticipation. */
    private fun dodge(base: Frame, b: BBox): List<Frame> {
        val unit = max(1, b.height / 10)
        return listOf(
            // Crouch (anticipation)
            buildFrame(base, listOf(
                PartOffset(torsoRegion(b), 0, unit),
                PartOffset(leftArmRegion(b), 0, unit),
                PartOffset(rightArmRegion(b), 0, unit),
                PartOffset(headRegion(b), 0, unit),
                PartOffset(leftLegRegion(b), 0, 0),
                PartOffset(rightLegRegion(b), 0, 0)
            )).also { it.tag = "dodge" },
            // Spring sideways
            buildFrame(base, listOf(
                PartOffset(torsoRegion(b), unit * 3, -unit * 2),
                PartOffset(leftArmRegion(b), unit * 2, -unit * 2),
                PartOffset(rightArmRegion(b), unit * 4, -unit),
                PartOffset(headRegion(b), unit * 3, -unit * 3),
                PartOffset(leftLegRegion(b), unit * 2, -unit),
                PartOffset(rightLegRegion(b), unit * 4, -unit)
            )).also { it.tag = "dodge" },
            // Land
            buildFrame(base, listOf(
                PartOffset(torsoRegion(b), unit * 4, unit),
                PartOffset(leftArmRegion(b), unit * 3, unit),
                PartOffset(rightArmRegion(b), unit * 5, unit),
                PartOffset(headRegion(b), unit * 4, unit),
                PartOffset(leftLegRegion(b), unit * 3, 0),
                PartOffset(rightLegRegion(b), unit * 5, 0)
            )).also { it.tag = "dodge" },
            // Back to original (we keep position shifted - artistic choice)
            buildFrame(base, listOf(
                PartOffset(torsoRegion(b), unit * 4, 0),
                PartOffset(leftArmRegion(b), unit * 4, 0),
                PartOffset(rightArmRegion(b), unit * 4, 0),
                PartOffset(headRegion(b), unit * 4, 0),
                PartOffset(leftLegRegion(b), unit * 4, 0),
                PartOffset(rightLegRegion(b), unit * 4, 0)
            )).also { it.tag = "dodge" }
        )
    }

    /** Jump: squash → stretch up → mid-air → land squash. */
    private fun jump(base: Frame, b: BBox): List<Frame> {
        val unit = max(1, b.height / 10)
        return listOf(
            // 1. Anticipation: deep crouch
            buildFrame(base, listOf(
                PartOffset(torsoRegion(b), 0, unit * 2),
                PartOffset(leftArmRegion(b), 0, unit * 2),
                PartOffset(rightArmRegion(b), 0, unit * 2),
                PartOffset(headRegion(b), 0, unit * 2),
                PartOffset(leftLegRegion(b), 0, 0),
                PartOffset(rightLegRegion(b), 0, 0)
            )).also { it.tag = "jump" },
            // 2. Launch: stretch up, legs tucked
            buildFrame(base, listOf(
                PartOffset(torsoRegion(b), 0, -unit * 2),
                PartOffset(leftArmRegion(b), -unit, -unit * 3),
                PartOffset(rightArmRegion(b), unit, -unit * 3),
                PartOffset(headRegion(b), 0, -unit * 3),
                PartOffset(leftLegRegion(b), 0, -unit),
                PartOffset(rightLegRegion(b), 0, -unit)
            )).also { it.tag = "jump" },
            // 3. Apex: floating, legs slightly forward
            buildFrame(base, listOf(
                PartOffset(torsoRegion(b), 0, -unit * 3),
                PartOffset(leftArmRegion(b), -unit, -unit * 2),
                PartOffset(rightArmRegion(b), unit, -unit * 2),
                PartOffset(headRegion(b), 0, -unit * 3),
                PartOffset(leftLegRegion(b), -unit / 2, -unit * 2),
                PartOffset(rightLegRegion(b), unit / 2, -unit * 2)
            )).also { it.tag = "jump" },
            // 4. Descent
            buildFrame(base, listOf(
                PartOffset(torsoRegion(b), 0, -unit),
                PartOffset(leftArmRegion(b), 0, -unit),
                PartOffset(rightArmRegion(b), 0, -unit),
                PartOffset(headRegion(b), 0, -unit),
                PartOffset(leftLegRegion(b), 0, -unit / 2),
                PartOffset(rightLegRegion(b), 0, -unit / 2)
            )).also { it.tag = "jump" },
            // 5. Land squash
            buildFrame(base, listOf(
                PartOffset(torsoRegion(b), 0, unit * 2),
                PartOffset(leftArmRegion(b), 0, unit * 2),
                PartOffset(rightArmRegion(b), 0, unit * 2),
                PartOffset(headRegion(b), 0, unit * 2),
                PartOffset(leftLegRegion(b), 0, 0),
                PartOffset(rightLegRegion(b), 0, 0)
            )).also { it.tag = "jump" },
            // 6. Stand
            base.copy().also { it.tag = "jump" }
        )
    }

    private fun crouch(base: Frame, b: BBox): List<Frame> {
        val unit = max(1, b.height / 10)
        return listOf(
            buildFrame(base, listOf(
                PartOffset(torsoRegion(b), 0, unit),
                PartOffset(leftArmRegion(b), 0, unit),
                PartOffset(rightArmRegion(b), 0, unit),
                PartOffset(headRegion(b), 0, unit),
                PartOffset(leftLegRegion(b), 0, 0),
                PartOffset(rightLegRegion(b), 0, 0)
            )).also { it.tag = "crouch" },
            buildFrame(base, listOf(
                PartOffset(torsoRegion(b), 0, unit * 2),
                PartOffset(leftArmRegion(b), 0, unit * 2),
                PartOffset(rightArmRegion(b), 0, unit * 2),
                PartOffset(headRegion(b), 0, unit * 2),
                PartOffset(leftLegRegion(b), 0, 0),
                PartOffset(rightLegRegion(b), 0, 0)
            )).also { it.tag = "crouch" },
            buildFrame(base, listOf(
                PartOffset(torsoRegion(b), 0, unit * 3),
                PartOffset(leftArmRegion(b), -unit, unit * 3),
                PartOffset(rightArmRegion(b), unit, unit * 3),
                PartOffset(headRegion(b), 0, unit * 3),
                PartOffset(leftLegRegion(b), 0, 0),
                PartOffset(rightLegRegion(b), 0, 0)
            )).also { it.tag = "crouch" },
            // hold crouch
            buildFrame(base, listOf(
                PartOffset(torsoRegion(b), 0, unit * 3),
                PartOffset(leftArmRegion(b), -unit, unit * 3),
                PartOffset(rightArmRegion(b), unit, unit * 3),
                PartOffset(headRegion(b), 0, unit * 3),
                PartOffset(leftLegRegion(b), 0, 0),
                PartOffset(rightLegRegion(b), 0, 0)
            )).also { it.tag = "crouch" }
        )
    }

    /** Defense / shake: small jitter around center. */
    private fun defense(base: Frame, b: BBox): List<Frame> {
        val unit = max(1, b.height / 24)
        return (0 until 4).map { f ->
            val phase = f.toFloat() / 4f * 2f * PI.toFloat()
            val sway = (sin(phase * 2f) * unit).roundToInt()
            val out = buildFrame(base, listOf(
                PartOffset(fullRegion(b), sway, 0)
            ))
            out.tag = "defense"; out
        }
    }

    /** Turn: shrink horizontally then mirror, expand back. */
    private fun turn(base: Frame, b: BBox): List<Frame> {
        val flipped = base.flipHorizontal()
        return listOf(
            squashHorizontal(base, b, 1).also { it.tag = "turn" },
            squashHorizontal(base, b, 3).also { it.tag = "turn" },
            squashHorizontal(flipped, b, 3).also { it.tag = "turn" },
            flipped.also { it.tag = "turn" }
        )
    }

    private fun squashHorizontal(src: Frame, b: BBox, pixels: Int): Frame {
        if (pixels <= 0) return src.copy()
        val out = Frame(src.width, src.height)
        out.tag = src.tag
        val srcW = b.x1 - b.x0 + 1
        val dstW = (srcW - pixels).coerceAtLeast(1)
        val offset = (srcW - dstW) / 2
        for (y in 0 until src.height) for (x in 0 until src.width) {
            if (x < b.x0 || x > b.x1) out.set(x, y, src.get(x, y))
        }
        for (xo in 0 until dstW) {
            val xs = b.x0 + (xo.toFloat() / dstW * srcW).roundToInt()
            for (y in 0 until src.height) out.set(b.x0 + offset + xo, y, src.get(xs, y))
        }
        return out
    }

    /** Bob: gentle floating. */
    private fun bob(base: Frame, b: BBox): List<Frame> {
        val unit = max(1, b.height / 16)
        return (0 until 4).map { f ->
            val phase = (f + 1) * (2.0 * PI) / 4.0
            val dy = (sin(phase) * unit * 1.5).roundToInt()
            val out = buildFrame(base, listOf(
                PartOffset(fullRegion(b), 0, dy)
            ))
            out.tag = "bob"; out
        }
    }

    /** Wave: raise right arm, hand moves left-right. */
    private fun wave(base: Frame, b: BBox): List<Frame> {
        val unit = max(1, b.height / 12)
        return (0 until 4).map { f ->
            val phase = f.toFloat() / 4f * 2f * PI.toFloat()
            val handX = (sin(phase) * unit).roundToInt()
            val out = buildFrame(base, listOf(
                PartOffset(torsoRegion(b), 0, 0),
                PartOffset(leftArmRegion(b), 0, 0),
                PartOffset(rightArmRegion(b), handX, -unit * 2),
                PartOffset(headRegion(b), 0, 0),
                PartOffset(leftLegRegion(b), 0, 0),
                PartOffset(rightLegRegion(b), 0, 0)
            ))
            out.tag = "wave"; out
        }
    }
}
