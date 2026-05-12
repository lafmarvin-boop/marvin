package com.marvin.sport.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marvin.sport.data.Phase
import com.marvin.sport.data.ProgressionStore
import com.marvin.sport.data.TrainingProgram

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    program: TrainingProgram,
    store: ProgressionStore,
    onPhaseClick: (Int) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Marvin Sport") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { HeaderCard() }
            items(program.phases) { phase ->
                PhaseCard(phase = phase, onClick = { onPhaseClick(phase.index) })
            }
            item { ProgressionLegend() }
        }
    }
}

@Composable
private fun HeaderCard() {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FitnessCenter, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Programme 12 semaines",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "3 phases — Technique, Volume, Force. 3 séances par semaine. Progression automatique : +1.5 kg toutes les 3 séances similaires.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun PhaseCard(phase: Phase, onClick: () -> Unit) {
    Card(onClick = onClick) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                phase.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(phase.description, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            AssistChip(onClick = onClick, label = { Text("${phase.weeks.size} semaines · ${phase.weeks.sumOf { it.sessions.size }} séances") })
        }
    }
}

@Composable
private fun ProgressionLegend() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Progression", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Coche \"Terminer la séance\" en bas de chaque séance. Toutes les 3 séances effectuées sur un même exercice, la charge affichée augmente automatiquement de 1.5 kg.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
