package com.marvin.assistant.proactive

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.TimeUnit

/**
 * Scrute le calendrier toutes les 15 min (via AlarmManager) et programme
 * une annonce vocale 5 min avant chaque événement à venir dans les 4
 * prochaines heures.
 *
 * Pourquoi 15 min : compromis entre réactivité et conso batterie.
 * Les événements ajoutés < 5 min avant qu'ils commencent ne seront pas
 * annoncés, mais c'est rare.
 *
 * Pourquoi 4 h de fenêtre : limite raisonnable, on ne veut pas
 * programmer 100 alarmes pour toute la semaine.
 */
class CalendarWatcher(private val context: Context) {

    fun enable() {
        scheduleNextScan()
        scanAndScheduleEvents()
    }

    fun disable() {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(scanPendingIntent())
        // Note : les alarmes de notif déjà programmées resteront, mais
        // ne déclenchent rien si le toggle proactif est OFF côté receiver.
    }

    /** Programme la prochaine analyse du calendrier dans 15 min. */
    private fun scheduleNextScan() {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = System.currentTimeMillis() + SCAN_INTERVAL_MS
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, scanPendingIntent())
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, scanPendingIntent())
            }
        } catch (t: Throwable) {
            Log.e(TAG, "scheduleNextScan failed", t)
        }
    }

    private fun scanPendingIntent(): PendingIntent {
        val intent = Intent(context, CalendarScanReceiver::class.java).apply {
            action = ACTION_SCAN
        }
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE_SCAN, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** Lit les événements à venir dans les 4 prochaines heures et programme
     *  une annonce 5 min avant chacun. */
    fun scanAndScheduleEvents() {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.i(TAG, "READ_CALENDAR refusée, skip")
            return
        }
        val now = System.currentTimeMillis()
        val until = now + WINDOW_MS
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, now)
        ContentUris.appendId(builder, until)
        val cursor = context.contentResolver.query(
            builder.build(),
            arrayOf(
                CalendarContract.Instances.EVENT_ID,
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN
            ),
            null, null,
            "${CalendarContract.Instances.BEGIN} ASC"
        ) ?: return

        var scheduled = 0
        cursor.use {
            while (it.moveToNext()) {
                val eventId = it.getLong(0)
                val title = it.getString(1) ?: continue
                val begin = it.getLong(2)
                val announceAt = begin - PRE_ANNOUNCE_MS
                if (announceAt <= now) continue // déjà passé / trop tard
                scheduleEventAnnounce(eventId, title, announceAt, begin)
                scheduled++
            }
        }
        Log.i(TAG, "scanAndScheduleEvents: $scheduled événements programmés")
    }

    private fun scheduleEventAnnounce(
        eventId: Long, title: String, announceAtMs: Long, beginAtMs: Long
    ) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, CalendarScanReceiver::class.java).apply {
            action = CalendarScanReceiver.ACTION_ANNOUNCE
            putExtra(CalendarScanReceiver.EXTRA_TITLE, title)
            putExtra(CalendarScanReceiver.EXTRA_BEGIN, beginAtMs)
        }
        // requestCode unique par event pour permettre la déduplication
        val pi = PendingIntent.getBroadcast(
            context, eventId.toInt(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, announceAtMs, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, announceAtMs, pi)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "scheduleEventAnnounce failed for $title", t)
        }
    }

    companion object {
        private const val TAG = "CalendarWatcher"
        const val ACTION_SCAN = "com.marvin.assistant.CALENDAR_SCAN"
        private const val REQUEST_CODE_SCAN = 0x5CAA
        private val SCAN_INTERVAL_MS = TimeUnit.MINUTES.toMillis(15)
        private val WINDOW_MS = TimeUnit.HOURS.toMillis(4)
        private val PRE_ANNOUNCE_MS = TimeUnit.MINUTES.toMillis(5)
    }
}
