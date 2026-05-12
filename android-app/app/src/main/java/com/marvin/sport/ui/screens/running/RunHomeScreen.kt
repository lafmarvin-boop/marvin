package com.marvin.sport.ui.screens.running

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marvin.sport.data.Run
import com.marvin.sport.data.RunRepository
import com.marvin.sport.data.RunStats
import com.marvin.sport.ui.components.RunPathCanvas
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RunHomeScreen(
    onStartRun: () -> Unit,
    onRunClick: (String) -> Unit,
) {
    val runs by RunRepository.savedRuns.collectAsState()
    val live by RunRepository.currentRun.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Header() }
            if (live != null) {
                item {
                    Card(
                        onClick = onStartRun,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.DirectionsRun,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    "Course en cours",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    "Toucher pour revenir au suivi",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
            if (runs.isEmpty()) {
                item { EmptyState() }
            } else {
                item {
                    Text(
                        "Historique (${runs.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                items(runs, key = { it.id }) { run ->
                    RunCard(run = run, onClick = { onRunClick(run.id) })
                }
            }
        }
        Button(
            onClick = onStartRun,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (live != null) "Reprendre le suivi" else "Démarrer une course")
        }
    }
}

@Composable
private fun Header() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.DirectionsRun, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Course à pied", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Tracking GPS de tes sorties running. Distance, allure, durée, tracé.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Aucune course encore", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Appuie sur \"Démarrer une course\" en bas de l'écran pour enregistrer ta première sortie.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun RunCard(run: Run, onClick: () -> Unit) {
    Card(onClick = onClick) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                formatDate(run.startedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                RunStats.formatDistance(run.distanceM),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Row {
                StatChip("Durée", RunStats.formatDuration(run.durationMs))
                Spacer(Modifier.width(8.dp))
                StatChip("Allure", RunStats.formatPace(run.durationMs, run.distanceM))
            }
            Spacer(Modifier.height(8.dp))
            RunPathCanvas(
                points = run.points,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
            )
        }
    }
}

@Composable
private fun StatChip(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatDate(ms: Long): String {
    val fmt = SimpleDateFormat("EEE d MMM yyyy · HH:mm", Locale.getDefault())
    return fmt.format(Date(ms)).replaceFirstChar { it.uppercase(Locale.getDefault()) }
}
