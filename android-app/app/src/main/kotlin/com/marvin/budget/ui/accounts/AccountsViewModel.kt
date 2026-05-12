package com.marvin.budget.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.marvin.budget.data.repository.AccountWithBalance
import com.marvin.budget.data.repository.BudgetRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class AccountsUiState(
    val personal: List<AccountWithBalance> = emptyList(),
    val joint: List<AccountWithBalance> = emptyList(),
    val total: Double = 0.0
)

class AccountsViewModel(repo: BudgetRepository) : ViewModel() {
    val state: StateFlow<AccountsUiState> = repo.accountsWithBalances()
        .map { list ->
            AccountsUiState(
                personal = list.filterNot { it.account.isJoint },
                joint = list.filter { it.account.isJoint },
                total = list.sumOf { it.account.balance }
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUiState())

    companion object {
        fun factory(repo: BudgetRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AccountsViewModel(repo) as T
        }
    }
}
