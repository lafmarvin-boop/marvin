package com.marvin.sport.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marvin.sport.data.Phase
import com.marvin.sport.data.TrainingProgram

@Composable
fun HomeScreen(
    program: TrainingProgram,
    onPhaseClick: (programId: String, phaseIndex: Int) -> Unit,
    contentPadding: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp,
            bottom = 16.dp + contentPadding.calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { HeaderCard(program) }
        items(program.phases) { phase ->
            PhaseCard(phase = phase, onClick = { onPhaseClick(program.id, phase.index) })
        }
        item { ProgressionLegend() }
    }
}

@Composable
private fun HeaderCard(program: TrainingProgram) {
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FitnessCenter, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    program.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(program.description, style = MaterialTheme.typography.bodyMedium)
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
            AssistChip(
                onClick = onClick,
                label = {
                    Text("${phase.weeks.size} semaines · ${phase.weeks.sumOf { it.sessions.size }} séances")
                },
            )
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
                "Toutes les 4 séances effectuées sur un exercice, la charge affichée augmente automatiquement de 1,5 kg. Tape sur le bouton ▶ pour voir la technique en vidéo.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
