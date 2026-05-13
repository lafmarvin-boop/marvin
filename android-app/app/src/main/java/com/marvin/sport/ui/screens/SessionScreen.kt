package com.marvin.sport.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.WbIncandescent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marvin.sport.data.Exercise
import com.marvin.sport.data.ExerciseInfoBank
import com.marvin.sport.data.Phase
import com.marvin.sport.data.ProgressionStore
import com.marvin.sport.data.Session
import com.marvin.sport.data.Week
import com.marvin.sport.ui.components.ExerciseInfoSheet
import com.marvin.sport.ui.theme.ProgramAccent
import com.marvin.sport.ui.theme.SuccessGreen
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(
    phase: Phase,
    week: Week,
    session: Session,
    store: ProgressionStore,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val accent = ProgramAccent.forProgramId(session.programId)
    val done by store.isSessionDoneFlow(session.id).collectAsState(initial = false)
    val savedNote by store.noteFlow(session.id).collectAsState(initial = "")
    var noteText by remember(savedNote) { mutableStateOf(savedNote) }
    var infoFor by remember { mutableStateOf<Exercise?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Séance ${session.sessionIndex + 1}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "${phase.title.substringAfter("— ", phase.title)} · ${week.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.background,
                tonalElevation = 0.dp,
            ) {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                if (done) store.unmarkSessionDone(session)
                                else store.markSessionDone(session)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (done) SuccessGreen else accent,
                            contentColor = Color.White,
                        ),
                    ) {
                        if (done) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Séance terminée — Annuler", fontWeight = FontWeight.Bold)
                        } else {
                            Text("Terminer la séance", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    session.title.substringAfter("— ", session.title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
            item {
                WarmupBlock(text = session.warmup, accent = accent)
            }
            item {
                Text(
                    "Exercices".uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                )
            }
            itemsIndexed(session.exercises) { _, exo ->
                ExerciseCard(
                    exo = exo,
                    accent = accent,
                    store = store,
                    onInfoClick = { infoFor = exo },
                )
            }
            item {
                NoteCard(
                    value = noteText,
                    onChange = {
                        noteText = it
                        scope.launch { store.saveNote(session.id, it) }
                    },
                )
            }
        }
    }

    val current = infoFor
    if (current != null) {
        ExerciseInfoSheet(
            info = ExerciseInfoBank.lookup(current.name),
            onDismiss = { infoFor = null },
        )
    }
}

@Composable
private fun WarmupBlock(text: String, accent: Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.08f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Outlined.WbIncandescent,
                contentDescription = null,
                tint = accent,
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "Échauffement",
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ExerciseCard(
    exo: Exercise,
    accent: Color,
    store: ProgressionStore,
    onInfoClick: () -> Unit,
) {
    val completedCount by store.completedCountFlow(exo.name).collectAsState(initial = 0)
    val load = ProgressionStore.progressedLoad(exo.baseLoadKg, completedCount)
    val remaining = ProgressionStore.sessionsBeforeNextStep(completedCount)
    val delta = if (exo.baseLoadKg != null && load != null) load - exo.baseLoadKg else 0.0

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (exo.isSuperset) it.padding(start = 18.dp) else it },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(accent.copy(alpha = 0.14f))
                        .clickable(role = Role.Button, onClick = onInfoClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.PlayCircle,
                        contentDescription = "Voir la technique",
                        tint = accent,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    if (exo.isSuperset) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Link,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "SUPERSET",
                                style = MaterialTheme.typography.labelSmall,
                                color = accent,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Text(
                        exo.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${exo.sets} séries · ${exo.reps}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ExerciseMetric(
                    label = "Charge",
                    primaryValue = load?.let { formatKg(it) } ?: "—",
                    primaryColor = if (load != null) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    secondary = when {
                        delta > 0.0 -> "+${formatKg(delta)}"
                        load != null -> "palier dans $remaining séances"
                        else -> null
                    },
                    secondaryColor = if (delta > 0.0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(48.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                ExerciseMetric(
                    label = "Repos",
                    primaryValue = exo.rest.ifEmpty { "—" },
                    primaryColor = MaterialTheme.colorScheme.onSurface,
                    secondary = null,
                    secondaryColor = MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .weight(1.4f)
                        .padding(start = 12.dp),
                    isCompact = true,
                )
            }

            if (exo.annotation.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f))
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Timer,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        exo.annotation,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun ExerciseMetric(
    label: String,
    primaryValue: String,
    primaryColor: Color,
    secondary: String?,
    secondaryColor: Color,
    modifier: Modifier = Modifier,
    isCompact: Boolean = false,
) {
    Column(modifier = modifier) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            primaryValue,
            style = if (isCompact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = primaryColor,
        )
        if (secondary != null) {
            Spacer(Modifier.height(2.dp))
            Text(secondary, style = MaterialTheme.typography.labelSmall, color = secondaryColor)
        }
    }
}

@Composable
private fun NoteCard(value: String, onChange: (String) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                "Notes de séance".uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Ressenti, RPE, ajustements…") },
                minLines = 3,
                shape = RoundedCornerShape(14.dp),
            )
        }
    }
}

private fun formatKg(value: Double): String {
    val rounded = if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)
    return "$rounded kg"
}
