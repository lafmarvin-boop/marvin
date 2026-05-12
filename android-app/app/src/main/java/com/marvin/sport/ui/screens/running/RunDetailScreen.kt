package com.marvin.sport.ui.screens.running

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marvin.sport.data.RunRepository
import com.marvin.sport.data.RunStats
import com.marvin.sport.ui.components.RunPathCanvas
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunDetailScreen(runId: String, onBack: () -> Unit) {
    val runs by RunRepository.savedRuns.collectAsState()
    val run = remember(runs, runId) { runs.firstOrNull { it.id == runId } }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Détail de la course") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    if (run != null) {
                        IconButton(onClick = {
                            scope.launch {
                                RunRepository.deleteRun(run.id)
                                onBack()
                            }
                        }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Supprimer")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
    ) { padding ->
        if (run == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("Course introuvable")
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                formatDate(run.startedAt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            Card {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        RunStats.formatDistance(run.distanceM),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Stat("Durée", RunStats.formatDuration(run.durationMs))
                        Stat("Allure", RunStats.formatPace(run.durationMs, run.distanceM))
                        Stat("Vitesse", RunStats.formatSpeed(run.durationMs, run.distanceM))
                    }
                }
            }
            Card {
                Box(modifier = Modifier.fillMaxWidth().height(320.dp)) {
                    RunPathCanvas(points = run.points, modifier = Modifier.fillMaxSize())
                }
            }
            Text(
                "${run.points.size} points GPS enregistrés",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatDate(ms: Long): String {
    val fmt = SimpleDateFormat("EEEE d MMMM yyyy · HH:mm", Locale.getDefault())
    return fmt.format(Date(ms)).replaceFirstChar { it.uppercase(Locale.getDefault()) }
}
