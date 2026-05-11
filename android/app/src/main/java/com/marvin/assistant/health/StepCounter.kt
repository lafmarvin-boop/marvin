package com.marvin.assistant.health

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Lit le capteur Step Counter Android (count cumule depuis le boot
 * du device). Pour avoir le delta du jour, on stocke la valeur de
 * minuit dans SharedPreferences au premier read de la journee.
 */
class StepCounter(private val context: Context) {

    /** Renvoie une phrase TTS-friendly indiquant les pas du jour. */
    suspend fun stepsToday(): String {
        val raw = currentSensorValue() ?: return "Capteur de pas indisponible sur ce téléphone."
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
            .format(java.util.Date())
        val storedDay = prefs.getString("day", "")
        val baseline = if (storedDay != today) {
            prefs.edit().putString("day", today).putInt("baseline", raw.toInt()).apply()
            raw.toInt()
        } else {
            prefs.getInt("baseline", raw.toInt())
        }
        val today_steps = (raw.toInt() - baseline).coerceAtLeast(0)
        return "Tu as fait $today_steps pas aujourd'hui."
    }

    private suspend fun currentSensorValue(): Float? = suspendCancellableCoroutine { cont ->
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (sensor == null) { cont.resume(null); return@suspendCancellableCoroutine }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                sm.unregisterListener(this)
                cont.resume(event.values.firstOrNull())
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST)
        cont.invokeOnCancellation { sm.unregisterListener(listener) }
    }

    companion object { private const val PREFS = "marvin_steps" }
}
