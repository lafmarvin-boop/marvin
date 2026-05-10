package com.marvin.assistant.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Gestion des rappels / réveils / timers persistants.
 *
 * - Stockés chiffrés dans EncryptedSharedPreferences (les rappels peuvent
 *   contenir des infos perso : « rappelle-moi le RDV chez le médecin »).
 * - Programmés via AlarmManager.setExactAndAllowWhileIdle pour fonctionner
 *   même en doze mode (économie d'énergie agressive Samsung).
 * - Le tir déclenche [ReminderReceiver] qui notifie + parle via TTS.
 *
 * Note Android 12+ : setExactAndAllowWhileIdle nécessite SCHEDULE_EXACT_ALARM
 * (auto-accordée) ou USE_EXACT_ALARM (impossible auto, mais auto pour les
 * apps de calendrier/alarme). On utilise SCHEDULE_EXACT_ALARM.
 */
class RemindersManager(private val context: Context) {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, PREFS_NAME, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    data class Reminder(val id: Int, val text: String, val triggerAtMs: Long) {
        fun describe(): String {
            val df = SimpleDateFormat("HH:mm 'le' EEEE d MMMM", Locale.FRENCH)
            return "« $text » à ${df.format(Date(triggerAtMs))}"
        }
    }

    /** Liste tous les rappels actifs (futurs). */
    fun all(): List<Reminder> {
        val raw = prefs.getString(KEY_LIST, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Throwable) { JSONArray() }
        val now = System.currentTimeMillis()
        val out = mutableListOf<Reminder>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val r = Reminder(
                id = o.optInt("id"),
                text = o.optString("text"),
                triggerAtMs = o.optLong("trigger")
            )
            if (r.triggerAtMs > now) out.add(r) // garde uniquement les futurs
        }
        return out.sortedBy { it.triggerAtMs }
    }

    /** Ajoute et programme un rappel. Renvoie le rappel créé. */
    fun add(text: String, triggerAtMs: Long): Reminder {
        val id = nextId()
        val r = Reminder(id, text, triggerAtMs)
        save(all() + r)
        scheduleAlarm(r)
        Log.i(TAG, "Rappel ajouté : ${r.describe()} (id=$id)")
        return r
    }

    /** Supprime un rappel (et annule l'alarme). */
    fun remove(id: Int) {
        val current = all()
        val toRemove = current.firstOrNull { it.id == id } ?: return
        save(current - toRemove)
        cancelAlarm(toRemove)
    }

    /** Vide tous les rappels. */
    fun clearAll() {
        all().forEach { cancelAlarm(it) }
        save(emptyList())
    }

    /**
     * Reprogramme tous les rappels à la volée (utile au boot / après mise à
     * jour de l'app où AlarmManager perd ses entrées).
     */
    fun rescheduleAll() {
        all().forEach { scheduleAlarm(it) }
    }

    private fun save(list: List<Reminder>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(JSONObject().apply {
                put("id", r.id)
                put("text", r.text)
                put("trigger", r.triggerAtMs)
            })
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply()
    }

    private fun nextId(): Int {
        val next = prefs.getInt(KEY_NEXT_ID, 1000)
        prefs.edit().putInt(KEY_NEXT_ID, next + 1).apply()
        return next
    }

    private fun scheduleAlarm(r: Reminder) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntentFor(r)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.triggerAtMs, pi)
                } else {
                    // Fallback : alarme inexacte (peut être différée jusqu'à 15 min)
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.triggerAtMs, pi)
                    Log.w(TAG, "Pas de permission alarme exacte — fallback inexact")
                }
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.triggerAtMs, pi)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "scheduleAlarm failed", t)
        }
    }

    private fun cancelAlarm(r: Reminder) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pendingIntentFor(r))
    }

    private fun pendingIntentFor(r: Reminder): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_FIRE
            putExtra(ReminderReceiver.EXTRA_ID, r.id)
            putExtra(ReminderReceiver.EXTRA_TEXT, r.text)
        }
        return PendingIntent.getBroadcast(
            context, r.id, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    companion object {
        private const val TAG = "Reminders"
        private const val PREFS_NAME = "marvin_reminders"
        private const val KEY_LIST = "list"
        private const val KEY_NEXT_ID = "next_id"
    }
}
