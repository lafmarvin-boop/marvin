package com.marvin.sport.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marvin.sport.data.Phase
import com.marvin.sport.data.ProgressionStore
import com.marvin.sport.data.Session
import com.marvin.sport.data.Week
import com.marvin.sport.ui.theme.ProgramAccent

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeekScreen(
    phase: Phase,
    week: Week,
    store: ProgressionStore,
    onBack: () -> Unit,
    onSessionClick: (Int) -> Unit,
) {
    val programId = week.sessions.firstOrNull()?.programId.orEmpty()
    val accent = ProgramAccent.forProgramId(programId)
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            week.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            phase.title.substringAfter("— ", phase.title),
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
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(week.sessions) { _, session ->
                SessionCard(session = session, store = store, accent = accent, onClick = { onSessionClick(session.sessionIndex) })
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: Session,
    store: ProgressionStore,
    accent: Color,
    onClick: () -> Unit,
) {
    val done by store.isSessionDoneFlow(session.id).collectAsState(initial = false)
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (done) com.marvin.sport.ui.theme.SuccessGreen.copy(alpha = 0.14f)
            else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (done) 0.dp else 1.dp),
        border = if (done) androidx.compose.foundation.BorderStroke(
            1.5.dp,
            com.marvin.sport.ui.theme.SuccessGreen.copy(alpha = 0.6f)
        ) else null,
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
                    .background(
                        if (done) com.marvin.sport.ui.theme.SuccessGreen
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = null,
                    tint = if (done) Color.White else MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Séance ${session.sessionIndex + 1}".uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        fontWeight = FontWeight.Bold,
                    )
                    if (done) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(com.marvin.sport.ui.theme.SuccessGreen)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        ) {
                            Text(
                                "FAIT",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    session.title.substringAfter("— ", session.title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${session.exercises.size} exercices",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
