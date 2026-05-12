package com.marvin.budget.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate

enum class TxCategory(val label: String) {
    GROCERIES("Courses"),
    RESTAURANT("Restaurants"),
    TRANSPORT("Transport"),
    HOUSING("Logement"),
    UTILITIES("Factures"),
    LEISURE("Loisirs"),
    HEALTH("Santé"),
    SHOPPING("Shopping"),
    SUBSCRIPTIONS("Abonnements"),
    TRANSFER("Virement"),
    SALARY("Salaire"),
    REFUND("Remboursement"),
    OTHER("Autre");
}

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = Account::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("accountId"), Index("date")]
)
data class Transaction(
    @PrimaryKey val id: String,
    val accountId: String,
    val date: LocalDate,
    /** Positive = credit (income), negative = debit (expense). */
    val amount: Double,
    val currency: String,
    val counterparty: String,
    val description: String,
    val category: TxCategory,
    /** True when the transaction is booked but not yet effective (carte différée). */
    val isPending: Boolean
) {
    val isIncome: Boolean get() = amount > 0
    val isExpense: Boolean get() = amount < 0
}
