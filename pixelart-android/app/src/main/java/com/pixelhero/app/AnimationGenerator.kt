package com.pixelhero.app

import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Predicts subsequent animation frames from a base frame using heuristic
 * transformations (shifts, squash/stretch, region nudges). The result is
 * never perfect - it's a useful starting point that the artist refines.
 *
 * The base frame is divided into 4 horizontal regions:
 *   - HEAD:  top 1/4 of opaque pixels' bounding box
 *   - BODY:  next 1/4..3/4
 *   - LEGS:  bottom 1/4 (split into left/right halves)
 *
 * Region detection uses the bounding box of opaque pixels to be robust to
 * sprite size and placement.
 */
object AnimationGenerator {

    enum class Preset(val displayName: String, val frameCount: Int) {
        WALK("Marche ← / → (4 frames)", 4),
        WALK8("Marche fluide ← / → (8)", 8),
        WALK_DOWN("Marche ↓ face (4)", 4),
        WALK_UP("Marche ↑ dos (4)", 4),
        IDLE("Idle / respiration (4)", 4),
        ATTACK("Attaque / coup (4)", 4),
        SPELL_CAST("Sort de magie (4)", 4),
        HIT("Dégât pris / impact (4)", 4),
        DEATH("Mort / chute KO (4)", 4),
        FALL("Chute libre (4)", 4),
        CLIMB("Escalade (4)", 4),
        DODGE("Esquive (4)", 4),
        JUMP("Saut (4)", 4),
        DEFENSE("Défense / tremblement (4)", 4),
        TURN("Rotation gauche/droite (4)", 4),
        BOB("Flottement (4)", 4);

        override fun toString() = displayName
    }

    /** Generate frames *after* the base. Returns N new frames. */
    fun generate(base: Frame, preset: Preset): List<Frame> {
        val bbox = computeBoundingBox(base) ?: return List(preset.frameCount) { base.copy() }
        return when (preset) {
            Preset.WALK -> walk4(base, bbox)
            Preset.WALK8 -> walk8(base, bbox)
            Preset.WALK_DOWN -> walkDown(base, bbox)
            Preset.WALK_UP -> walkUp(base, bbox)
            Preset.IDLE -> idle(base, bbox)
            Preset.ATTACK -> attack(base, bbox)
            Preset.SPELL_CAST -> spellCast(base, bbox)
            Preset.HIT -> hit(base, bbox)
            Preset.DEATH -> death(base, bbox)
            Preset.FALL -> fall(base, bbox)
            Preset.CLIMB -> climb(base, bbox)
            Preset.DODGE -> dodge(base, bbox)
            Preset.JUMP -> jump(base, bbox)
            Preset.DEFENSE -> defense(base, bbox)
            Preset.TURN -> turn(base, bbox)
            Preset.BOB -> bob(base, bbox)
        }
    }

    // ---- Bounding box of opaque pixels ----
    data class BBox(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        val width get() = x1 - x0 + 1
        val height get() = y1 - y0 + 1
        val cx get() = (x0 + x1) / 2
        // Body regions (head/body/legs/feet)
        val headBottom get() = y0 + height / 4
        val bodyBottom get() = y0 + (height * 2) / 3
        val legsBottom get() = y1
    }

    private fun computeBoundingBox(frame: Frame): BBox? {
        var minX = frame.width; var minY = frame.height
        var maxX = -1; var maxY = -1
        for (y in 0 until frame.height) for (x in 0 until frame.width) {
            val c = frame.pixels[y * frame.width + x]
            if ((c ushr 24) and 0xFF >= 128) {
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
        }
        if (maxX < 0) return null
        return BBox(minX, minY, maxX, maxY)
    }

    // ---- Helpers for region transforms ----

    /** Build a new frame by applying [transform] to each pixel of [src]. */
    private fun build(src: Frame, transform: (x: Int, y: Int) -> Int): Frame {
        val out = Frame(src.width, src.height)
        out.tag = src.tag
        for (y in 0 until src.height) for (x in 0 until src.width) {
            out.set(x, y, transform(x, y))
        }
        return out
    }

    /** Shift the body globally by (dx,dy). */
    private fun shifted(src: Frame, dx: Int, dy: Int): Frame =
        build(src) { x, y -> src.get(x - dx, y - dy) }

    /** Shift only the pixels whose Y is in yMin..yMax by (dx,dy). */
    private fun shiftRegion(src: Frame, yMin: Int, yMax: Int, dx: Int, dy: Int): Frame {
        return build(src) { x, y ->
            // For target pixel (x,y), find source pixel
            val sy = y - dy
            if (sy in yMin..yMax) src.get(x - dx, sy) else if (y in yMin..yMax) 0 else src.get(x, y)
        }
    }

    /** Shift only the LEFT half of region yMin..yMax by (dx,dy). */
    private fun shiftHalfRegion(src: Frame, yMin: Int, yMax: Int, leftHalf: Boolean, cx: Int, dx: Int, dy: Int): Frame {
        return build(src) { x, y ->
            val sx = x - dx
            val sy = y - dy
            // is (sx,sy) in the moved region?
            val inSrcRegion = sy in yMin..yMax && (if (leftHalf) sx <= cx else sx > cx)
            // is target (x,y) where we are clearing the moved area?
            val inTgtRegion = y in yMin..yMax && (if (leftHalf) x <= cx else x > cx)
            if (inSrcRegion) src.get(sx, sy)
            else if (inTgtRegion) 0
            else src.get(x, y)
        }
    }

    /** Vertical squash: compress region yMin..yMax vertically by pixels (top + bottom stay). */
    private fun squash(src: Frame, yMin: Int, yMax: Int, pixels: Int): Frame {
        if (pixels <= 0) return src.copy()
        val out = Frame(src.width, src.height)
        out.tag = src.tag
        // copy unchanged regions
        for (y in 0 until src.height) for (x in 0 until src.width) {
            if (y < yMin || y > yMax) out.set(x, y, src.get(x, y))
        }
        // squash region: map srcY in yMin..yMax to outY in yMin..yMax-pixels
        val srcH = yMax - yMin + 1
        val dstH = (srcH - pixels).coerceAtLeast(1)
        for (yo in 0 until dstH) {
            val ys = yMin + (yo.toFloat() / dstH * srcH).roundToInt()
            for (x in 0 until src.width) {
                out.set(x, yMin + yo, src.get(x, ys))
            }
        }
        return out
    }

    // ---- Animation presets ----

    /**
     * Marche face caméra (le héros marche vers le bas de l'écran).
     * Le mouvement caractéristique vu de face : balancement du corps,
     * pieds qui alternent gauche/droite, léger bob vertical.
     */
    private fun walkDown(base: Frame, b: BBox): List<Frame> {
        return listOf(
            // Frame 2: pied gauche en avant (descend 1px), corps légèrement à gauche
            run {
                val a = shiftHalfRegion(base, b.bodyBottom + 1, b.legsBottom, leftHalf = true, cx = b.cx, dx = 0, dy = 1)
                shifted(a, -1, 0)
            }.also { it.tag = "walk_down" },
            // Frame 3: passing - corps centré, légèrement haut
            shifted(base, 0, -1).also { it.tag = "walk_down" },
            // Frame 4: pied droit en avant (descend 1px), corps légèrement à droite
            run {
                val a = shiftHalfRegion(base, b.bodyBottom + 1, b.legsBottom, leftHalf = false, cx = b.cx, dx = 0, dy = 1)
                shifted(a, 1, 0)
            }.also { it.tag = "walk_down" }
        )
    }

    /**
     * Marche dos caméra (le héros s'éloigne vers le haut).
     * Mouvement plus discret : sway horizontal du corps + alternance des
     * pieds. Souvent on voit moins les bras (cachés derrière).
     */
    private fun walkUp(base: Frame, b: BBox): List<Frame> {
        return listOf(
            // Frame 2: corps sway -1 (vers gauche), pied gauche se lève (-1px)
            run {
                val a = shifted(base, -1, 0)
                shiftHalfRegion(a, b.bodyBottom + 1, b.legsBottom, leftHalf = true, cx = b.cx, dx = 0, dy = -1)
            }.also { it.tag = "walk_up" },
            // Frame 3: corps centré + très léger bob bas
            shifted(base, 0, 1).also { it.tag = "walk_up" },
            // Frame 4: corps sway +1 (vers droite), pied droit se lève (-1px)
            run {
                val a = shifted(base, 1, 0)
                shiftHalfRegion(a, b.bodyBottom + 1, b.legsBottom, leftHalf = false, cx = b.cx, dx = 0, dy = -1)
            }.also { it.tag = "walk_up" }
        )
    }

    private fun walk4(base: Frame, b: BBox): List<Frame> {
        // 4-frame walk cycle: contact, recoil, passing, high-point
        // Contact = base.
        // Recoil = body up 1px + legs squash a bit
        // Passing = base
        // Highpoint = body up 1px + legs split (left forward, right back)
        return listOf(
            // Frame 2: legs split - left forward, right back
            run {
                val a = shiftHalfRegion(base, b.bodyBottom + 1, b.legsBottom, leftHalf = true, cx = b.cx, dx = 1, dy = -1)
                shiftHalfRegion(a, b.bodyBottom + 1, b.legsBottom, leftHalf = false, cx = b.cx, dx = -1, dy = 0)
            }.also { it.tag = "walk" },
            // Frame 3: passing - whole body 1px down (impact)
            shifted(base, 0, 1).also { it.tag = "walk" },
            // Frame 4: legs split opposite - right forward, left back
            run {
                val a = shiftHalfRegion(base, b.bodyBottom + 1, b.legsBottom, leftHalf = false, cx = b.cx, dx = 1, dy = -1)
                shiftHalfRegion(a, b.bodyBottom + 1, b.legsBottom, leftHalf = true, cx = b.cx, dx = -1, dy = 0)
            }.also { it.tag = "walk" }
        )
    }

    private fun walk8(base: Frame, b: BBox): List<Frame> {
        // Smooth 8-frame walk: combine 4-frame logic with intermediate poses
        val w4 = walk4(base, b)
        val out = mutableListOf<Frame>()
        for ((i, f) in w4.withIndex()) {
            out.add(f)
            // Intermediate: slight bob
            val bob = if (i % 2 == 0) -1 else 1
            out.add(shifted(f, 0, bob).also { it.tag = "walk" })
        }
        // Drop last intermediate to keep 8 generated (so caller's total = base + 8)
        return out.take(7).toMutableList().also { it.add(base.copy().also { f -> f.tag = "walk" }) }
    }

    private fun idle(base: Frame, b: BBox): List<Frame> {
        // Breathing: body very slightly up/down
        return listOf(
            shifted(base, 0, -1).also { it.tag = "idle" },
            base.copy().also { it.tag = "idle" },
            shifted(base, 0, 0).also {
                // chest expand: shift body region 0px but head -1 to make a "lift"
                it.pixels.fill(0)
                base.pixels.copyInto(it.pixels)
                it.tag = "idle"
            },
            base.copy().also { it.tag = "idle" }
        )
    }

    private fun attack(base: Frame, b: BBox): List<Frame> {
        // Attack swing: anticipation -> impact -> recovery
        return listOf(
            // Anticipation: shift body slightly back-left, head down
            shifted(base, -1, 1).also { it.tag = "attack" },
            // Impact: extend forward; head slightly up; body shifts +2px right
            shifted(base, 2, -1).also { it.tag = "attack" },
            // Mid recovery: shift +1 right
            shifted(base, 1, 0).also { it.tag = "attack" },
            // Back to base
            base.copy().also { it.tag = "attack" }
        )
    }

    private fun jump(base: Frame, b: BBox): List<Frame> {
        // Squash (crouch) -> stretch (jump up) -> mid air -> land squash
        return listOf(
            // Crouch: squash body region
            squash(base, b.headBottom, b.legsBottom, 2).also { it.tag = "jump" },
            // Stretch: shift body up 2px (jumping)
            shifted(base, 0, -3).also { it.tag = "jump" },
            // Mid air
            shifted(base, 0, -2).also { it.tag = "jump" },
            // Land
            squash(base, b.bodyBottom, b.legsBottom, 1).also { it.tag = "jump" }
        )
    }

    private fun defense(base: Frame, b: BBox): List<Frame> {
        // Shake / brace: tiny offsets
        return listOf(
            shifted(base, -1, 0).also { it.tag = "defense" },
            shifted(base, 1, 0).also { it.tag = "defense" },
            shifted(base, 0, -1).also { it.tag = "defense" },
            base.copy().also { it.tag = "defense" }
        )
    }

    private fun turn(base: Frame, b: BBox): List<Frame> {
        // Turn: progressive horizontal flip via squashing then unsquashing flipped
        val mid = squashHorizontal(base, b, 3)
        val flipped = base.flipHorizontal()
        val midFlip = squashHorizontal(flipped, b, 3)
        return listOf(
            squashHorizontal(base, b, 1).also { it.tag = "turn" },
            mid.also { it.tag = "turn" },
            midFlip.also { it.tag = "turn" },
            flipped.also { it.tag = "turn" }
        )
    }

    private fun squashHorizontal(src: Frame, b: BBox, pixels: Int): Frame {
        if (pixels <= 0) return src.copy()
        val out = Frame(src.width, src.height)
        val srcW = b.x1 - b.x0 + 1
        val dstW = (srcW - pixels).coerceAtLeast(1)
        val offset = (srcW - dstW) / 2
        for (y in 0 until src.height) for (x in 0 until src.width) {
            if (x < b.x0 || x > b.x1) out.set(x, y, src.get(x, y))
        }
        for (xo in 0 until dstW) {
            val xs = b.x0 + (xo.toFloat() / dstW * srcW).roundToInt()
            for (y in 0 until src.height) {
                out.set(b.x0 + offset + xo, y, src.get(xs, y))
            }
        }
        return out
    }

    private fun bob(base: Frame, b: BBox): List<Frame> {
        return List(4) { i ->
            val phase = (i + 1) * (2.0 * Math.PI) / 4.0
            val dy = (sin(phase) * 1.5).roundToInt()
            shifted(base, 0, dy).also { it.tag = "bob" }
        }
    }

    /** Spell cast: bras vers le ciel + montée + flash final. */
    private fun spellCast(base: Frame, b: BBox): List<Frame> {
        return listOf(
            // Concentration - body crouches slightly
            squash(base, b.headBottom, b.legsBottom, 1).also { it.tag = "spell_cast" },
            // Charge - body up + stretch
            shifted(base, 0, -2).also { it.tag = "spell_cast" },
            // Release - max stretch
            shifted(base, 0, -3).also { it.tag = "spell_cast" },
            // Recovery - body back to normal with shake
            shifted(base, 1, 0).also { it.tag = "spell_cast" }
        )
    }

    /** Hit reaction: pushed back + flash. */
    private fun hit(base: Frame, b: BBox): List<Frame> {
        return listOf(
            // Impact: pushed back violently
            shifted(base, -3, -1).also { it.tag = "hit" },
            // Recovery: slowly returning
            shifted(base, -2, 0).also { it.tag = "hit" },
            shifted(base, -1, 0).also { it.tag = "hit" },
            base.copy().also { it.tag = "hit" }
        )
    }

    /** Death: rotation 90deg + fall. */
    private fun death(base: Frame, b: BBox): List<Frame> {
        return listOf(
            // Stagger
            shifted(base, 0, 1).also { it.tag = "death" },
            // Tilt (squash vertically a lot)
            squash(base, b.y0, b.legsBottom, base.height / 3).also { it.tag = "death" },
            // Lying down (90 degree rotation via squash + shift)
            rotateToLying(base, b).also { it.tag = "death" },
            // KO (final position, slightly shaken)
            rotateToLying(base, b).also { it.tag = "death" }
        )
    }

    private fun rotateToLying(src: Frame, b: BBox): Frame {
        // Approximate 90-degree rotation: collapse vertical bbox into horizontal
        val out = Frame(src.width, src.height)
        out.tag = src.tag
        val centerX = b.cx
        val groundY = b.legsBottom
        // For each source pixel (x, y), put it at (centerX + (groundY - y) - height/2, groundY)
        // ... actually let's just squash to height 1/3 and shift
        val squashed = squash(src, b.y0, b.legsBottom, b.height - b.height / 3)
        return shifted(squashed, 0, (b.height - b.height / 3) / 2)
    }

    /** Free fall: arms out, body twisting. */
    private fun fall(base: Frame, b: BBox): List<Frame> {
        return listOf(
            // Body up + slight sway
            shifted(base, -1, -2).also { it.tag = "fall" },
            shifted(base, 1, -1).also { it.tag = "fall" },
            shifted(base, -1, 0).also { it.tag = "fall" },
            shifted(base, 1, 1).also { it.tag = "fall" }
        )
    }

    /** Climb: alternating limb up. */
    private fun climb(base: Frame, b: BBox): List<Frame> {
        return listOf(
            // Right arm up (right side of body shifted up)
            shiftHalfRegion(base, b.y0, b.bodyBottom, leftHalf = false, cx = b.cx, dx = 0, dy = -1).also { it.tag = "climb" },
            // Both legs slightly raised
            shifted(base, 0, -1).also { it.tag = "climb" },
            // Left arm up
            shiftHalfRegion(base, b.y0, b.bodyBottom, leftHalf = true, cx = b.cx, dx = 0, dy = -1).also { it.tag = "climb" },
            // Reset
            base.copy().also { it.tag = "climb" }
        )
    }

    /** Dodge: quick sidestep + return. */
    private fun dodge(base: Frame, b: BBox): List<Frame> {
        return listOf(
            // Squash (anticipation)
            squash(base, b.headBottom, b.legsBottom, 1).also { it.tag = "dodge" },
            // Sidestep right
            shifted(base, 3, -1).also { it.tag = "dodge" },
            // Mid recovery
            shifted(base, 2, 0).also { it.tag = "dodge" },
            // Back to position
            shifted(base, 0, 0).also { it.tag = "dodge" }
        )
    }
}
