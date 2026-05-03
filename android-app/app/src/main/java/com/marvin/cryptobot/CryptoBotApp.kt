package com.marvin.cryptobot

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.marvin.cryptobot.data.AppContainer

class CryptoBotApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bot trading",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Notifications du bot DCA" }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val CHANNEL_ID = "cryptobot_default"
        lateinit var instance: CryptoBotApp
            private set
    }
}
