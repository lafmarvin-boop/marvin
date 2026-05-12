package com.marvin.sport.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marvin.sport.data.Phase
import com.marvin.sport.data.ProgressionStore
import com.marvin.sport.data.Session
import com.marvin.sport.data.Week

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeekScreen(
    phase: Phase,
    week: Week,
    store: ProgressionStore,
    onBack: () -> Unit,
    onSessionClick: (Int) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${phase.title.substringBefore(" —")} · ${week.label}") },
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
            itemsIndexed(week.sessions) { idx, session ->
                SessionCard(session = session, store = store, onClick = { onSessionClick(idx) })
            }
        }
    }
}

@Composable
private fun SessionCard(session: Session, store: ProgressionStore, onClick: () -> Unit) {
    val done by store.isSessionDoneFlow(session.id).collectAsState(initial = false)
    Card(onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (done) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    session.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${session.exercises.size} exercices",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
