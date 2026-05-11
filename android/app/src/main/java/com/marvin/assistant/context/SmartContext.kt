package com.marvin.assistant.context

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.provider.CalendarContract
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.Calendar

/**
 * Détection automatique du contexte de l'utilisateur pour adapter le
 * comportement de Jarvis sans qu'on ait à le dire.
 *
 * Vérifie en temps réel :
 *  - Heure du jour (matin / journée / soir / nuit)
 *  - Évènement calendrier en cours (= en réunion → silence sauf urgence)
 *  - Appel téléphonique en cours (= occupé → ne pas trigger TTS)
 *  - Niveau de batterie (< 15 % → mode économie)
 *  - Jour ouvré vs week-end
 *
 * Renvoie un [Context] que les composants peuvent consulter pour adapter
 * leur comportement (system prompt enrichi, TTS plus court, etc.).
 *
 * C'est une feature originale : les autres assistants n'adaptent pas leur
 * comportement à ton contexte sans config explicite.
 */
class SmartContext(private val context: Context) {

    enum class TimeOfDay { NIGHT, MORNING, DAYTIME, EVENING }
    enum class BatteryLevel { CRITICAL, LOW, NORMAL, FULL }
    enum class CallStatus { IDLE, RINGING, OFF_HOOK }

    data class Snapshot(
        val timeOfDay: TimeOfDay,
        val inMeeting: Boolean,
        val meetingTitle: String?,
        val callStatus: CallStatus,
        val battery: BatteryLevel,
        val batteryPercent: Int,
        val isWeekend: Boolean
    ) {
        /** Doit-on rester silencieux par défaut ? */
        fun shouldStaySilent(): Boolean = inMeeting || callStatus == CallStatus.OFF_HOOK

        /** Format compact pour le system prompt Claude. */
        fun toSystemNote(): String {
            val parts = mutableListOf<String>()
            parts.add(when (timeOfDay) {
                TimeOfDay.MORNING -> "C'est le matin"
                TimeOfDay.DAYTIME -> "C'est la journée"
                TimeOfDay.EVENING -> "C'est le soir"
                TimeOfDay.NIGHT -> "C'est la nuit"
            })
            if (isWeekend) parts.add("On est en week-end")
            if (inMeeting) parts.add("L'utilisateur est en réunion : « ${meetingTitle ?: "inconnu"} » — sois bref et discret")
            if (callStatus == CallStatus.OFF_HOOK) parts.add("L'utilisateur est en appel téléphonique")
            if (battery == BatteryLevel.CRITICAL) parts.add("Batterie critique (< 5 %), reste très bref")
            else if (battery == BatteryLevel.LOW) parts.add("Batterie faible ($batteryPercent %), sois concis")
            return parts.joinToString(". ") + "."
        }
    }

    fun snapshot(): Snapshot {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val time = when (hour) {
            in 22..23, in 0..5 -> TimeOfDay.NIGHT
            in 6..11 -> TimeOfDay.MORNING
            in 12..17 -> TimeOfDay.DAYTIME
            else -> TimeOfDay.EVENING
        }
        val weekend = Calendar.getInstance().get(Calendar.DAY_OF_WEEK).let {
            it == Calendar.SATURDAY || it == Calendar.SUNDAY
        }
        val (inMeeting, meetingTitle) = currentMeeting()
        val callStatus = callStatus()
        val pct = batteryPercent()
        val battery = when {
            pct < 5 -> BatteryLevel.CRITICAL
            pct < 15 -> BatteryLevel.LOW
            pct >= 95 -> BatteryLevel.FULL
            else -> BatteryLevel.NORMAL
        }
        return Snapshot(time, inMeeting, meetingTitle, callStatus, battery, pct, weekend)
    }

    private fun currentMeeting(): Pair<Boolean, String?> {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return false to null
        val now = System.currentTimeMillis()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, now - 60_000L) // marge 1 min
        ContentUris.appendId(builder, now + 60_000L)
        val cur = try {
            context.contentResolver.query(
                builder.build(),
                arrayOf(
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END
                ),
                null, null, null
            )
        } catch (t: Throwable) {
            Log.w(TAG, "calendar query failed", t); null
        } ?: return false to null
        cur.use {
            if (it.moveToFirst()) {
                val title = it.getString(0)
                val begin = it.getLong(1)
                val end = it.getLong(2)
                if (now in begin..end) return true to title
            }
        }
        return false to null
    }

    private fun callStatus(): CallStatus {
        return try {
            val tm = context.getSystemService(TelephonyManager::class.java) ?: return CallStatus.IDLE
            // callState requires READ_PHONE_STATE on Android 12+, mais l'API
            // existe quand meme. Si refusee, retourne IDLE silencieusement.
            @Suppress("DEPRECATION")
            when (tm.callState) {
                TelephonyManager.CALL_STATE_IDLE -> CallStatus.IDLE
                TelephonyManager.CALL_STATE_RINGING -> CallStatus.RINGING
                TelephonyManager.CALL_STATE_OFFHOOK -> CallStatus.OFF_HOOK
                else -> CallStatus.IDLE
            }
        } catch (_: Throwable) { CallStatus.IDLE }
    }

    private fun batteryPercent(): Int {
        return try {
            val bm = context.getSystemService(BatteryManager::class.java) ?: return 50
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Throwable) { 50 }
    }

    companion object { private const val TAG = "SmartContext" }
}
