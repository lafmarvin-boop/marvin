package com.marvin.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.marvin.assistant.service.AssistantService

class MarvinApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        com.marvin.assistant.crash.CrashReporter(this).install()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return

        // Channel pour la notif persistante "Marvin écoute".
        val persistent = NotificationChannel(
            getString(R.string.notification_channel_id),
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notification persistante pendant que Marvin écoute le mot d'activation."
            setShowBadge(false)
        }
        nm.createNotificationChannel(persistent)

        // Channel HIGH pour l'écran "réacteur" qui s'ouvre via fullScreenIntent —
        // doit être en IMPORTANCE_HIGH sinon Android n'autorise pas le full-screen.
        val visual = NotificationChannel(
            AssistantService.VISUAL_CHANNEL_ID,
            "Marvin – écran d'interaction",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Affiche le réacteur Jarvis quand tu déclenches le wake word, " +
                "même écran verrouillé."
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(visual)
    }
}
