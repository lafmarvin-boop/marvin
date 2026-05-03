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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marvin.cryptobot.domain.model.TradingMode
import com.marvin.cryptobot.viewmodel.MainViewModel

@Composable
fun DashboardScreen(vm: MainViewModel) {
    val config by vm.config.collectAsStateWithLifecycle()
    val price by vm.lastPrice.collectAsStateWithLifecycle()
    val trades by vm.trades.collectAsStateWithLifecycle()

    LaunchedEffect(config.symbol) { vm.refreshPrice() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Bot DCA Binance", style = MaterialTheme.typography.headlineSmall)

        Card {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Bot actif", Modifier.weight(1f))
                    Switch(
                        checked = config.enabled,
                        onCheckedChange = { vm.setEnabled(it) },
                    )
                }
                Spacer(Modifier.height(8.dp))
                val modeText = if (config.mode == TradingMode.PAPER) "Paper trading (simulation)" else "LIVE — argent réel"
                val modeColor = if (config.mode == TradingMode.PAPER)
                    MaterialTheme.colorScheme.secondary else Color(0xFFD32F2F)
                Text("Mode: $modeText", color = modeColor, style = MaterialTheme.typography.bodyMedium)
                Text("Symbole: ${config.symbol}")
                Text("Achat périodique: ${config.quoteAmount} ${quoteOf(config.symbol)} toutes les ${config.intervalHours}h")
            }
        }

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp)) {
                Text("Prix actuel ${config.symbol}", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = price?.let { "%.2f".format(it) } ?: "—",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.refreshPrice() }) { Text("Rafraîchir") }
                    Button(onClick = { vm.runOnceNow() }) { Text("Acheter maintenant") }
                }
            }
        }

        Card {
            Column(Modifier.padding(16.dp)) {
                Text("Statistiques", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                val ok = trades.filter { it.status == "OK" && it.mode == config.mode.name }
                val totalSpent = ok.sumOf { it.quoteSpent }
                val totalQty = ok.filter { it.symbol == config.symbol }.sumOf { it.quantity }
                Text("Trades réussis (${config.mode.name}): ${ok.size}")
                Text("Dépensé: %.2f %s".format(totalSpent, quoteOf(config.symbol)))
                Text("Possédé sur %s: %.8f".format(config.symbol, totalQty))
                if (price != null && totalQty > 0) {
                    val value = totalQty * price!!
                    val pnl = value - totalSpent
                    val pnlPct = if (totalSpent > 0) pnl / totalSpent * 100 else 0.0
                    Text("Valeur actuelle: %.2f %s".format(value, quoteOf(config.symbol)))
                    val pnlColor = if (pnl >= 0) Color(0xFF2E7D32) else Color(0xFFC62828)
                    Text(
                        "P&L: %+.2f %s (%+.2f%%)".format(pnl, quoteOf(config.symbol), pnlPct),
                        color = pnlColor,
                    )
                }
            }
        }
    }
}

private fun quoteOf(symbol: String): String {
    val quotes = listOf("EUR", "USDT", "BUSD", "USDC", "GBP", "TRY", "BTC")
    return quotes.firstOrNull { symbol.endsWith(it) } ?: ""
}
