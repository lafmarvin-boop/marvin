package com.marvin.budget.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.marvin.budget.data.model.Account
import com.marvin.budget.data.model.Transaction
import com.marvin.budget.data.repository.BudgetRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class TransactionsUiState(
    val title: String = "Toutes les transactions",
    val transactions: List<Transaction> = emptyList(),
    val accountsById: Map<String, Account> = emptyMap()
)

class TransactionsViewModel(
    repo: BudgetRepository,
    private val accountId: String?
) : ViewModel() {

    val state: StateFlow<TransactionsUiState> =
        combine(
            if (accountId == null) repo.transactions() else repo.transactionsForAccount(accountId),
            repo.accounts()
        ) { txs, accs ->
            val map = accs.associateBy { it.id }
            TransactionsUiState(
                title = accountId?.let { map[it]?.displayName } ?: "Toutes les transactions",
                transactions = txs,
                accountsById = map
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionsUiState())

    companion object {
        fun factory(repo: BudgetRepository, accountId: String?) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                TransactionsViewModel(repo, accountId) as T
        }
    }
}
