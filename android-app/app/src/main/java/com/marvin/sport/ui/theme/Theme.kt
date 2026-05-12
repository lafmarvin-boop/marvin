package com.marvin.sport.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF66BB6A),
    onPrimary = Color(0xFF003910),
    secondary = Color(0xFFB6CCB1),
    background = Color(0xFF101510),
    surface = Color(0xFF1A1F1A),
    surfaceVariant = Color(0xFF2A302A),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E7D32),
    onPrimary = Color.White,
    secondary = Color(0xFF4CAF50),
    background = Color(0xFFF4F7F2),
    surface = Color.White,
    surfaceVariant = Color(0xFFE6EDE2),
)

@Composable
fun MarvinSportTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
