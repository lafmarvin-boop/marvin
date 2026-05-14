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
import com.marvin.sport.data.RunBlock
import com.marvin.sport.data.RunRepository
import com.marvin.sport.data.RunSession
import com.marvin.sport.data.RunningProgramBuilder
import com.marvin.sport.ui.components.HeroBanner
import com.marvin.sport.ui.theme.ProgramAccent

@Composable
fun RunningProgramScreen(onStartRun: () -> Unit) {
    val accent = ProgramAccent.Running
    val program = RunningProgramBuilder.program

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
                        onStart = {
                            RunRepository.setNextTarget(session.targetKm * 1000.0)
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
private fun RunSessionCard(session: RunSession, accent: Color, onStart: () -> Unit) {
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
                    Text(
                        "%.1f km".format(session.targetKm),
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        fontWeight = FontWeight.Bold,
                    )
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
                Text("Démarrer (%.1f km)".format(session.targetKm), fontWeight = FontWeight.Bold)
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
