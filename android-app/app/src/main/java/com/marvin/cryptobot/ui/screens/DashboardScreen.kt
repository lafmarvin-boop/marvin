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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marvin.cryptobot.domain.model.StrategyType
import com.marvin.cryptobot.domain.model.TradingMode
import com.marvin.cryptobot.domain.model.Wallet
import com.marvin.cryptobot.ui.component.AddWalletDialog
import com.marvin.cryptobot.ui.component.TransferDialog
import com.marvin.cryptobot.viewmodel.MainViewModel

@Composable
fun DashboardScreen(vm: MainViewModel) {
    val wallets by vm.wallets.collectAsStateWithLifecycle()
    val prices by vm.prices.collectAsStateWithLifecycle()

    var showTransfer by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(wallets.map { it.symbol }.toSet()) { vm.refreshPrices() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Tableau de bord", style = MaterialTheme.typography.headlineSmall)

        InvestmentSummaryCard(wallets, prices)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.refreshPrices() }) { Text("Rafraîchir") }
            OutlinedButton(onClick = { showTransfer = true }, enabled = wallets.size >= 2) {
                Text("Transférer")
            }
            Button(onClick = { showAdd = true }) { Text("+ Wallet") }
        }

        wallets.forEach { wallet ->
            WalletCard(
                wallet = wallet,
                price = prices[wallet.symbol],
                onToggle = { vm.setWalletEnabled(wallet.id, it) },
                onRunNow = { vm.runWalletNow(wallet.id) },
                onDelete = { pendingDeleteId = wallet.id },
            )
        }

        Spacer(Modifier.height(24.dp))
    }

    if (showTransfer) {
        TransferDialog(
            wallets = wallets,
            onDismiss = { showTransfer = false },
            onConfirm = { from, to, amount ->
                vm.transferBetweenWallets(from, to, amount)
                showTransfer = false
            },
        )
    }

    if (showAdd) {
        AddWalletDialog(
            onDismiss = { showAdd = false },
            onConfirm = { name, type, symbol, cash ->
                vm.addWallet(name, type, symbol, cash)
                showAdd = false
            },
        )
    }

    pendingDeleteId?.let { id ->
        val w = wallets.firstOrNull { it.id == id }
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("Supprimer ${w?.name} ?") },
            text = {
                Text(
                    "Le wallet sera supprimé. Les trades passés restent dans l'historique " +
                        "(marqués avec l'ancien identifiant). Le cash et les holdings " +
                        "simulés sont perdus."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeWallet(id)
                    pendingDeleteId = null
                }) { Text("Supprimer") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text("Annuler") }
            },
        )
    }
}

@Composable
private fun InvestmentSummaryCard(
    wallets: List<Wallet>,
    prices: Map<String, Double>,
) {
    val capitalInjected = wallets.sumOf { it.cashInjected }
    val totalCash = wallets.sumOf { it.balanceQuote }
    val totalCryptoValue = wallets.sumOf { w ->
        val p = prices[w.symbol] ?: 0.0
        w.holdingsBase * p
    }
    val recoverable = totalCash + totalCryptoValue
    val pnl = recoverable - capitalInjected
    val pnlPct = if (capitalInjected > 0) pnl / capitalInjected * 100.0 else 0.0
    val pnlColor = when {
        pnl > 0.01 -> Color(0xFF2E7D32)
        pnl < -0.01 -> Color(0xFFC62828)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("💰 Mon investissement", style = MaterialTheme.typography.titleMedium)

            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Investi", style = MaterialTheme.typography.labelMedium)
                    Text(
                        "%.2f €".format(capitalInjected),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("Récupérable", style = MaterialTheme.typography.labelMedium)
                    Text(
                        "%.2f €".format(recoverable),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = pnlColor,
                    )
                }
            }

            HorizontalDivider()

            Text("Cash dispo: %.2f €".format(totalCash), style = MaterialTheme.typography.bodySmall)
            Text("Valeur crypto: %.2f €".format(totalCryptoValue), style = MaterialTheme.typography.bodySmall)
            Text(
                "P&L: %+.2f € (%+.2f%%)".format(pnl, pnlPct),
                color = pnlColor,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun WalletCard(
    wallet: Wallet,
    price: Double?,
    onToggle: (Boolean) -> Unit,
    onRunNow: () -> Unit,
    onDelete: () -> Unit,
) {
    val typeLabel = when (wallet.type) {
        StrategyType.DCA -> "📅 DCA"
        StrategyType.GRID -> "📈 Grid"
    }
    val modeColor = if (wallet.mode == TradingMode.PAPER)
        MaterialTheme.colorScheme.secondary else Color(0xFFD32F2F)
    val modeText = if (wallet.mode == TradingMode.PAPER) "Paper" else "LIVE"

    val cryptoValue = (price ?: 0.0) * wallet.holdingsBase
    val recoverable = wallet.balanceQuote + cryptoValue
    val pnl = recoverable - wallet.cashInjected
    val pnlPct = if (wallet.cashInjected > 0) pnl / wallet.cashInjected * 100 else 0.0
    val pnlColor = when {
        pnl > 0.01 -> Color(0xFF2E7D32)
        pnl < -0.01 -> Color(0xFFC62828)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$typeLabel  ${wallet.name}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = wallet.enabled, onCheckedChange = onToggle)
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Supprimer",
                        tint = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            Text("Mode: $modeText  •  Symbole: ${wallet.symbol}", color = modeColor)

            HorizontalDivider()

            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Cash", style = MaterialTheme.typography.labelSmall)
                    Text("%.2f €".format(wallet.balanceQuote), fontWeight = FontWeight.SemiBold)
                }
                Column(Modifier.weight(1f)) {
                    Text("Crypto", style = MaterialTheme.typography.labelSmall)
                    Text("%.6f".format(wallet.holdingsBase), fontWeight = FontWeight.SemiBold)
                    if (price != null && wallet.holdingsBase > 0) {
                        Text("%.2f €".format(cryptoValue), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text("Capital", style = MaterialTheme.typography.labelSmall)
                    Text("%.2f €".format(wallet.cashInjected), fontWeight = FontWeight.SemiBold)
                }
            }

            if (price != null && wallet.cashInjected > 0) {
                Text(
                    "Récupérable: %.2f €  •  P&L: %+.2f € (%+.2f%%)".format(recoverable, pnl, pnlPct),
                    color = pnlColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            // Stratégie spécifique
            when (wallet.type) {
                StrategyType.DCA -> Text(
                    "Achète ${wallet.dcaAmount} € toutes les ${wallet.dcaIntervalHours}h",
                    style = MaterialTheme.typography.bodySmall,
                )
                StrategyType.GRID -> {
                    Text(
                        "Trade ${wallet.gridAmountPerStep} € à chaque ±${wallet.gridStepPercent}%",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (wallet.gridReferencePrice > 0) {
                        Text(
                            "Référence: %.2f €".format(wallet.gridReferencePrice),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Button(onClick = onRunNow) { Text("Exécuter maintenant") }
        }
    }
}
