package com.marvin.cryptobot.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.marvin.cryptobot.domain.model.Wallet

@Composable
fun TransferDialog(
    wallets: List<Wallet>,
    onDismiss: () -> Unit,
    onConfirm: (fromId: String, toId: String, amount: Double) -> Unit,
) {
    if (wallets.size < 2) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Transfert impossible") },
            text = { Text("Il faut au moins 2 wallets pour faire un transfert.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        )
        return
    }

    var fromId by remember { mutableStateOf(wallets[0].id) }
    var toId by remember { mutableStateOf(wallets[1].id) }
    var amountStr by remember { mutableStateOf("") }

    val from = wallets.firstOrNull { it.id == fromId }
    val amount = amountStr.toDoubleOrNull() ?: 0.0
    val canConfirm = fromId != toId && amount > 0 && (from?.balanceQuote ?: 0.0) >= amount

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transférer entre wallets") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Depuis", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    wallets.forEach { w ->
                        FilterChip(
                            selected = fromId == w.id,
                            onClick = { fromId = w.id; if (toId == w.id) toId = wallets.first { it.id != w.id }.id },
                            label = { Text("${w.name}\n%.2f €".format(w.balanceQuote)) },
                        )
                    }
                }
                Text("Vers", style = androidx.compose.material3.MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    wallets.filter { it.id != fromId }.forEach { w ->
                        FilterChip(
                            selected = toId == w.id,
                            onClick = { toId = w.id },
                            label = { Text(w.name) },
                        )
                    }
                }
                OutlinedTextField(
                    value = amountStr,
                    onValueChange = { amountStr = it },
                    label = { Text("Montant (EUR)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (from != null && amount > from.balanceQuote) {
                    Text(
                        "⚠️ Solde insuffisant (%.2f €)".format(from.balanceQuote),
                        color = androidx.compose.ui.graphics.Color(0xFFC62828),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = { onConfirm(fromId, toId, amount) },
            ) { Text("Transférer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
