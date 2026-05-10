package com.marvin.assistant.proactive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.util.Log
import com.marvin.assistant.util.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reçoit deux types d'événements :
 *
 *  - ACTION_SCAN (toutes les 15 min) : re-scanne le calendrier et
 *    programme les prochaines annonces.
 *
 *  - ACTION_ANNOUNCE (5 min avant un événement) : annonce vocalement
 *    « Rappel : événement X commence dans 5 minutes ».
 *
 * Respecte le toggle proactiveCalendarAnnouncementsEnabled — si OFF,
 * on ne déclenche aucune annonce (mais le scan continue pour quand le
 * toggle sera réactivé).
 */
class CalendarScanReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            CalendarWatcher.ACTION_SCAN -> handleScan(context)
            ACTION_ANNOUNCE -> handleAnnounce(context, intent)
        }
    }

    private fun handleScan(context: Context) {
        val settings = Settings(context)
        if (!settings.proactiveCalendarAnnouncementsEnabled) {
            Log.i(TAG, "Toggle off, skip scan")
            return
        }
        CalendarWatcher(context).also {
            it.scanAndScheduleEvents()
            // re-arme le prochain scan
            it.enable()
        }
    }

    private fun handleAnnounce(context: Context, intent: Intent) {
        val settings = Settings(context)
        if (!settings.proactiveCalendarAnnouncementsEnabled) return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        val begin = intent.getLongExtra(EXTRA_BEGIN, 0L)
        val time = SimpleDateFormat("HH'h'mm", Locale.FRENCH).format(Date(begin))
        val phrase = "Rappel : tu as « $title » à $time, dans 5 minutes."
        announceViaTts(context, phrase)
    }

    private fun announceViaTts(context: Context, phrase: String) {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) { tts?.shutdown(); return@TextToSpeech }
            tts!!.language = Locale.FRENCH
            tts!!.speak(phrase, TextToSpeech.QUEUE_ADD, null, "calendar")
            // shutdown au bout de 8 s (assez pour finir une annonce courte)
            android.os.Handler(context.mainLooper).postDelayed({
                runCatching { tts!!.shutdown() }
            }, 8_000L)
        }
    }

    companion object {
        private const val TAG = "CalendarScan"
        const val ACTION_ANNOUNCE = "com.marvin.assistant.CALENDAR_ANNOUNCE"
        const val EXTRA_TITLE = "title"
        const val EXTRA_BEGIN = "begin"
    }
}
