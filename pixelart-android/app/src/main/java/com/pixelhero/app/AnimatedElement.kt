package com.pixelhero.app

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Discrete animated decorative elements that can be placed on existing frames.
 * Each element occupies a small region; its appearance changes per frame to
 * create motion (flame flicker, smoke rise, waterfall scroll, etc.).
 *
 * The element does NOT erase background pixels - it overlays only its own pixels.
 */
object AnimatedElement {

    enum class Type(val displayName: String, val recommendedFrames: Int) {
        TORCH("Flambeau mural", 4),
        CAMPFIRE("Feu de camp", 4),
        WATERFALL("Cascade", 4),
        LANTERN("Lanterne suspendue", 4),
        MAGIC_CRYSTAL("Cristal magique", 4),
        CHIMNEY_SMOKE("Cheminée fumante", 6),
        CANDLE("Bougie", 4),
        SHOOTING_STAR("Étoile filante", 6),
        BUTTERFLY("Papillon", 4),
        WATER_RIPPLE("Vague qui s'étend", 4);

        override fun toString() = displayName
    }

    /**
     * Render the element on the given [frame] for animation frame [frameIdx]
     * (out of [totalFrames]) at position (cx, cy). The position represents
     * a meaningful anchor for each element type (e.g., for TORCH it's the
     * base of the post; for CAMPFIRE it's the center of the fire).
     */
    fun render(frame: Frame, cx: Int, cy: Int, type: Type, frameIdx: Int, totalFrames: Int) {
        when (type) {
            Type.TORCH -> renderTorch(frame, cx, cy, frameIdx, totalFrames)
            Type.CAMPFIRE -> renderCampfire(frame, cx, cy, frameIdx, totalFrames)
            Type.WATERFALL -> renderWaterfall(frame, cx, cy, frameIdx, totalFrames)
            Type.LANTERN -> renderLantern(frame, cx, cy, frameIdx, totalFrames)
            Type.MAGIC_CRYSTAL -> renderCrystal(frame, cx, cy, frameIdx, totalFrames)
            Type.CHIMNEY_SMOKE -> renderChimneySmoke(frame, cx, cy, frameIdx, totalFrames)
            Type.CANDLE -> renderCandle(frame, cx, cy, frameIdx, totalFrames)
            Type.SHOOTING_STAR -> renderShootingStar(frame, cx, cy, frameIdx, totalFrames)
            Type.BUTTERFLY -> renderButterfly(frame, cx, cy, frameIdx, totalFrames)
            Type.WATER_RIPPLE -> renderWaterRipple(frame, cx, cy, frameIdx, totalFrames)
        }
    }

    private fun set(f: Frame, x: Int, y: Int, c: Int) {
        if (x in 0 until f.width && y in 0 until f.height) f.set(x, y, c)
    }

    // ---- Flame shapes (reused for torch/candle/campfire) ----
    private val FLAME_YELLOW = 0xFFFFD93D.toInt()
    private val FLAME_ORANGE = 0xFFFF6B22.toInt()
    private val FLAME_RED = 0xFFCC2222.toInt()
    private val WOOD_DARK = 0xFF4A2C18.toInt()
    private val WOOD_LIGHT = 0xFF8B5A2F.toInt()

    /** Small flame variation. cx, cy = top of the post (flame base). */
    private fun drawFlame(f: Frame, cx: Int, cy: Int, phase: Int, scale: Int = 1) {
        when (phase % 4) {
            0 -> {
                set(f, cx, cy - 1, FLAME_YELLOW)
                set(f, cx, cy - 2, FLAME_ORANGE)
            }
            1 -> {
                set(f, cx, cy - 1, FLAME_YELLOW)
                set(f, cx - 1, cy - 2, FLAME_ORANGE)
                set(f, cx, cy - 2, FLAME_YELLOW)
                set(f, cx, cy - 3, FLAME_RED)
            }
            2 -> {
                set(f, cx, cy - 1, FLAME_YELLOW)
                set(f, cx - 1, cy - 2, FLAME_ORANGE)
                set(f, cx + 1, cy - 2, FLAME_ORANGE)
                set(f, cx, cy - 2, FLAME_YELLOW)
                set(f, cx, cy - 3, FLAME_YELLOW)
                set(f, cx, cy - 4, FLAME_RED)
            }
            3 -> {
                set(f, cx, cy - 1, FLAME_YELLOW)
                set(f, cx + 1, cy - 2, FLAME_ORANGE)
                set(f, cx, cy - 2, FLAME_YELLOW)
                set(f, cx, cy - 3, FLAME_RED)
            }
        }
    }

    // ---- TORCH: wall-mounted, flame flickers ----
    private fun renderTorch(f: Frame, cx: Int, cy: Int, fi: Int, total: Int) {
        // Wall bracket (extends right of cx)
        set(f, cx, cy, WOOD_DARK)
        set(f, cx + 1, cy, WOOD_DARK)
        set(f, cx + 1, cy + 1, WOOD_DARK)
        set(f, cx + 2, cy + 1, WOOD_DARK)
        // Post (3 px tall, descends below bracket)
        set(f, cx + 2, cy, WOOD_LIGHT)
        set(f, cx + 2, cy - 1, WOOD_DARK)
        set(f, cx + 2, cy - 2, WOOD_DARK)
        // Flame at top
        drawFlame(f, cx + 2, cy - 2, fi)
        // Subtle glow on wall (light yellow halo around flame base)
        if (fi % 2 == 0) {
            set(f, cx + 3, cy - 2, 0x55FFE5B4.toInt())
        }
    }

    // ---- CAMPFIRE: stones + logs + flames ----
    private fun renderCampfire(f: Frame, cx: Int, cy: Int, fi: Int, total: Int) {
        val stone = 0xFF707070.toInt()
        val stoneL = 0xFFA0A0A0.toInt()
        // Stone circle (around cx, cy)
        set(f, cx - 2, cy, stone); set(f, cx + 2, cy, stone)
        set(f, cx - 1, cy + 1, stoneL); set(f, cx + 1, cy + 1, stoneL)
        set(f, cx, cy + 1, stoneL)
        // Logs underneath
        set(f, cx - 1, cy, WOOD_DARK); set(f, cx, cy, WOOD_LIGHT); set(f, cx + 1, cy, WOOD_DARK)
        // Flames: cluster of 3 flames at slightly different phases
        drawFlame(f, cx, cy - 1, fi)
        drawFlame(f, cx - 1, cy - 1, fi + 1)
        drawFlame(f, cx + 1, cy - 1, fi + 2)
    }

    // ---- WATERFALL: vertical column of water scrolling down ----
    private fun renderWaterfall(f: Frame, cx: Int, cy: Int, fi: Int, total: Int) {
        val waterDark = 0xFF1F6FCC.toInt()
        val waterLight = 0xFFE0F4FF.toInt()
        val waterMid = 0xFF6BB0E5.toInt()
        // Top: solid rock
        set(f, cx - 2, cy - 4, 0xFF555566.toInt())
        set(f, cx - 1, cy - 4, 0xFF555566.toInt())
        set(f, cx + 1, cy - 4, 0xFF555566.toInt())
        set(f, cx + 2, cy - 4, 0xFF555566.toInt())
        // Falling water column (3 px wide, 6 tall) with phase-dependent highlights
        for (dy in -3..3) {
            for (dx in -1..1) {
                set(f, cx + dx, cy + dy, if (dx == 0) waterMid else waterDark)
            }
            // Moving highlights
            if ((dy + fi) % 2 == 0) set(f, cx, cy + dy, waterLight)
        }
        // Splash at the bottom (frame-dependent)
        when (fi % 4) {
            0, 2 -> {
                set(f, cx - 2, cy + 4, waterLight); set(f, cx + 2, cy + 4, waterLight)
            }
            1, 3 -> {
                set(f, cx - 3, cy + 4, waterLight); set(f, cx + 3, cy + 4, waterLight)
                set(f, cx - 2, cy + 5, waterMid); set(f, cx + 2, cy + 5, waterMid)
            }
        }
    }

    // ---- LANTERN: hanging lantern with chain, glow pulses ----
    private fun renderLantern(f: Frame, cx: Int, cy: Int, fi: Int, total: Int) {
        val metal = 0xFF333333.toInt()
        val glass = 0xFFFFE5B4.toInt()
        val glowBright = 0xFFFFC95C.toInt()
        // Chain above (5 px)
        for (k in 1..5) set(f, cx, cy - 6 - k, metal)
        // Top cap
        set(f, cx - 1, cy - 5, metal); set(f, cx, cy - 6, metal); set(f, cx + 1, cy - 5, metal)
        // Lantern body 3x3
        set(f, cx - 1, cy - 4, metal); set(f, cx + 1, cy - 4, metal)
        set(f, cx - 1, cy - 3, metal); set(f, cx + 1, cy - 3, metal)
        set(f, cx - 1, cy - 2, metal); set(f, cx + 1, cy - 2, metal)
        set(f, cx - 1, cy - 1, metal); set(f, cx, cy - 1, metal); set(f, cx + 1, cy - 1, metal)
        // Glass interior pulses
        val brightness = (fi % 4) / 3f
        val glow = lerpColor(glass, glowBright, brightness)
        set(f, cx, cy - 4, glow); set(f, cx, cy - 3, glow); set(f, cx, cy - 2, glow)
        // Halo when brightest
        if (fi % 4 == 2) {
            set(f, cx - 2, cy - 3, 0x55FFD93D.toInt())
            set(f, cx + 2, cy - 3, 0x55FFD93D.toInt())
        }
    }

    // ---- MAGIC CRYSTAL: faceted gem with pulsing glow ----
    private fun renderCrystal(f: Frame, cx: Int, cy: Int, fi: Int, total: Int) {
        val core = 0xFFAA22EE.toInt()
        val edge = 0xFF6611AA.toInt()
        // Diamond shape
        set(f, cx, cy - 3, edge)
        set(f, cx - 1, cy - 2, edge); set(f, cx, cy - 2, core); set(f, cx + 1, cy - 2, edge)
        set(f, cx - 2, cy - 1, edge); set(f, cx - 1, cy - 1, core); set(f, cx, cy - 1, core); set(f, cx + 1, cy - 1, core); set(f, cx + 2, cy - 1, edge)
        set(f, cx - 1, cy, edge); set(f, cx, cy, core); set(f, cx + 1, cy, edge)
        set(f, cx, cy + 1, edge)
        // Pulse highlight on facets
        when (fi % 4) {
            0 -> set(f, cx, cy - 2, 0xFFFFE5FF.toInt())
            1 -> { set(f, cx - 1, cy - 1, 0xFFFFE5FF.toInt()); set(f, cx + 1, cy - 1, 0xFFFFE5FF.toInt()) }
            2 -> { set(f, cx, cy, 0xFFFFE5FF.toInt()) }
            3 -> { set(f, cx, cy - 1, 0xFFFFE5FF.toInt()) }
        }
    }

    // ---- CHIMNEY SMOKE: puffs rising ----
    private fun renderChimneySmoke(f: Frame, cx: Int, cy: Int, fi: Int, total: Int) {
        val smoke1 = 0xFFDDDDDD.toInt()
        val smoke2 = 0xFFAAAAAA.toInt()
        // Chimney static
        set(f, cx - 1, cy, 0xFF8B3A3A.toInt())
        set(f, cx, cy, 0xFFA04545.toInt())
        set(f, cx + 1, cy, 0xFF8B3A3A.toInt())
        set(f, cx - 1, cy + 1, 0xFF6B2424.toInt())
        set(f, cx + 1, cy + 1, 0xFF6B2424.toInt())
        // Smoke puffs at different heights based on frame
        val baseHeight = -1
        for (puffIdx in 0..3) {
            val puffY = cy + baseHeight - puffIdx * 2 - (fi % 2)
            val sideOff = ((puffIdx + fi) % 3) - 1
            val color = if (puffIdx < 2) smoke1 else smoke2
            set(f, cx + sideOff, puffY, color)
            set(f, cx + sideOff + 1, puffY, color)
            if (puffIdx > 0) set(f, cx + sideOff, puffY - 1, smoke2)
        }
    }

    // ---- CANDLE: small flame on a candle ----
    private fun renderCandle(f: Frame, cx: Int, cy: Int, fi: Int, total: Int) {
        // Candle body (cream/yellow)
        val body = 0xFFF5E5C0.toInt()
        val shadow = 0xFFC0AC8B.toInt()
        for (dy in 0..3) {
            set(f, cx, cy + dy, body)
            set(f, cx + 1, cy + dy, shadow)
        }
        // Wick
        set(f, cx, cy - 1, 0xFF333333.toInt())
        // Small flame
        when (fi % 4) {
            0 -> { set(f, cx, cy - 2, FLAME_YELLOW) }
            1 -> { set(f, cx, cy - 2, FLAME_YELLOW); set(f, cx - 1, cy - 2, FLAME_ORANGE) }
            2 -> { set(f, cx, cy - 2, FLAME_YELLOW); set(f, cx, cy - 3, FLAME_ORANGE) }
            3 -> { set(f, cx, cy - 2, FLAME_YELLOW); set(f, cx + 1, cy - 2, FLAME_ORANGE) }
        }
    }

    // ---- SHOOTING STAR: trail moves diagonally ----
    private fun renderShootingStar(f: Frame, cx: Int, cy: Int, fi: Int, total: Int) {
        // Position depends on frame
        val px = cx + (fi * 3) - total
        val py = cy + (fi * 2) - total
        val trailLen = 4
        for (k in 0 until trailLen) {
            val tx = px - k
            val ty = py - k / 2
            val col = lerpColor(0xFFFFFFFF.toInt(), 0xFF050524.toInt(), k.toFloat() / trailLen)
            set(f, tx, ty, col)
        }
    }

    // ---- BUTTERFLY: 4-frame wing animation, fluttering position ----
    private fun renderButterfly(f: Frame, cx: Int, cy: Int, fi: Int, total: Int) {
        val body = 0xFF333333.toInt()
        val wingPrimary = 0xFFFFB347.toInt()
        val wingDetail = 0xFFFF6B22.toInt()
        // Body shifts slightly
        val bobX = cx + ((fi % 4) - 2)
        val bobY = cy + (sin(fi * Math.PI / 2).toInt())
        set(f, bobX, bobY, body)
        set(f, bobX, bobY + 1, body)
        // Wings: open vs closed per frame
        when (fi % 4) {
            0 -> {
                // open
                set(f, bobX - 2, bobY, wingPrimary); set(f, bobX - 1, bobY, wingPrimary)
                set(f, bobX + 1, bobY, wingPrimary); set(f, bobX + 2, bobY, wingPrimary)
                set(f, bobX - 2, bobY + 1, wingDetail); set(f, bobX + 2, bobY + 1, wingDetail)
            }
            1, 3 -> {
                // mid
                set(f, bobX - 1, bobY, wingPrimary); set(f, bobX + 1, bobY, wingPrimary)
            }
            2 -> {
                // closed (up)
                set(f, bobX, bobY - 1, wingPrimary)
            }
        }
    }

    // ---- WATER RIPPLE: concentric rings expanding ----
    private fun renderWaterRipple(f: Frame, cx: Int, cy: Int, fi: Int, total: Int) {
        val rippleColor = 0xFFE0F4FF.toInt()
        val ringRadius = (fi + 1) % 4 + 1
        // Draw a ring of radius `ringRadius`
        for (angle in 0 until 360 step 30) {
            val rad = angle * Math.PI / 180.0
            val rx = cx + (kotlin.math.cos(rad) * ringRadius).toInt()
            val ry = cy + (kotlin.math.sin(rad) * ringRadius / 2).toInt()  // elliptical
            set(f, rx, ry, rippleColor)
        }
    }

    private fun lerpColor(a: Int, b: Int, tt: Float): Int {
        val t = tt.coerceIn(0f, 1f)
        val ar = (a shr 16) and 0xFF; val ag = (a shr 8) and 0xFF; val ab = a and 0xFF
        val br = (b shr 16) and 0xFF; val bg = (b shr 8) and 0xFF; val bb = b and 0xFF
        val r = (ar + (br - ar) * t).toInt()
        val g = (ag + (bg - ag) * t).toInt()
        val bl = (ab + (bb - ab) * t).toInt()
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or bl
    }
}
