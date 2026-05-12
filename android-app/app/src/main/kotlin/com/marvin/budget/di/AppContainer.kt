package com.marvin.budget.di

import android.content.Context
import com.marvin.budget.data.local.AppDatabase
import com.marvin.budget.data.remote.GoCardlessApi
import com.marvin.budget.data.remote.RetrofitFactory
import com.marvin.budget.data.repository.BudgetRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    private val database: AppDatabase = AppDatabase.get(context)

    val goCardlessApi: GoCardlessApi by lazy { RetrofitFactory.buildGoCardless() }

    val repository: BudgetRepository = BudgetRepository(
        accountDao = database.accountDao(),
        transactionDao = database.transactionDao()
    )

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        appScope.launch { repository.seedIfEmpty() }
    }
}
