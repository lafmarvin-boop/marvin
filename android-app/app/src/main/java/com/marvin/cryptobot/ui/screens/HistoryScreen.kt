package com.marvin.cryptobot.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.marvin.cryptobot.viewmodel.MainViewModel
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(vm: MainViewModel) {
    val trades by vm.trades.collectAsStateWithLifecycle()
    val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)

    if (trades.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Aucun trade encore. Lance ton premier achat depuis le tableau.")
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(trades, key = { it.id }) { t ->
            Card {
                Column(Modifier.padding(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(df.format(Date(t.timestamp)), style = MaterialTheme.typography.labelSmall)
                        val tag = if (t.mode == "PAPER") "📝 PAPER" else "💰 LIVE"
                        Text(tag, style = MaterialTheme.typography.labelSmall)
                        val statusColor = if (t.status == "OK") Color(0xFF2E7D32) else Color(0xFFC62828)
                        Text(t.status, color = statusColor, style = MaterialTheme.typography.labelSmall)
                    }
                    Text(
                        "${t.side} ${t.symbol}",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (t.status == "OK") {
                        Text("Quantité: %.8f @ %.2f".format(t.quantity, t.price))
                        Text("Total: %.2f".format(t.quoteSpent))
                    } else {
                        Text(t.message ?: "(erreur)", color = Color(0xFFC62828))
                    }
                }
            }
        }
    }
}
