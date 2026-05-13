package com.pixelhero.app

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Animation easing functions. Replaces pure sine waves with more natural
 * motion curves: ease-in (slow start), ease-out (slow end), back (overshoot),
 * elastic (oscillation), bounce, etc.
 *
 * All functions take t in [0..1] and return a value in [0..1]
 * (except elastic/back which can overshoot slightly).
 */
object Easing {

    enum class Curve(val displayName: String) {
        LINEAR("Linéaire"),
        SINE("Sinusoïde (défaut)"),
        EASE_IN("Ease-in (départ lent)"),
        EASE_OUT("Ease-out (arrivée lente)"),
        EASE_IN_OUT("Ease-in-out (lent aux 2 bouts)"),
        BACK_OUT("Back-out (dépassement)"),
        ELASTIC_OUT("Élastique (oscille)"),
        BOUNCE("Rebond")
    }

    /** Apply curve to t in [0..1]. */
    fun apply(curve: Curve, t: Float): Float {
        val tt = t.coerceIn(0f, 1f)
        return when (curve) {
            Curve.LINEAR -> tt
            Curve.SINE -> 0.5f - 0.5f * cos((tt * PI).toFloat())
            Curve.EASE_IN -> tt * tt * tt
            Curve.EASE_OUT -> {
                val inv = 1f - tt
                1f - inv * inv * inv
            }
            Curve.EASE_IN_OUT -> {
                if (tt < 0.5f) 4f * tt * tt * tt
                else 1f - (-2f * tt + 2f).pow(3f) / 2f
            }
            Curve.BACK_OUT -> {
                val c1 = 1.70158f
                val c3 = c1 + 1f
                1f + c3 * (tt - 1f).pow(3f) + c1 * (tt - 1f).pow(2f)
            }
            Curve.ELASTIC_OUT -> {
                if (tt == 0f) 0f
                else if (tt == 1f) 1f
                else {
                    val c4 = (2f * PI / 3f).toFloat()
                    2f.pow(-10f * tt) * sin((tt * 10f - 0.75f) * c4) + 1f
                }
            }
            Curve.BOUNCE -> bounceOut(tt)
        }
    }

    private fun bounceOut(t: Float): Float {
        val n1 = 7.5625f
        val d1 = 2.75f
        return when {
            t < 1f / d1 -> n1 * t * t
            t < 2f / d1 -> { val u = t - 1.5f / d1; n1 * u * u + 0.75f }
            t < 2.5f / d1 -> { val u = t - 2.25f / d1; n1 * u * u + 0.9375f }
            else -> { val u = t - 2.625f / d1; n1 * u * u + 0.984375f }
        }
    }

    /**
     * Apply the curve to a periodic value (returns to start at t=1).
     * Useful for breathing/idle animations.
     */
    fun applyPeriodic(curve: Curve, t: Float): Float {
        // Halve the interval: 0..0.5 -> 0..1 ease in/out, 0.5..1 -> 1..0 reverse
        val tt = t.coerceIn(0f, 1f)
        return if (tt <= 0.5f) apply(curve, tt * 2f)
        else apply(curve, (1f - tt) * 2f)
    }
}
