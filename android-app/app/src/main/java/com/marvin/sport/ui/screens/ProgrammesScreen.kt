package com.marvin.sport.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marvin.sport.data.ProgressionStore
import com.marvin.sport.data.Programs
import com.marvin.sport.data.TrainingProgram
import com.marvin.sport.ui.theme.ProgramAccent
import kotlinx.coroutines.launch

@Composable
fun ProgrammesScreen(
    store: ProgressionStore,
    onPhaseClick: (programId: String, phaseIndex: Int) -> Unit,
    contentPadding: PaddingValues,
) {
    val scope = rememberCoroutineScope()
    val savedId by store.selectedProgramFlow(default = Programs.strength.id)
        .collectAsState(initial = Programs.strength.id)
    val selected = remember(savedId) { Programs.byId(savedId) }

    Column(modifier = Modifier.fillMaxSize()) {
        ProgramChips(
            programs = Programs.all,
            selectedId = selected.id,
            onSelect = { id -> scope.launch { store.saveSelectedProgram(id) } },
        )
        HomeScreen(
            program = selected,
            onPhaseClick = onPhaseClick,
            contentPadding = contentPadding,
        )
    }
}

@Composable
private fun ProgramChips(
    programs: List<TrainingProgram>,
    selectedId: String,
    onSelect: (String) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(programs) { program ->
            val accent = ProgramAccent.forProgramId(program.id)
            val isSelected = program.id == selectedId
            ProgramChip(
                label = program.shortName,
                accent = accent,
                selected = isSelected,
                onClick = { onSelect(program.id) },
            )
        }
    }
}

@Composable
private fun ProgramChip(label: String, accent: Color, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) accent else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontWeight = FontWeight.SemiBold)
    }
}
