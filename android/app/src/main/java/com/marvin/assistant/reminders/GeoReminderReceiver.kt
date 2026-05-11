package com.marvin.assistant.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.util.Log
import com.google.android.gms.location.GeofencingEvent
import java.util.Locale

/**
 * Reçoit le déclenchement d'un geofence et annonce le rappel via TTS.
 */
class GeoReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent)
        if (event == null || event.hasError()) {
            Log.w(TAG, "Event invalide ou erreur")
            return
        }
        val text = intent.getStringExtra("text") ?: return
        Log.i(TAG, "Geo reminder fire : $text")
        announce(context, "Rappel à proximité : $text")
    }

    private fun announce(context: Context, phrase: String) {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status != TextToSpeech.SUCCESS) { tts?.shutdown(); return@TextToSpeech }
            tts!!.language = Locale.FRENCH
            tts!!.speak(phrase, TextToSpeech.QUEUE_ADD, null, "geo")
            android.os.Handler(context.mainLooper).postDelayed({
                runCatching { tts!!.shutdown() }
            }, 8_000L)
        }
    }

    companion object {
        private const val TAG = "GeoReminderRx"
        const val ACTION_GEO_FIRE = "com.marvin.assistant.GEO_REMINDER_FIRE"
    }
}
