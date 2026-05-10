package com.marvin.assistant.reminders

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.marvin.assistant.R
import com.marvin.assistant.ui.MainActivity
import java.util.Locale

/**
 * BroadcastReceiver déclenché par AlarmManager quand un rappel arrive.
 *
 * Ce qu'il fait :
 *  - Acquiert un wake lock court (10 s) pour réveiller le device
 *  - Poste une notification heads-up avec full-screen-intent
 *  - Énonce le rappel via Android TTS (le service Marvin peut être tué,
 *    on ne peut pas compter dessus pour la lecture — Android TTS est
 *    système, toujours dispo)
 *
 * On NE relance pas le service Marvin ici parce qu'il consomme du CPU
 * en continu et l'utilisateur peut juste vouloir un rappel ponctuel.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getIntExtra(EXTRA_ID, 0)
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return

        Log.i(TAG, "Reminder fire: id=$id text=\"$text\"")

        // Wake lock court pour s'assurer qu'on a le temps de notifier + parler
        val pm = context.getSystemService(PowerManager::class.java)
        val wl = pm?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "marvin:reminder-$id"
        )?.apply { acquire(15_000L) }

        try {
            ensureChannel(context)
            postNotification(context, id, text)
            speakReminder(context, text) {
                // Relâche le wake lock après TTS
                wl?.runCatching { release() }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to fire reminder", t)
            wl?.runCatching { release() }
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val ch = NotificationChannel(
            CHANNEL_ID, "Rappels Jarvis", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Rappels et timers déclenchés par Jarvis"
            setShowBadge(true)
        }
        nm.createNotificationChannel(ch)
    }

    private fun postNotification(context: Context, id: Int, text: String) {
        val openIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(
            context, id, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notif = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Rappel Jarvis")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setPriority(Notification.PRIORITY_MAX)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java)
            ?.notify(NOTIF_BASE + id, notif)
    }

    private fun speakReminder(context: Context, text: String, onDone: () -> Unit) {
        // Android TTS plutôt que PiperTtsEngine : pas de dépendance au
        // service Marvin (qui peut être mort), toujours dispo, et c'est
        // un cas ponctuel donc qualité acceptable.
        var tts: TextToSpeech? = null
        tts = TextToSpeech(context) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Log.e(TAG, "TTS init failed status=$status")
                onDone(); return@TextToSpeech
            }
            tts!!.language = Locale.FRENCH
            tts!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    tts!!.shutdown()
                    onDone()
                }
                @Deprecated("legacy") override fun onError(utteranceId: String?) {
                    tts!!.shutdown(); onDone()
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    tts!!.shutdown(); onDone()
                }
            })
            val phrase = "Rappel : $text"
            tts!!.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "reminder")
        }
    }

    companion object {
        private const val TAG = "ReminderReceiver"
        const val ACTION_FIRE = "com.marvin.assistant.REMINDER_FIRE"
        const val EXTRA_ID = "reminder_id"
        const val EXTRA_TEXT = "reminder_text"
        private const val CHANNEL_ID = "marvin_reminders"
        private const val NOTIF_BASE = 0x4A00 // 'J' for Jarvis reminders
    }
}
