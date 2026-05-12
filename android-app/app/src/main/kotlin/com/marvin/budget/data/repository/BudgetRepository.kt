package com.marvin.budget.data.repository

import com.marvin.budget.data.local.AccountDao
import com.marvin.budget.data.local.TransactionDao
import com.marvin.budget.data.mock.MockData
import com.marvin.budget.data.model.Account
import com.marvin.budget.data.model.Transaction
import com.marvin.budget.data.model.TxCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.YearMonth

data class MonthlyTotals(
    val month: YearMonth,
    val income: Double,
    val expense: Double
) {
    val net: Double get() = income - expense
}

data class CategoryTotal(val category: TxCategory, val amount: Double)

data class AccountWithBalance(
    val account: Account,
    val pendingDelta: Double,
    val transactionCount: Int
) {
    /** Solde "à venir" en tenant compte des transactions différées encore non effectives. */
    val effectiveBalance: Double get() = account.balance + pendingDelta
}

class BudgetRepository(
    private val accountDao: AccountDao,
    private val transactionDao: TransactionDao
) {
    fun accounts(): Flow<List<Account>> = accountDao.observeAll()

    fun transactions(): Flow<List<Transaction>> = transactionDao.observeAll()

    fun transactionsForAccount(accountId: String): Flow<List<Transaction>> =
        transactionDao.observeByAccount(accountId)

    fun accountsWithBalances(): Flow<List<AccountWithBalance>> =
        accounts().combine(transactions()) { accs, txs ->
            accs.map { acc ->
                val accountTxs = txs.filter { it.accountId == acc.id }
                AccountWithBalance(
                    account = acc,
                    pendingDelta = accountTxs.filter { it.isPending }.sumOf { it.amount },
                    transactionCount = accountTxs.size
                )
            }
        }

    /** Total combiné = somme algébrique des soldes (les cartes différées sont en négatif). */
    fun combinedBalance(): Flow<Double> =
        accounts().map { it.sumOf { acc -> acc.balance } }

    fun monthlyTotals(months: Int = 6): Flow<List<MonthlyTotals>> =
        transactions().map { txs ->
            val today = LocalDate.now()
            (0 until months).map { offset ->
                val ym = YearMonth.from(today).minusMonths((months - 1 - offset).toLong())
                val monthTxs = txs.filter { YearMonth.from(it.date) == ym }
                MonthlyTotals(
                    month = ym,
                    income = monthTxs.filter { it.isIncome }.sumOf { it.amount },
                    expense = -monthTxs.filter { it.isExpense }.sumOf { it.amount }
                )
            }
        }

    fun categoryBreakdown(yearMonth: YearMonth): Flow<List<CategoryTotal>> =
        transactions().map { txs ->
            txs.filter { it.isExpense && YearMonth.from(it.date) == yearMonth }
                .groupBy { it.category }
                .map { (cat, list) -> CategoryTotal(cat, -list.sumOf { it.amount }) }
                .sortedByDescending { it.amount }
        }

    /**
     * Seed la base avec des données mock si elle est vide.
     * Remplacer par un vrai sync GoCardless une fois les credentials configurés.
     */
    suspend fun seedIfEmpty() {
        accountDao.upsertAll(MockData.accounts)
        transactionDao.upsertAll(MockData.transactions)
    }
}
