package com.marvin.sport

import android.app.Application
import com.marvin.sport.data.RunRepository

class MarvinApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RunRepository.init(this)
    }
}
