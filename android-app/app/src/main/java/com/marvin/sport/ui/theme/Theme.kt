package com.marvin.sport.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// --- Palette principale ----------------------------------------------------
// Orange vif inspiré du monde du sport (énergie, action) en couleur primaire,
// secondaires neutres pour laisser respirer les statistiques.

private val Orange50 = Color(0xFFFFF1EC)
private val Orange100 = Color(0xFFFFDBC8)
private val Orange200 = Color(0xFFFFB088)
private val Orange400 = Color(0xFFFF7A45)
private val Orange500 = Color(0xFFF4511E)
private val Orange600 = Color(0xFFE64A19)
private val Orange700 = Color(0xFFD84315)

private val Slate50 = Color(0xFFF6F8FB)
private val Slate100 = Color(0xFFECF1F6)
private val Slate200 = Color(0xFFD7DFE8)
private val Slate400 = Color(0xFF889CB0)
private val Slate700 = Color(0xFF334155)
private val Slate800 = Color(0xFF1E293B)
private val Slate900 = Color(0xFF0F172A)
private val Slate950 = Color(0xFF080F1C)

private val Gold = Color(0xFFFFB300)
private val Mint = Color(0xFF22C55E)

private val LightColors = lightColorScheme(
    primary = Orange600,
    onPrimary = Color.White,
    primaryContainer = Orange100,
    onPrimaryContainer = Color(0xFF3D1100),
    secondary = Slate800,
    onSecondary = Color.White,
    secondaryContainer = Slate100,
    onSecondaryContainer = Slate900,
    tertiary = Gold,
    onTertiary = Color(0xFF2C1B00),
    background = Slate50,
    onBackground = Slate900,
    surface = Color.White,
    onSurface = Slate900,
    surfaceVariant = Slate100,
    onSurfaceVariant = Slate700,
    surfaceTint = Orange500,
    outline = Slate400,
    outlineVariant = Slate200,
    error = Color(0xFFDC2626),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Orange400,
    onPrimary = Color(0xFF2E0E00),
    primaryContainer = Orange700,
    onPrimaryContainer = Orange100,
    secondary = Slate200,
    onSecondary = Slate900,
    secondaryContainer = Slate800,
    onSecondaryContainer = Slate100,
    tertiary = Gold,
    onTertiary = Color(0xFF2C1B00),
    background = Slate950,
    onBackground = Slate100,
    surface = Slate900,
    onSurface = Slate100,
    surfaceVariant = Slate800,
    onSurfaceVariant = Color(0xFFB8C2CC),
    surfaceTint = Orange400,
    outline = Slate400,
    outlineVariant = Slate700,
    error = Color(0xFFEF4444),
    onError = Color(0xFF270000),
)

private val ExpressiveTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Black, fontSize = 57.sp, letterSpacing = (-0.25).sp),
    displayMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Black, fontSize = 45.sp),
    displaySmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.ExtraBold, fontSize = 36.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 28.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 24.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, letterSpacing = 0.5.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 0.5.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp, letterSpacing = 0.15.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp, letterSpacing = 0.25.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 12.sp, letterSpacing = 0.4.sp),
)

/** Couleurs spécifiques par programme — accent visuel pour distinguer les modules. */
object ProgramAccent {
    val Strength = Color(0xFF16A34A)   // vert sportif
    val Striking = Color(0xFFE11D48)   // rouge boxe
    val Grappling = Color(0xFF7C3AED)  // violet grappling
    val Running = Color(0xFF0EA5E9)    // bleu running

    fun forProgramId(id: String): Color = when (id) {
        "strength" -> Strength
        "striking" -> Striking
        "grappling" -> Grappling
        else -> Running
    }
}

/** Couleurs utilitaires (succès / progression). */
val SuccessGreen = Mint

@Composable
fun MarvinSportTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            window.statusBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            window.navigationBarColor = colors.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = colors, typography = ExpressiveTypography, content = content)
}
