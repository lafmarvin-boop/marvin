package com.marvin.sport

import android.app.Application
import android.content.Context
import com.marvin.sport.data.RunRepository
import org.osmdroid.config.Configuration

class MarvinApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RunRepository.init(this)
        // osmdroid : user-agent obligatoire avant tout chargement de tuile OSM.
        Configuration.getInstance().apply {
            load(this@MarvinApp, getSharedPreferences("osmdroid_prefs", Context.MODE_PRIVATE))
            userAgentValue = packageName
        }
    }
}
