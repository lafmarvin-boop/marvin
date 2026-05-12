package com.marvin.budget.data.mock

import com.marvin.budget.data.model.Account
import com.marvin.budget.data.model.AccountKind
import com.marvin.budget.data.model.Transaction
import com.marvin.budget.data.model.TxCategory
import java.time.LocalDate
import java.util.UUID
import kotlin.random.Random

object MockData {
    private val now: LocalDate = LocalDate.of(2026, 5, 12)

    val accounts: List<Account> = listOf(
        Account(
            id = "acc-bnp-checking",
            institutionName = "BNP Paribas",
            displayName = "Compte courant",
            iban = "FR76 3000 4000 0312 3456 7890 143",
            currency = "EUR",
            balance = 2480.42,
            kind = AccountKind.CHECKING,
            isJoint = false,
            colorHex = "#0E7C66",
            lastSyncedAt = System.currentTimeMillis()
        ),
        Account(
            id = "acc-bnp-savings",
            institutionName = "BNP Paribas",
            displayName = "Livret A",
            iban = "FR76 3000 4000 0399 8877 6655 412",
            currency = "EUR",
            balance = 12750.00,
            kind = AccountKind.SAVINGS,
            isJoint = false,
            colorHex = "#1565C0",
            lastSyncedAt = System.currentTimeMillis()
        ),
        Account(
            id = "acc-revolut",
            institutionName = "Revolut",
            displayName = "Carte différée",
            iban = null,
            currency = "EUR",
            balance = -312.18,
            kind = AccountKind.CREDIT_CARD,
            isJoint = false,
            colorHex = "#7B1FA2",
            lastSyncedAt = System.currentTimeMillis()
        ),
        Account(
            id = "acc-ca-joint",
            institutionName = "Crédit Agricole",
            displayName = "Compte commun",
            iban = "FR76 1820 6000 1234 5678 9012 345",
            currency = "EUR",
            balance = 5634.91,
            kind = AccountKind.JOINT,
            isJoint = true,
            colorHex = "#2E7D32",
            lastSyncedAt = System.currentTimeMillis()
        ),
        Account(
            id = "acc-boursorama",
            institutionName = "BoursoBank",
            displayName = "Compte secondaire",
            iban = "FR76 4061 8000 9988 7766 5544 332",
            currency = "EUR",
            balance = 845.10,
            kind = AccountKind.CHECKING,
            isJoint = false,
            colorHex = "#EF6C00",
            lastSyncedAt = System.currentTimeMillis()
        )
    )

    val transactions: List<Transaction> = buildList {
        val rng = Random(42)
        // Salaire récurrent
        for (m in 0L..5L) {
            val day = now.minusMonths(m).withDayOfMonth(2)
            add(
                Transaction(
                    id = UUID.randomUUID().toString(),
                    accountId = "acc-bnp-checking",
                    date = day,
                    amount = 2850.00,
                    currency = "EUR",
                    counterparty = "EMPLOYEUR SA",
                    description = "Salaire ${day.month.name}",
                    category = TxCategory.SALARY,
                    isPending = false
                )
            )
        }

        // Dépenses récentes variées
        val samples = listOf(
            Triple("Carrefour Market",  -64.32,  TxCategory.GROCERIES),
            Triple("Monoprix",          -38.91,  TxCategory.GROCERIES),
            Triple("Picard",            -27.50,  TxCategory.GROCERIES),
            Triple("Le Petit Bistrot",  -42.00,  TxCategory.RESTAURANT),
            Triple("Uber Eats",         -23.40,  TxCategory.RESTAURANT),
            Triple("SNCF Connect",     -89.00,  TxCategory.TRANSPORT),
            Triple("RATP",              -84.10,  TxCategory.TRANSPORT),
            Triple("Total Energies",   -210.45,  TxCategory.UTILITIES),
            Triple("Orange Mobile",    -19.99,  TxCategory.UTILITIES),
            Triple("Netflix",          -15.99,   TxCategory.SUBSCRIPTIONS),
            Triple("Spotify",          -10.99,   TxCategory.SUBSCRIPTIONS),
            Triple("Pharmacie Centrale",-12.50,  TxCategory.HEALTH),
            Triple("Decathlon",        -54.99,   TxCategory.SHOPPING),
            Triple("Fnac",             -129.00,  TxCategory.SHOPPING),
            Triple("Loyer Foncia",     -980.00,  TxCategory.HOUSING),
            Triple("Cinéma Pathé",     -13.50,   TxCategory.LEISURE),
            Triple("Remboursement Ana", 45.00,   TxCategory.REFUND),
        )

        for (m in 0L..5L) {
            val baseDay = now.minusMonths(m)
            samples.forEachIndexed { idx, (cp, amt, cat) ->
                val day = baseDay.withDayOfMonth(((idx * 2 + 3) % 27) + 1)
                val accId = when {
                    cat == TxCategory.HOUSING -> "acc-ca-joint"
                    cat == TxCategory.GROCERIES && idx % 2 == 0 -> "acc-ca-joint"
                    cat == TxCategory.RESTAURANT -> "acc-revolut"
                    cat == TxCategory.SHOPPING -> "acc-revolut"
                    idx % 4 == 0 -> "acc-boursorama"
                    else -> "acc-bnp-checking"
                }
                val jitter = (rng.nextDouble() - 0.5) * (amt.absoluteSafe() * 0.15)
                add(
                    Transaction(
                        id = UUID.randomUUID().toString(),
                        accountId = accId,
                        date = day,
                        amount = (amt + jitter).round2(),
                        currency = "EUR",
                        counterparty = cp,
                        description = cp,
                        category = cat,
                        isPending = m == 0L && day.isAfter(now.minusDays(3))
                    )
                )
            }
        }
    }

    private fun Double.absoluteSafe() = if (this < 0) -this else this
    private fun Double.round2() = kotlin.math.round(this * 100) / 100.0
}
