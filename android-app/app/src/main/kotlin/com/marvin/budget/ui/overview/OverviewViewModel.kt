package com.marvin.budget.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.marvin.budget.data.repository.BudgetRepository
import com.marvin.budget.data.repository.CategoryTotal
import com.marvin.budget.data.repository.MonthlyTotals
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth

data class OverviewUiState(
    val monthly: List<MonthlyTotals> = emptyList(),
    val combinedBalance: Double = 0.0,
    val categories: List<CategoryTotal> = emptyList(),
    val accountsCount: Int = 0
)

class OverviewViewModel(private val repo: BudgetRepository) : ViewModel() {

    val state: StateFlow<OverviewUiState> = combine(
        repo.monthlyTotals(months = 6),
        repo.combinedBalance(),
        repo.categoryBreakdown(YearMonth.now()),
        repo.accounts()
    ) { monthly, balance, categories, accounts ->
        OverviewUiState(
            monthly = monthly,
            combinedBalance = balance,
            categories = categories,
            accountsCount = accounts.size
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = OverviewUiState()
    )

    companion object {
        fun factory(repo: BudgetRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                OverviewViewModel(repo) as T
        }
    }
}
