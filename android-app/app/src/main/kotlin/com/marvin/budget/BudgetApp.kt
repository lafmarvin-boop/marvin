package com.marvin.budget

import android.app.Application
import com.marvin.budget.di.AppContainer

class BudgetApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
