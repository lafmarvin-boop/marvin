package com.marvin.sport.data

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Sons et vibrations courts utilisés pour les alertes en jeu (timer fractionné,
 * jalons de distance pendant une course). Tout sort sur le canal Musique pour
 * être audible avec un casque Bluetooth pendant la course.
 */
object AlertSound {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Bip court (200 ms par défaut). */
    fun beep(durationMs: Int = 200) {
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }.getOrNull() ?: return
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, durationMs)
        mainHandler.postDelayed({ runCatching { tone.release() } }, (durationMs + 150).toLong())
    }

    /** Bip plus marqué (500 ms) pour les jalons importants. */
    fun beepStrong(durationMs: Int = 500) {
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }.getOrNull() ?: return
        tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, durationMs)
        mainHandler.postDelayed({ runCatching { tone.release() } }, (durationMs + 150).toLong())
    }

    @Suppress("DEPRECATION")
    fun vibrate(context: Context, strong: Boolean) {
        val source: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            mgr?.defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        val vib = source ?: return
        val durations = if (strong) longArrayOf(0, 250, 100, 250) else longArrayOf(0, 150)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vib.vibrate(VibrationEffect.createWaveform(durations, -1))
        } else {
            vib.vibrate(durations, -1)
        }
    }

    /**
     * Alerte de jalon de course :
     *   25 %  → 1 bip court
     *   50 %  → 2 bips courts
     *   75 %  → 3 bips courts
     *   100 % → bip long marqué + vibration forte
     */
    fun milestoneAlert(context: Context, percent: Int) {
        when (percent) {
            100 -> {
                beepStrong(550)
                vibrate(context, strong = true)
            }
            75 -> {
                beep(180)
                mainHandler.postDelayed({ beep(180) }, 280)
                mainHandler.postDelayed({ beep(180) }, 560)
                vibrate(context, strong = false)
            }
            50 -> {
                beep(180)
                mainHandler.postDelayed({ beep(180) }, 280)
                vibrate(context, strong = false)
            }
            else -> {
                beep(180)
                vibrate(context, strong = false)
            }
        }
    }
}
