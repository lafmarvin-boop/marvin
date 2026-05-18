package com.marvin.sport.ui.screens.running

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.marvin.sport.data.RunBlock
import com.marvin.sport.data.RunFitnessEstimator
import com.marvin.sport.data.FitnessProfile
import com.marvin.sport.data.RunRepository
import com.marvin.sport.data.RunSession
import com.marvin.sport.data.RunningProgramBuilder
import com.marvin.sport.ui.components.HeroBanner
import com.marvin.sport.ui.theme.ProgramAccent

@Composable
fun RunningProgramScreen(onStartRun: () -> Unit) {
    val accent = ProgramAccent.Running
    val program = RunningProgramBuilder.program
    val savedRuns by RunRepository.savedRuns.collectAsState()
    val profile = remember(savedRuns) { RunFitnessEstimator.estimate(savedRuns) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            HeroBanner(
                eyebrow = "Programme structuré",
                title = program.name,
                subtitle = program.description,
                accent = accent,
            )
        }
        if (profile.isAdapted) {
            item { AdaptationBanner(profile = profile, accent = accent) }
        }
        program.weeks.forEach { week ->
            item {
                Text(
                    week.label.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }
            week.sessions.forEach { session ->
                item {
                    RunSessionCard(
                        session = session,
                        accent = accent,
                        profile = profile,
                        onStart = {
                            val adaptedKm = profile.adaptedKm(session.targetKm)
                            RunRepository.setNextTarget(adaptedKm * 1000.0)
                            RunRepository.setNextProgram(
                                session.blocks.map { b ->
                                    when {
                                        b.distanceM != null -> b.copy(
                                            distanceM = (b.distanceM * profile.factor).toInt().coerceAtLeast(50)
                                        )
                                        b.durationMin != null -> b.copy(
                                            durationMin = profile.adaptedDurationMin(b.durationMin)
                                        )
                                        else -> b
                                    }
                                }
                            )
                            onStartRun()
                        },
                    )
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun AdaptationBanner(profile: FitnessProfile, accent: Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.10f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                "PROGRAMME ADAPTÉ",
                style = MaterialTheme.typography.labelMedium,
                color = accent,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            val avg = profile.recentAvgKm ?: 0.0
            val factorPct = ((profile.factor - 1.0) * 100).toInt()
            val sign = if (factorPct >= 0) "+" else ""
            Text(
                "Basé sur tes 5 dernières sorties (moy. ${"%.1f".format(avg)} km). " +
                    "Distances ajustées de $sign$factorPct %.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun RunSessionCard(session: RunSession, accent: Color, profile: FitnessProfile, onStart: () -> Unit) {
    val adaptedKm = profile.adaptedKm(session.targetKm)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Speed, contentDescription = null, tint = accent)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        session.type.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(session.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(accent.copy(alpha = 0.14f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "%.1f km".format(adaptedKm),
                            style = MaterialTheme.typography.labelMedium,
                            color = accent,
                            fontWeight = FontWeight.Bold,
                        )
                        if (profile.isAdapted && kotlin.math.abs(adaptedKm - session.targetKm) >= 0.2) {
                            Text(
                                "base ${"%.1f".format(session.targetKm)} km",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                session.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(10.dp))
            session.blocks.forEach { block ->
                BlockRow(block = block, accent = accent)
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Démarrer (%.1f km)".format(adaptedKm), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun BlockRow(block: RunBlock, accent: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(accent),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                block.label + if (block.repeat > 1) " × ${block.repeat}" else "",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                block.intensity,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            when {
                block.durationMin != null -> "${block.durationMin} min"
                block.distanceM != null -> "${block.distanceM} m"
                else -> ""
            },
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = accent,
        )
    }
}
