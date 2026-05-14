package com.marvin.sport.ui.screens.running

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.DirectionsRun
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marvin.sport.data.Run
import com.marvin.sport.data.RunRepository
import com.marvin.sport.data.RunStats
import com.marvin.sport.ui.components.HeroBanner
import com.marvin.sport.ui.components.RunPathCanvas
import com.marvin.sport.ui.components.TargetDistanceDialog
import com.marvin.sport.ui.theme.ProgramAccent
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
    val accent = ProgramAccent.Running
    var showDistanceDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                val totalKm = runs.sumOf { it.distanceM } / 1000.0
                val subtitle = if (runs.isEmpty()) "Aucune sortie enregistrée pour l'instant."
                else "%.1f km au total sur %d sortie%s".format(
                    totalKm, runs.size, if (runs.size > 1) "s" else ""
                )
                HeroBanner(
                    eyebrow = "Running",
                    title = "Course à pied",
                    subtitle = subtitle,
                    accent = accent,
                )
            }
            if (live != null) {
                item { LiveRunBanner(onClick = onStartRun) }
            }
            if (runs.isEmpty()) {
                item { EmptyState(accent = accent) }
            } else {
                item {
                    Text(
                        "Historique".uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                }
                items(runs, key = { it.id }) { run ->
                    RunCard(run = run, accent = accent, onClick = { onRunClick(run.id) })
                }
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = {
                    RunRepository.setNextTarget(null)
                    onStartRun()
                },
                modifier = Modifier
                    .weight(1f)
                    .height(60.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    contentColor = Color.White,
                ),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (live != null) "Reprendre" else "Course libre",
                    fontWeight = FontWeight.Bold,
                )
            }
            OutlinedButton(
                onClick = { showDistanceDialog = true },
                modifier = Modifier
                    .weight(1f)
                    .height(60.dp),
                shape = CircleShape,
                enabled = live == null,
            ) {
                Icon(Icons.Filled.Flag, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Objectif", fontWeight = FontWeight.Bold)
            }
        }
    }

    if (showDistanceDialog) {
        TargetDistanceDialog(
            accent = accent,
            onDismiss = { showDistanceDialog = false },
            onPicked = { targetM ->
                RunRepository.setNextTarget(targetM)
                showDistanceDialog = false
                onStartRun()
            },
        )
    }
}

@Composable
private fun LiveRunBanner(onClick: () -> Unit) {
    val accent = ProgramAccent.Running
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = accent),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.DirectionsRun, contentDescription = null, tint = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Course en cours", color = Color.White, fontWeight = FontWeight.Bold)
                Text(
                    "Tape pour reprendre le suivi",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(accent: Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.DirectionsRun,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(36.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Prêt pour ta première sortie ?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Appuie sur \"Démarrer une course\" pour lancer le tracking GPS et enregistrer ton parcours.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RunCard(run: Run, accent: Color, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                formatDate(run.startedAt),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                RunStats.formatDistance(run.distanceM),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = accent,
            )
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatTiny("Durée", RunStats.formatDuration(run.durationMs))
                StatTiny("Allure", RunStats.formatPace(run.durationMs, run.distanceM))
                StatTiny("Vitesse", RunStats.formatSpeed(run.durationMs, run.distanceM))
            }
            Spacer(Modifier.height(10.dp))
            RunPathCanvas(
                points = run.points,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                pathColor = accent,
            )
        }
    }
}

@Composable
private fun StatTiny(label: String, value: String) {
    Column {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatDate(ms: Long): String {
    val fmt = SimpleDateFormat("EEE d MMM yyyy · HH:mm", Locale.getDefault())
    return fmt.format(Date(ms)).replaceFirstChar { it.uppercase(Locale.getDefault()) }
}
