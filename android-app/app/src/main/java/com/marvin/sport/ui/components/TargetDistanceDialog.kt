package com.marvin.sport.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Dialogue de choix d'une distance objectif. Le bouton "Démarrer" appelle
 * `onPicked` avec la distance en mètres. Pressets en chips horizontales + champ
 * custom (en km).
 */
@Composable
fun TargetDistanceDialog(
    accent: Color,
    onPicked: (targetM: Double) -> Unit,
    onDismiss: () -> Unit,
) {
    val presets = listOf(1.0, 2.0, 3.0, 5.0, 7.0, 10.0)
    var selectedKm by remember { mutableStateOf<Double?>(null) }
    var customText by remember { mutableStateOf("") }

    val picked: Double? = selectedKm
        ?: customText.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Distance objectif", fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(
                    "Tu recevras un bip à 25 %, 50 %, 75 % du parcours, et un bip plus marqué à l'arrivée.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "Presets",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    presets.forEach { km ->
                        val isSelected = selectedKm == km
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) accent
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable {
                                    selectedKm = km
                                    customText = ""
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Text(
                                "%.0f km".format(km),
                                color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "Ou distance personnalisée",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = customText,
                    onValueChange = {
                        customText = it.filter { c -> c.isDigit() || c == '.' || c == ',' }
                        if (customText.isNotEmpty()) selectedKm = null
                    },
                    singleLine = true,
                    suffix = { Text("km") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    placeholder = { Text("ex. 4,5") },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { picked?.let { onPicked(it * 1000.0) } },
                enabled = picked != null,
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.White),
            ) { Text("Démarrer") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annuler") }
        },
    )
}
