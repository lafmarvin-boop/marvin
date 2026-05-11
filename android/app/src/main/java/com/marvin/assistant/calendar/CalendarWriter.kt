package com.marvin.assistant.calendar

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.TimeZone

/**
 * Création d'événements dans le calendrier Android.
 *
 * Utilise le compte calendrier "primary" de l'utilisateur. Si pas de
 * compte principal, prend le premier dispo. Permission WRITE_CALENDAR
 * requise (déclarée au manifest).
 */
class CalendarWriter(private val context: Context) {

    /**
     * Crée un événement. Renvoie une description du résultat pour TTS.
     */
    fun createEvent(title: String, startMs: Long, durationMinutes: Int = 60): String {
        if (!hasPermission()) {
            return "Permission d'écriture du calendrier refusée. Va dans Paramètres → Apps → Marvin → Permissions."
        }
        val calendarId = primaryCalendarId()
            ?: return "Je n'ai pas trouvé de calendrier disponible."

        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, startMs)
            put(CalendarContract.Events.DTEND, startMs + durationMinutes * 60_000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }

        return try {
            val uri = context.contentResolver.insert(
                CalendarContract.Events.CONTENT_URI, values
            )
            if (uri != null) {
                val df = java.text.SimpleDateFormat("HH'h'mm 'le' EEEE d MMMM",
                    java.util.Locale.FRENCH)
                "OK, j'ai ajouté « $title » ${df.format(java.util.Date(startMs))}."
            } else {
                "Échec de la création de l'événement."
            }
        } catch (t: Throwable) {
            Log.e(TAG, "createEvent failed", t)
            "Erreur lors de la création : ${t.message}"
        }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    private fun primaryCalendarId(): Long? {
        val cur = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.IS_PRIMARY,
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
            ),
            null, null, null
        ) ?: return null

        cur.use {
            var firstWritable: Long? = null
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val isPrimary = it.getInt(1) == 1
                val accessLevel = it.getInt(2)
                if (isPrimary && accessLevel >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                    return id
                }
                if (firstWritable == null &&
                    accessLevel >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                    firstWritable = id
                }
            }
            return firstWritable
        }
    }

    companion object { private const val TAG = "CalendarWriter" }
}
