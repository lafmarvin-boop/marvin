package com.marvin.assistant.service

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.util.Log
import com.marvin.assistant.util.Settings
import java.util.Collections
import java.util.Locale

/**
 * Capture les notifications actives sur le téléphone pour qu'elles puissent
 * être lues par l'outil `get_unread_notifications` (Claude) OU annoncées
 * vocalement en proactif si l'utilisateur a activé le mode.
 *
 * Activation: Paramètres → Apps → Accès spécial → Accès aux notifications
 * → activer Marvin. Sans ça, [activeNotifications] reste vide et le mode
 * proactif ne déclenche rien.
 *
 * Privacy: les contenus restent en RAM. Ils ne quittent l'appareil que :
 *  - Si Claude appelle l'outil → envoyés à api.anthropic.com
 *  - JAMAIS sinon (le mode proactif est 100 % local : Android TTS).
 */
class NotificationCaptureService : NotificationListenerService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val recentlyAnnounced = mutableSetOf<String>() // dedup keys
    private var settings: Settings? = null
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    override fun onListenerConnected() {
        Log.i(TAG, "Notification listener connected")
        instance = this
        settings = Settings(applicationContext)
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        runCatching { tts?.shutdown() }
        tts = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val s = settings ?: return
        if (!s.proactiveNotificationsEnabled) return
        if (sbn.packageName !in Settings.PROACTIVE_NOTIF_PACKAGES) return
        // Dedup : la même notif peut être re-postée plusieurs fois.
        val key = "${sbn.packageName}|${sbn.id}|${sbn.postTime}"
        if (key in recentlyAnnounced) return
        recentlyAnnounced.add(key)
        if (recentlyAnnounced.size > 50) recentlyAnnounced.clear() // bornage

        val summary = summarize(sbn) ?: return
        val phrase = phraseFor(summary)
        if (phrase.isBlank()) return
        Log.i(TAG, "Proactive announce: $phrase")
        announceViaTts(phrase)
    }

    private fun phraseFor(n: NotificationSummary): String {
        val app = appLabelFromPackage(n.packageName)
        return when {
            n.packageName.contains("dialer", ignoreCase = true) ->
                "Appel manqué de ${n.title.ifBlank { "un numéro inconnu" }}."
            else -> {
                val from = n.title.ifBlank { app }
                val body = n.text.take(160)
                "Message de $from : $body"
            }
        }
    }

    private fun appLabelFromPackage(pkg: String): String = when {
        pkg.contains("whatsapp") -> "WhatsApp"
        pkg.contains("messaging") || pkg.contains("mms") -> "SMS"
        pkg.contains("dialer") -> "appels"
        else -> pkg.substringAfterLast('.')
    }

    private fun announceViaTts(phrase: String) {
        if (tts == null) {
            tts = TextToSpeech(applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.FRENCH
                    ttsReady = true
                    tts?.speak(phrase, TextToSpeech.QUEUE_ADD, null, "proactive")
                } else {
                    Log.e(TAG, "TTS init failed status=$status")
                }
            }
        } else if (ttsReady) {
            tts?.speak(phrase, TextToSpeech.QUEUE_ADD, null, "proactive")
        } else {
            // TTS pas encore prêt — réessaie dans 500 ms.
            mainHandler.postDelayed({ announceViaTts(phrase) }, 500)
        }
    }

    companion object {
        private const val TAG = "NotifCapture"

        @Volatile
        private var instance: NotificationCaptureService? = null

        fun isActive(): Boolean = instance != null

        /** Snapshot des notifications actives, au plus [max], les plus récentes d'abord. */
        fun snapshot(max: Int = 20): List<NotificationSummary> {
            val service = instance ?: return emptyList()
            val active = try {
                service.activeNotifications ?: return emptyList()
            } catch (t: Throwable) {
                Log.w(TAG, "activeNotifications failed", t); return emptyList()
            }
            return active
                .sortedByDescending { it.postTime }
                .take(max)
                .mapNotNull { sbn -> summarize(sbn) }
                .let { Collections.unmodifiableList(it) }
        }

        private fun summarize(sbn: StatusBarNotification): NotificationSummary? {
            val n = sbn.notification ?: return null
            val extras = n.extras ?: return null
            val title = extras.getCharSequence("android.title")?.toString().orEmpty()
            val text = extras.getCharSequence("android.text")?.toString().orEmpty()
            if (title.isBlank() && text.isBlank()) return null
            return NotificationSummary(
                packageName = sbn.packageName ?: "",
                title = title,
                text = text,
                postedAtMs = sbn.postTime
            )
        }
    }

    data class NotificationSummary(
        val packageName: String,
        val title: String,
        val text: String,
        val postedAtMs: Long
    )
}
