package com.marvin.sport.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marvin.sport.data.Phase
import com.marvin.sport.data.TrainingProgram
import com.marvin.sport.ui.components.AccentChip
import com.marvin.sport.ui.components.HeroBanner
import com.marvin.sport.ui.theme.ProgramAccent

@Composable
fun HomeScreen(
    program: TrainingProgram,
    onPhaseClick: (programId: String, phaseIndex: Int) -> Unit,
    contentPadding: PaddingValues,
) {
    val accent = ProgramAccent.forProgramId(program.id)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp,
            bottom = 24.dp + contentPadding.calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            HeroBanner(
                eyebrow = program.shortName,
                title = program.name,
                subtitle = program.description,
                accent = accent,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AccentChip(
                        text = "${program.phases.size} phases",
                        color = Color.White.copy(alpha = 0.92f),
                    )
                    Spacer(Modifier.width(8.dp))
                    val totalSessions = program.phases.sumOf { it.weeks.sumOf { w -> w.sessions.size } }
                    AccentChip(text = "$totalSessions séances", color = Color.White.copy(alpha = 0.92f))
                }
            }
        }
        item {
            SectionTitle("Phases")
        }
        items(program.phases) { phase ->
            PhaseCard(
                phase = phase,
                accent = accent,
                onClick = { onPhaseClick(program.id, phase.index) },
            )
        }
        item { Spacer(Modifier.height(4.dp)) }
        item { ProgressionLegend() }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

@Composable
private fun PhaseCard(phase: Phase, accent: Color, onClick: () -> Unit) {
    val totalSessions = phase.weeks.sumOf { it.sessions.size }
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${phase.index + 1}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = accent,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    phase.title.substringAfter("— ", phase.title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    phase.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    AccentChip("${phase.weeks.size} sem.", accent)
                    Spacer(Modifier.width(6.dp))
                    AccentChip("$totalSessions séances", MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun ProgressionLegend() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Bolt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "Progression auto +1,5 kg / 4 séances",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Le compteur tourne dès qu'une séance est cochée. La charge affichée monte d'elle-même.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
