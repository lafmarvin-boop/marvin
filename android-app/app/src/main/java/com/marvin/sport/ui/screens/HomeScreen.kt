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

@Composable
fun HomeScreen(
    program: TrainingProgram,
    store: ProgressionStore,
    onPhaseClick: (Int) -> Unit,
    contentPadding: PaddingValues,
) {
    val cycles by store.completedCyclesFlow().collectAsState(initial = 0)
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
        item { HeaderCard(cycles = cycles) }
        items(program.phases) { phase ->
            PhaseCard(
                phase = phase,
                cycles = cycles,
                onClick = { onPhaseClick(phase.index) },
            )
        }
        item { ProgressionLegend() }
    }
}

@Composable
private fun HeaderCard(cycles: Int) {
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
                "3 phases — Technique, Volume, Force. 3 séances par semaine. Progression automatique : +1.5 kg à la fin de chaque phase de 4 semaines.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (cycles > 0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Cycle en cours : ${cycles + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun PhaseCard(phase: Phase, cycles: Int, onClick: () -> Unit) {
    val step = ProgressionStore.stepKgForPhase(phase.index, cycles)
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
            Row {
                AssistChip(
                    onClick = onClick,
                    label = {
                        Text("${phase.weeks.size} semaines · ${phase.weeks.sumOf { it.sessions.size }} séances")
                    },
                )
                if (step > 0.0) {
                    Spacer(Modifier.width(6.dp))
                    AssistChip(
                        onClick = onClick,
                        label = {
                            val txt = if (step % 1.0 == 0.0) "+${step.toInt()} kg" else "+%.1f kg".format(step)
                            Text(txt)
                        },
                    )
                }
            }
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
                "Phase 1 = charge de base. Phase 2 = +1.5 kg. Phase 3 = +3 kg. Termine un cycle complet (3 phases) pour repartir avec un nouveau palier de +1.5 kg.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
