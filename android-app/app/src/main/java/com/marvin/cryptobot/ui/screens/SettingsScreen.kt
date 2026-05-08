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
import com.marvin.cryptobot.domain.model.StrategyType
import com.marvin.cryptobot.domain.model.TradingMode
import com.marvin.cryptobot.domain.model.Wallet
import com.marvin.cryptobot.viewmodel.MainViewModel

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val wallets by vm.wallets.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()

    var apiKey by remember { mutableStateOf("") }
    var apiSecret by remember { mutableStateOf("") }
    var pendingLiveWalletId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Réglages", style = MaterialTheme.typography.headlineSmall)

        wallets.forEach { wallet ->
            WalletSettingsCard(
                wallet = wallet,
                onSetSymbol = { vm.setSymbol(wallet.id, it) },
                onSetMode = { mode ->
                    if (mode == TradingMode.LIVE) pendingLiveWalletId = wallet.id
                    else vm.setMode(wallet.id, mode)
                },
                onSetDca = { amount, hours -> vm.setDcaParams(wallet.id, amount, hours) },
                onSetGrid = { step, amount -> vm.setGridParams(wallet.id, step, amount) },
                onSetMaxSpend = { vm.setMaxSpend(wallet.id, it) },
                onDeposit = { vm.depositToWallet(wallet.id, it) },
                onReset = { vm.resetWallet(wallet.id) },
            )
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
                    Button(onClick = {
                        if (apiKey.isNotBlank() && apiSecret.isNotBlank()) {
                            vm.saveApiCredentials(apiKey, apiSecret)
                            apiKey = ""; apiSecret = ""
                        }
                    }) { Text("Enregistrer") }
                    OutlinedButton(onClick = { vm.clearApiCredentials() }) { Text("Effacer") }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    pendingLiveWalletId?.let { id ->
        val w = wallets.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { pendingLiveWalletId = null },
            title = { Text("⚠️ Activer LIVE pour ${w?.name} ?") },
            text = {
                Text(
                    "En mode LIVE, ce wallet enverra de vrais ordres sur ton compte Binance. " +
                        "Vérifie ton plafond, ta stratégie et tes clés API (sans permission de retrait)."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setMode(id, TradingMode.LIVE)
                    pendingLiveWalletId = null
                }) { Text("Activer LIVE") }
            },
            dismissButton = {
                TextButton(onClick = { pendingLiveWalletId = null }) { Text("Annuler") }
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

@Composable
private fun WalletSettingsCard(
    wallet: Wallet,
    onSetSymbol: (String) -> Unit,
    onSetMode: (TradingMode) -> Unit,
    onSetDca: (Double, Int) -> Unit,
    onSetGrid: (Double, Double) -> Unit,
    onSetMaxSpend: (Double) -> Unit,
    onDeposit: (Double) -> Unit,
    onReset: () -> Unit,
) {
    var symbol by remember(wallet.symbol) { mutableStateOf(wallet.symbol) }
    var dcaAmount by remember(wallet.dcaAmount) { mutableStateOf(wallet.dcaAmount.toString()) }
    var dcaInterval by remember(wallet.dcaIntervalHours) { mutableStateOf(wallet.dcaIntervalHours.toString()) }
    var gridStep by remember(wallet.gridStepPercent) { mutableStateOf(wallet.gridStepPercent.toString()) }
    var gridAmount by remember(wallet.gridAmountPerStep) { mutableStateOf(wallet.gridAmountPerStep.toString()) }
    var maxSpend by remember(wallet.maxTotalSpend) { mutableStateOf(wallet.maxTotalSpend.toString()) }
    var depositAmount by remember { mutableStateOf("") }
    val typeLabel = when (wallet.type) {
        StrategyType.DCA -> "📅 DCA"
        StrategyType.GRID -> "📈 Grid"
    }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$typeLabel  ${wallet.name}", style = MaterialTheme.typography.titleMedium)

            // Mode
            Text("Mode de trading", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = wallet.mode == TradingMode.PAPER,
                    onClick = { onSetMode(TradingMode.PAPER) },
                    label = { Text("Paper") },
                )
                FilterChip(
                    selected = wallet.mode == TradingMode.LIVE,
                    onClick = { onSetMode(TradingMode.LIVE) },
                    label = { Text("LIVE") },
                )
            }
            if (wallet.type == StrategyType.GRID && wallet.mode == TradingMode.LIVE) {
                Text(
                    "⚠️ Grid LIVE pas encore supporté — reste en PAPER",
                    color = Color(0xFFC62828),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            OutlinedTextField(
                value = symbol,
                onValueChange = { symbol = it },
                label = { Text("Symbole") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            when (wallet.type) {
                StrategyType.DCA -> {
                    OutlinedTextField(
                        value = dcaAmount,
                        onValueChange = { dcaAmount = it },
                        label = { Text("Montant par achat (EUR)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = dcaInterval,
                        onValueChange = { dcaInterval = it },
                        label = { Text("Intervalle (heures)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                StrategyType.GRID -> {
                    OutlinedTextField(
                        value = gridStep,
                        onValueChange = { gridStep = it },
                        label = { Text("Pas (%) — déclenche un trade à chaque ±X%") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = gridAmount,
                        onValueChange = { gridAmount = it },
                        label = { Text("Montant par trade (EUR)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

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
                    onSetSymbol(symbol)
                    when (wallet.type) {
                        StrategyType.DCA -> onSetDca(
                            dcaAmount.toDoubleOrNull() ?: wallet.dcaAmount,
                            dcaInterval.toIntOrNull() ?: wallet.dcaIntervalHours,
                        )
                        StrategyType.GRID -> onSetGrid(
                            gridStep.toDoubleOrNull() ?: wallet.gridStepPercent,
                            gridAmount.toDoubleOrNull() ?: wallet.gridAmountPerStep,
                        )
                    }
                    onSetMaxSpend(maxSpend.toDoubleOrNull() ?: wallet.maxTotalSpend)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Enregistrer") }

            Text("Capital", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = depositAmount,
                    onValueChange = { depositAmount = it },
                    label = { Text("Dépôt simulé (EUR)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = {
                        depositAmount.toDoubleOrNull()?.let { onDeposit(it) }
                        depositAmount = ""
                    }
                ) { Text("Déposer") }
            }
            OutlinedButton(onClick = onReset) { Text("Réinitialiser le wallet") }
        }
    }
}
