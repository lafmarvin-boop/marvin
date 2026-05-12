package com.marvin.sport.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.marvin.sport.data.Exercise
import com.marvin.sport.data.ExerciseInfoBank
import com.marvin.sport.data.Phase
import com.marvin.sport.data.ProgressionStore
import com.marvin.sport.data.Session
import com.marvin.sport.data.Week
import com.marvin.sport.ui.components.ExerciseInfoSheet
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
    val done by store.isSessionDoneFlow(session.id).collectAsState(initial = false)
    val savedNote by store.noteFlow(session.id).collectAsState(initial = "")
    var noteText by remember(savedNote) { mutableStateOf(savedNote) }
    var infoFor by remember { mutableStateOf<Exercise?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(session.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${phase.title.substringBefore(" —")} · ${week.label}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
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
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Échauffement", fontWeight = FontWeight.Bold)
                        Text(session.warmup, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item {
                ExerciseTable(
                    exercises = session.exercises,
                    store = store,
                    onInfoClick = { infoFor = it },
                )
            }
            item {
                Card {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Annotations", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = noteText,
                            onValueChange = {
                                noteText = it
                                scope.launch { store.saveNote(session.id, it) }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Ressenti, RPE, ajustements…") },
                            minLines = 3,
                        )
                    }
                }
            }
            item {
                Button(
                    onClick = {
                        scope.launch {
                            if (done) store.unmarkSessionDone(session)
                            else store.markSessionDone(session)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (done) ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                    ) else ButtonDefaults.buttonColors(),
                ) {
                    if (done) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Séance terminée — Annuler")
                    } else {
                        Text("Marquer la séance comme terminée")
                    }
                }
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
private fun ExerciseTable(
    exercises: List<Exercise>,
    store: ProgressionStore,
    onInfoClick: (Exercise) -> Unit,
) {
    Card {
        Column(modifier = Modifier.padding(8.dp)) {
            Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Column {
                    TableHeader()
                    exercises.forEachIndexed { index, exo ->
                        ExerciseRow(
                            exo = exo,
                            store = store,
                            isAlt = index % 2 == 1,
                            onInfoClick = { onInfoClick(exo) },
                        )
                    }
                }
            }
        }
    }
}

private val COL_INFO = 40.dp
private val COL_EXO = 180.dp
private val COL_SETS = 56.dp
private val COL_REPS = 100.dp
private val COL_LOAD = 100.dp
private val COL_REST = 180.dp
private val COL_NOTE = 160.dp

@Composable
private fun TableHeader() {
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primary)
            .padding(vertical = 8.dp, horizontal = 4.dp),
    ) {
        HeaderCell("", COL_INFO)
        HeaderCell("Exercice", COL_EXO, TextAlign.Start)
        HeaderCell("Séries", COL_SETS)
        HeaderCell("Reps", COL_REPS)
        HeaderCell("Charge", COL_LOAD)
        HeaderCell("Repos", COL_REST)
        HeaderCell("Annotation", COL_NOTE)
    }
}

@Composable
private fun HeaderCell(text: String, width: androidx.compose.ui.unit.Dp, align: TextAlign = TextAlign.Center) {
    Box(modifier = Modifier.width(width).padding(horizontal = 4.dp)) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium,
            textAlign = align,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ExerciseRow(
    exo: Exercise,
    store: ProgressionStore,
    isAlt: Boolean,
    onInfoClick: () -> Unit,
) {
    val completedCount by store.completedCountFlow(exo.name).collectAsState(initial = 0)
    val load = ProgressionStore.progressedLoad(exo.baseLoadKg, completedCount)
    val remaining = ProgressionStore.sessionsBeforeNextStep(completedCount)
    val delta = if (exo.baseLoadKg != null && load != null) load - exo.baseLoadKg else 0.0

    val bg = when {
        exo.isSuperset -> MaterialTheme.colorScheme.surfaceVariant
        isAlt -> MaterialTheme.colorScheme.surface
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .background(bg)
            .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(COL_INFO), contentAlignment = Alignment.Center) {
            IconButton(onClick = onInfoClick, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Outlined.PlayCircle,
                    contentDescription = "Voir la technique",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        BodyCell(
            text = if (exo.isSuperset) "↳ ${exo.name}" else exo.name,
            width = COL_EXO,
            align = TextAlign.Start,
            bold = !exo.isSuperset,
        )
        BodyCell(text = exo.sets, width = COL_SETS)
        BodyCell(text = exo.reps, width = COL_REPS)
        Column(modifier = Modifier.width(COL_LOAD), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = load?.let { formatKg(it) } ?: "—",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            if (delta > 0.0) {
                Text(
                    text = "+${formatKg(delta)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else if (load != null) {
                Text(
                    text = "palier dans $remaining",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        BodyCell(text = exo.rest.ifEmpty { "—" }, width = COL_REST, align = TextAlign.Start)
        BodyCell(text = exo.annotation.ifEmpty { "—" }, width = COL_NOTE, align = TextAlign.Start)
    }
}

@Composable
private fun BodyCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    align: TextAlign = TextAlign.Center,
    bold: Boolean = false,
) {
    Box(modifier = Modifier.width(width).padding(horizontal = 4.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            textAlign = align,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun formatKg(value: Double): String {
    val rounded = if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)
    return "$rounded kg"
}
