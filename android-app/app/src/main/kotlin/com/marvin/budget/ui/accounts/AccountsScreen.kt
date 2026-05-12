package com.marvin.budget.ui.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marvin.budget.data.model.AccountKind
import com.marvin.budget.data.repository.AccountWithBalance
import com.marvin.budget.ui.format.Format

@Composable
fun AccountsScreen(
    viewModel: AccountsViewModel,
    onAccountClick: (String) -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(Modifier.padding(20.dp)) {
                    Text("Total tous comptes", style = MaterialTheme.typography.labelMedium)
                    Text(
                        Format.money(state.total),
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (state.personal.isNotEmpty()) {
            item { SectionHeader("Comptes personnels") }
            items(state.personal) { item ->
                AccountCard(item) { onAccountClick(item.account.id) }
            }
        }
        if (state.joint.isNotEmpty()) {
            item { SectionHeader("Comptes communs") }
            items(state.joint) { item ->
                AccountCard(item) { onAccountClick(item.account.id) }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun AccountCard(item: AccountWithBalance, onClick: () -> Unit) {
    val acc = item.account
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(44.dp)
                    .background(Color(parseHex(acc.colorHex)), RoundedCornerShape(10.dp))
            )
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(acc.displayName, style = MaterialTheme.typography.titleLarge)
                    Text(
                        Format.money(acc.balance, acc.currency),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (acc.balance < 0) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    "${acc.institutionName} • ${kindLabel(acc.kind)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (item.pendingDelta != 0.0) {
                    Text(
                        "Différé : ${Format.signedMoney(item.pendingDelta)} • " +
                                "Solde à venir ${Format.money(item.effectiveBalance)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
                if (acc.iban != null) {
                    Text(
                        acc.iban,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun kindLabel(kind: AccountKind): String = when (kind) {
    AccountKind.CHECKING -> "Compte courant"
    AccountKind.SAVINGS -> "Épargne"
    AccountKind.CREDIT_CARD -> "Carte différée"
    AccountKind.JOINT -> "Compte commun"
    AccountKind.OTHER -> "Autre"
}

private fun parseHex(hex: String): Long {
    val cleaned = hex.removePrefix("#")
    val rgb = cleaned.toLong(16)
    return 0xFF000000L or rgb
}
