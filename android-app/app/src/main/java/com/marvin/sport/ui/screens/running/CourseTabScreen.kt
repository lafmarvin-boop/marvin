package com.marvin.sport.ui.screens.running

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marvin.sport.ui.theme.ProgramAccent

@Composable
fun CourseTabScreen(
    onStartRun: () -> Unit,
    onRunClick: (String) -> Unit,
) {
    var selected by remember { mutableStateOf(0) }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillTab("GPS", Icons.Filled.DirectionsRun, selected == 0, ProgramAccent.Running,
                onClick = { selected = 0 }, modifier = Modifier.weight(1f))
            PillTab("Programme", Icons.Filled.Speed, selected == 1, ProgramAccent.Running,
                onClick = { selected = 1 }, modifier = Modifier.weight(1f))
            PillTab("Timer", Icons.Filled.Timer, selected == 2, ProgramAccent.Running,
                onClick = { selected = 2 }, modifier = Modifier.weight(1f))
        }
        AnimatedContent(
            targetState = selected,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
            label = "courseTab",
        ) { current ->
            Box(modifier = Modifier.fillMaxSize()) {
                when (current) {
                    0 -> RunHomeScreen(onStartRun = onStartRun, onRunClick = onRunClick)
                    1 -> RunningProgramScreen()
                    2 -> TimerScreen()
                }
            }
        }
    }
}

@Composable
private fun PillTab(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) accent else Color.Transparent
    val fg = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text(label, color = fg, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
    }
}
