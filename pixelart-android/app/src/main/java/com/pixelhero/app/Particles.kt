package com.pixelhero.app

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import java.util.Random

/**
 * Particle effects overlay. Paints small animated dots (sparks, smoke, embers,
 * etc.) on top of existing frames. The base frame content is preserved; only
 * particle pixels are added.
 */
object Particles {

    enum class Type(val displayName: String) {
        SPARKLES("Étincelles dorées"),
        SMOKE("Fumée"),
        EMBERS("Braises montantes"),
        SNOW("Neige"),
        AURA("Aura magique"),
        BLOOD("Sang (impact)"),
        DUST("Poussière (pas)"),
        STARS("Étoiles tournantes"),
        BUBBLES("Bulles d'eau"),
        LEAVES("Feuilles qui tombent")
    }

    private data class Particle(
        val startPhase: Float, val angle: Float, val speed: Float,
        val color: Int, val drift: Float
    )

    /** Apply particle effect to a list of frames, anchored at (cx, cy). */
    fun apply(frames: List<Frame>, type: Type, cx: Int, cy: Int, seed: Long = System.currentTimeMillis()): List<Frame> {
        if (frames.isEmpty()) return frames
        val r = Random(seed)
        val particleCount = when (type) {
            Type.SPARKLES, Type.STARS -> 6
            Type.SMOKE, Type.EMBERS, Type.SNOW, Type.BUBBLES, Type.LEAVES -> 8
            Type.AURA -> 12
            Type.BLOOD -> 10
            Type.DUST -> 6
        }
        val w = frames[0].width; val h = frames[0].height
        val frameCount = frames.size
        val particles = (0 until particleCount).map {
            Particle(
                startPhase = r.nextFloat(),
                angle = (r.nextFloat() * 2 * PI).toFloat(),
                speed = r.nextFloat() * 0.5f + 0.3f,
                color = pickColor(type, r),
                drift = (r.nextFloat() - 0.5f) * 0.6f
            )
        }
        return frames.mapIndexed { fi, baseFrame ->
            val out = baseFrame.copy()
            val tNorm = fi.toFloat() / frameCount.coerceAtLeast(1)
            particles.forEach { p ->
                val localT = ((tNorm + p.startPhase) % 1f)
                drawParticle(out, type, p, localT, cx, cy, w, h)
            }
            out
        }
    }

    private fun pickColor(type: Type, r: Random): Int {
        val choices: IntArray = when (type) {
            Type.SPARKLES -> intArrayOf(0xFFFFEB80.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFC95C.toInt())
            Type.SMOKE -> intArrayOf(0xFFAAAAAA.toInt(), 0xFFCCCCCC.toInt(), 0xFF888888.toInt())
            Type.EMBERS -> intArrayOf(0xFFFF6B22.toInt(), 0xFFFFD93D.toInt(), 0xFFCC2222.toInt())
            Type.SNOW -> intArrayOf(0xFFFFFFFF.toInt(), 0xFFE0F4FF.toInt())
            Type.AURA -> intArrayOf(0xFFCC66CC.toInt(), 0xFFAA22EE.toInt(), 0xFFFF00FF.toInt(), 0xFFCC00FF.toInt())
            Type.BLOOD -> intArrayOf(0xFFAA2222.toInt(), 0xFFCC3333.toInt(), 0xFF881111.toInt())
            Type.DUST -> intArrayOf(0xFFB8945C.toInt(), 0xFFC0AC8B.toInt(), 0xFFA08568.toInt())
            Type.STARS -> intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFE5B4.toInt(), 0xFFB4D8FF.toInt(), 0xFFFFB4D8.toInt())
            Type.BUBBLES -> intArrayOf(0xFFE0F4FF.toInt(), 0xFFAACCEE.toInt(), 0xFFFFFFFF.toInt())
            Type.LEAVES -> intArrayOf(0xFFD64545.toInt(), 0xFFCC8833.toInt(), 0xFFFFAA33.toInt(), 0xFF8B5A2B.toInt())
        }
        return choices[r.nextInt(choices.size)]
    }

    private fun drawParticle(frame: Frame, type: Type, p: Particle, t: Float, cx: Int, cy: Int, w: Int, h: Int) {
        val u = max(1, frame.height / 16).toFloat()
        var px = cx; var py = cy
        when (type) {
            Type.SPARKLES, Type.STARS -> {
                val radius = u * (2f + t * 2f)
                val a = p.angle + t * 2f * PI.toFloat()
                px = cx + (cos(a) * radius).roundToInt()
                py = cy + (sin(a) * radius * 0.5f).roundToInt()
            }
            Type.SMOKE, Type.EMBERS -> {
                px = cx + (p.drift * u * 4f * t).roundToInt()
                py = cy - (t * u * 6f).roundToInt()
            }
            Type.SNOW, Type.LEAVES -> {
                px = cx + (sin(t * 4f * PI.toFloat() + p.angle) * u * 2f).roundToInt()
                py = cy + (t * u * 6f).roundToInt() - (u * 3f).toInt()
            }
            Type.AURA -> {
                val radius = u * (1f + t * 3f)
                px = cx + (cos(p.angle) * radius).roundToInt()
                py = cy + (sin(p.angle) * radius).roundToInt() - (t * u).toInt()
            }
            Type.BLOOD -> {
                val radius = u * t * 4f
                px = cx + (cos(p.angle) * radius).roundToInt()
                py = cy + (sin(p.angle) * radius).roundToInt() + (t * t * u * 4f).roundToInt()
            }
            Type.DUST -> {
                px = cx + (p.drift * u * 3f).roundToInt()
                py = cy + (u * 2f - t * u * 3f).roundToInt()
            }
            Type.BUBBLES -> {
                px = cx + (sin(t * 6f * PI.toFloat() + p.angle) * u).roundToInt()
                py = cy - (t * u * 5f).roundToInt()
            }
        }
        setPx(frame, px, py, p.color, w, h)
        when (type) {
            Type.EMBERS -> setPx(frame, px, py + 1, (p.color and 0x00FFFFFF) or 0xFF884400.toInt(), w, h)
            Type.SPARKLES -> {
                setPx(frame, px + 1, py, p.color, w, h)
                setPx(frame, px, py + 1, p.color, w, h)
            }
            else -> {}
        }
    }

    private fun setPx(frame: Frame, x: Int, y: Int, c: Int, w: Int, h: Int) {
        if (x in 0 until w && y in 0 until h) frame.set(x, y, c)
    }
}
