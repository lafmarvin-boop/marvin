package com.marvin.cryptobot.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marvin.cryptobot.domain.model.TradingMode
import com.marvin.cryptobot.viewmodel.MainViewModel

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val config by vm.config.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()

    var symbol by remember(config.symbol) { mutableStateOf(config.symbol) }
    var amount by remember(config.quoteAmount) { mutableStateOf(config.quoteAmount.toString()) }
    var interval by remember(config.intervalHours) { mutableStateOf(config.intervalHours.toString()) }
    var maxSpend by remember(config.maxTotalSpend) { mutableStateOf(config.maxTotalSpend.toString()) }
    var apiKey by remember { mutableStateOf("") }
    var apiSecret by remember { mutableStateOf("") }
    var confirmLive by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Réglages", style = MaterialTheme.typography.headlineSmall)

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Mode de trading", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = config.mode == TradingMode.PAPER,
                        onClick = { vm.setMode(TradingMode.PAPER) },
                        label = { Text("Paper (simulation)") },
                    )
                    FilterChip(
                        selected = config.mode == TradingMode.LIVE,
                        onClick = { confirmLive = true },
                        label = { Text("LIVE (réel)") },
                    )
                }
                Text(
                    "En mode paper, aucun ordre n'est envoyé à Binance. Le bot simule avec les vrais prix.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Stratégie DCA", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = symbol,
                    onValueChange = { symbol = it },
                    label = { Text("Symbole (ex: BTCEUR)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Montant par achat (en quote-currency)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = interval,
                    onValueChange = { interval = it },
                    label = { Text("Intervalle entre achats (heures)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = maxSpend,
                    onValueChange = { maxSpend = it },
                    label = { Text("Plafond cumulé (0 = illimité)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        vm.setSymbol(symbol)
                        amount.toDoubleOrNull()?.let { vm.setQuoteAmount(it) }
                        interval.toIntOrNull()?.let { vm.setIntervalHours(it) }
                        maxSpend.toDoubleOrNull()?.let { vm.setMaxSpend(it) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Enregistrer la stratégie") }
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Clés API Binance", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Stockées chiffrées via Android Keystore. Active uniquement les permissions de trading sur ta clé, JAMAIS les retraits.",
                    style = MaterialTheme.typography.bodySmall,
                )
                val hasCreds = vm.hasCredentials
                Text(
                    if (hasCreds) "✓ Clés configurées" else "✗ Aucune clé enregistrée",
                    color = if (hasCreds) Color(0xFF2E7D32) else Color(0xFFC62828),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiSecret,
                    onValueChange = { apiSecret = it },
                    label = { Text("API Secret") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (apiKey.isNotBlank() && apiSecret.isNotBlank()) {
                                vm.saveApiCredentials(apiKey, apiSecret)
                                apiKey = ""; apiSecret = ""
                            }
                        }
                    ) { Text("Enregistrer") }
                    OutlinedButton(onClick = { vm.clearApiCredentials() }) { Text("Effacer") }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (confirmLive) {
        AlertDialog(
            onDismissRequest = { confirmLive = false },
            title = { Text("⚠️ Activer le mode LIVE ?") },
            text = {
                Text(
                    "En mode LIVE, le bot dépensera de l'argent réel sur ton compte Binance. " +
                        "Vérifie ta stratégie, ton plafond, et tes clés API (sans permission de retrait). " +
                        "Tu confirmes ?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setMode(TradingMode.LIVE)
                    confirmLive = false
                }) { Text("Activer LIVE") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLive = false }) { Text("Annuler") }
            },
        )
    }

    val msg = ui.message
    if (msg != null) {
        AlertDialog(
            onDismissRequest = { vm.consumeMessage() },
            title = { Text("Info") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { vm.consumeMessage() }) { Text("OK") } },
        )
    }
}
