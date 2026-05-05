package com.marvin.assistant.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.Collections

/**
 * Capture les notifications actives sur le téléphone pour qu'elles puissent
 * être lues par l'outil `get_unread_notifications`.
 *
 * Activation: Paramètres → Apps → Accès spécial → Accès aux notifications
 * → activer Marvin. Sans ça, [activeNotifications] reste vide.
 *
 * Privacy: les contenus restent en RAM dans cette JVM. Ils ne quittent
 * l'appareil que lorsque Claude appelle l'outil — auquel cas ils sont
 * envoyés à api.anthropic.com.
 */
class NotificationCaptureService : NotificationListenerService() {

    override fun onListenerConnected() {
        Log.i(TAG, "Notification listener connected")
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // No-op: on lit la liste à la demande via getActiveNotifications().
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
