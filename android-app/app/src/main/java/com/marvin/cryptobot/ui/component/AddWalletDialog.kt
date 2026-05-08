package com.marvin.cryptobot.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.marvin.cryptobot.domain.model.StrategyType

@Composable
fun AddWalletDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, type: StrategyType, symbol: String, initialCash: Double) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(StrategyType.GRID) }
    var symbol by remember { mutableStateOf("BTCEUR") }
    var cashStr by remember { mutableStateOf("25") }

    val cash = cashStr.toDoubleOrNull() ?: -1.0
    val canConfirm = symbol.isNotBlank() && cash >= 0.0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nouveau wallet") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Stratégie", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = type == StrategyType.DCA,
                        onClick = { type = StrategyType.DCA },
                        label = { Text("📅 DCA") },
                    )
                    FilterChip(
                        selected = type == StrategyType.GRID,
                        onClick = { type = StrategyType.GRID },
                        label = { Text("📈 Grid") },
                    )
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nom (vide = auto)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = symbol,
                    onValueChange = { symbol = it.uppercase() },
                    label = { Text("Symbole (ex: BTCEUR, ETHEUR, SOLEUR)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = cashStr,
                    onValueChange = { cashStr = it },
                    label = { Text("Capital initial (EUR)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    "Tu pourras ajuster les paramètres de la stratégie (montant, pas, intervalle) " +
                        "depuis l'écran Réglages après création.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = { onConfirm(name, type, symbol, cash) },
            ) { Text("Créer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
