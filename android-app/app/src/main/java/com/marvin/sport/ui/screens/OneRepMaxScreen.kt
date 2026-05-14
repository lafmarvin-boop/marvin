package com.marvin.sport.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.marvin.sport.data.DefaultOneRm
import com.marvin.sport.data.OneRepMaxStore
import com.marvin.sport.data.OneRmEntry
import com.marvin.sport.ui.components.HeroBanner
import com.marvin.sport.ui.theme.ProgramAccent
import kotlinx.coroutines.launch

@Composable
fun OneRepMaxScreen(
    store: OneRepMaxStore,
    contentPadding: PaddingValues,
) {
    val scope = rememberCoroutineScope()
    val values by store.allValuesFlow().collectAsState(initial = emptyMap())
    var editing by remember { mutableStateOf<OneRmEntry?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = 16.dp,
            bottom = 24.dp + contentPadding.calculateBottomPadding(),
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            HeroBanner(
                eyebrow = "Charges",
                title = "Tes 1RM",
                subtitle = "Tape sur une valeur pour la modifier. Toutes les charges de tes séances se recalculent automatiquement à partir de ces valeurs.",
                accent = ProgramAccent.Strength,
            )
        }

        DefaultOneRm.groups.forEach { group ->
            item {
                Text(
                    group.label.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                )
            }
            items(group.entries) { entry ->
                val current = values[entry.key] ?: entry.defaultKg
                OneRmRow(
                    entry = entry,
                    currentKg = current,
                    isModified = current != entry.defaultKg,
                    onClick = { editing = entry },
                )
            }
        }

        item {
            OutlinedButton(
                onClick = { scope.launch { store.resetAll() } },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Réinitialiser aux valeurs par défaut")
            }
        }
    }

    val ed = editing
    if (ed != null) {
        OneRmEditDialog(
            entry = ed,
            currentKg = values[ed.key] ?: ed.defaultKg,
            onDismiss = { editing = null },
            onSave = { newValue ->
                scope.launch { store.set(ed.key, newValue) }
                editing = null
            },
        )
    }
}

@Composable
private fun OneRmRow(
    entry: OneRmEntry,
    currentKg: Double,
    isModified: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.key, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Défaut ${formatKg(entry.defaultKg)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isModified) ProgramAccent.Strength.copy(alpha = 0.16f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    formatKg(currentKg),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isModified) ProgramAccent.Strength else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun OneRmEditDialog(
    entry: OneRmEntry,
    currentKg: Double,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit,
) {
    var text by remember { mutableStateOf(formatKgInput(currentKg)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(entry.key, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(
                    "Charge maximale (1RM) en kg",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                    singleLine = true,
                    suffix = { Text("kg") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Valeur par défaut : ${formatKg(entry.defaultKg)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val parsed = text.replace(',', '.').toDoubleOrNull()
                if (parsed != null && parsed >= 0.0) onSave(parsed)
            }) { Text("Enregistrer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}

private fun formatKg(value: Double): String {
    val rounded = if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)
    return "$rounded kg"
}

private fun formatKgInput(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else "%.1f".format(value)
