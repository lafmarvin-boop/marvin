package com.marvin.assistant.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.marvin.assistant.util.Settings

/**
 * Composable qui renvoie la couleur d'accent actuelle.
 * Lue depuis Settings.accentColor à chaque recomposition (effet visible
 * immédiatement après changement dans Réglages).
 */
@Composable
fun accentColor(): Color {
    val ctx = LocalContext.current
    val name = Settings(ctx).accentColor
    val argb = Settings.ACCENT_PRESETS[name] ?: Settings.ACCENT_PRESETS["cyan"]!!
    return Color(argb)
}

/** Variante non-Composable pour les classes Activity hors Compose. */
fun accentColorFor(ctx: Context): Int {
    val name = Settings(ctx).accentColor
    return Settings.ACCENT_PRESETS[name] ?: Settings.ACCENT_PRESETS["cyan"]!!
}
