package com.marvin.assistant.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.marvin.assistant.service.AssistantService
import com.marvin.assistant.util.Settings as MarvinSettings
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {

    private val requiredPermissions: Array<String>
        get() {
            val perms = mutableListOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.CALL_PHONE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms += Manifest.permission.POST_NOTIFICATIONS
            }
            return perms.toTypedArray()
        }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* result handled in UI by re-checking */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Redirige vers l'onboarding au premier lancement
        val settings = MarvinSettings(this)
        if (!settings.onboardingDone) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        setContent {
            MaterialTheme {
                HomeScreen(
                    onRequestPermissions = { permissionLauncher.launch(requiredPermissions) },
                    onOpenAccessibilitySettings = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                    onOpenMarvinSettings = {
                        val target = if (MarvinSettings(this).isPinSet())
                            PinUnlockActivity::class.java
                        else
                            SettingsActivity::class.java
                        startActivity(Intent(this, target))
                    },
                    onStartService = { AssistantService.start(this) },
                    onStopService = { AssistantService.stop(this) },
                    onOpenDrivingMode = {
                        startActivity(Intent(this, DrivingModeActivity::class.java))
                    },
                    hasPermissions = { hasAllPermissions() }
                )
            }
        }
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }
}

private val ACCENT = Color(0xFF4FC3F7)
private val ACCENT_DEEP = Color(0xFF29B6F6)
private val BG_DEEP = Color(0xFF050B16)
private val BG_MID = Color(0xFF0F2240)
private val TEXT_PRIMARY = Color(0xFFE1F5FE)
private val TEXT_DIM = Color(0xFF607D8B)
private val DANGER = Color(0xFFEF5350)
private val SUCCESS = Color(0xFF66BB6A)

@Composable
private fun HomeScreen(
    onRequestPermissions: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenMarvinSettings: () -> Unit,
    onOpenDrivingMode: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onOpenDrivingMode: () -> Unit,
    hasPermissions: () -> Boolean
) {
    var permsOk by remember { mutableStateOf(hasPermissions()) }
    var serviceRunning by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(BG_MID, BG_DEEP),
                    radius = 1600f
                )
            )
    ) {
        // Background décor: anneaux orbitaux animés en fond
        BackgroundRings(modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))

            // Réacteur central (logo animé)
            ReactorEmblem(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .height(180.dp)
            )

            Spacer(Modifier.height(20.dp))

            Text(
                text = "JARVIS",
                color = TEXT_PRIMARY,
                fontSize = 42.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = 8.sp
            )
            Text(
                text = "Assistant vocal personnel",
                color = TEXT_DIM,
                fontSize = 13.sp,
                letterSpacing = 1.sp
            )

            Spacer(Modifier.height(24.dp))

            // Indicateur de statut
            StatusBadge(
                running = serviceRunning,
                permsOk = permsOk
            )

            Spacer(Modifier.height(36.dp))

            // Bouton principal: démarrer / arrêter
            HexButton(
                label = if (serviceRunning) "ARRÊTER" else "DÉMARRER",
                accent = if (serviceRunning) DANGER else SUCCESS,
                primary = true,
                onClick = {
                    if (serviceRunning) {
                        onStopService()
                        serviceRunning = false
                    } else {
                        onStartService()
                        serviceRunning = true
                    }
                }
            )

            Spacer(Modifier.height(24.dp))

            // Boutons secondaires
            HexButton(
                label = if (permsOk) "PERMISSIONS  ✓" else "ACCORDER LES PERMISSIONS",
                accent = if (permsOk) SUCCESS else ACCENT,
                onClick = {
                    onRequestPermissions()
                    permsOk = hasPermissions()
                }
            )
            Spacer(Modifier.height(10.dp))
            HexButton(
                label = "ACCESSIBILITÉ",
                accent = ACCENT,
                onClick = onOpenAccessibilitySettings
            )
            Spacer(Modifier.height(10.dp))
            HexButton(
                label = "RÉGLAGES",
                accent = ACCENT,
                onClick = onOpenMarvinSettings
            )
            Spacer(Modifier.height(10.dp))
            HexButton(
                label = "MODE VOITURE",
                accent = SUCCESS,
                onClick = onOpenDrivingMode
            )

            Spacer(Modifier.weight(1f))

            Text(
                text = "Dis « Jarvis » à voix haute pour me parler.",
                color = TEXT_DIM,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun StatusBadge(running: Boolean, permsOk: Boolean) {
    val (label, color) = when {
        !permsOk -> "PERMISSIONS REQUISES" to DANGER
        running -> "EN ÉCOUTE" to SUCCESS
        else -> "INACTIF" to TEXT_DIM
    }
    val infinite = rememberInfiniteTransition(label = "status")
    val pulse by infinite.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "pulse"
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = if (running) pulse else 0.5f))
        )
        Spacer(Modifier.height(0.dp))
        androidx.compose.foundation.layout.Spacer(Modifier.padding(horizontal = 6.dp))
        Text(
            text = label,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp
        )
    }
}

/**
 * Bouton stylisé "tech" : fond translucide, bordure cyan, label majuscule.
 * `primary` = bouton plus large et plus accentué pour le démarrer/arrêter.
 */
@Composable
private fun HexButton(
    label: String,
    accent: Color,
    primary: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (primary) 64.dp else 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = if (primary) 0.18f else 0.08f))
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = accent,
            fontSize = if (primary) 18.sp else 14.sp,
            fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal,
            letterSpacing = 2.sp,
            fontFamily = FontFamily.SansSerif
        )
    }
}

/** Réacteur central — anneaux concentriques en rotation, triangle central. */
@Composable
private fun ReactorEmblem(modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "reactor")
    val rotOuter by infinite.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(28_000, easing = LinearEasing), RepeatMode.Restart),
        label = "rotO"
    )
    val rotMid by infinite.animateFloat(
        360f, 0f,
        infiniteRepeatable(tween(16_000, easing = LinearEasing), RepeatMode.Restart),
        label = "rotM"
    )
    val rotIn by infinite.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(7_000, easing = LinearEasing), RepeatMode.Restart),
        label = "rotI"
    )
    val pulse by infinite.animateFloat(
        0.96f, 1.04f,
        infiniteRepeatable(tween(2200), RepeatMode.Reverse),
        label = "pulse"
    )

    Canvas(modifier = modifier.scale(pulse)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.minDimension / 2f
        val center = Offset(cx, cy)

        // Halo
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(ACCENT.copy(alpha = 0.30f), Color.Transparent),
                center = center, radius = r
            ),
            radius = r, center = center
        )

        // Anneau extérieur en pointillés
        rotate(rotOuter, pivot = center) {
            val arcs = 12
            val gap = 4f
            val arc = 360f / arcs - gap
            for (i in 0 until arcs) {
                drawArc(
                    color = ACCENT,
                    startAngle = i * (arc + gap),
                    sweepAngle = arc,
                    useCenter = false,
                    topLeft = Offset(cx - r * 0.92f, cy - r * 0.92f),
                    size = androidx.compose.ui.geometry.Size(r * 1.84f, r * 1.84f),
                    style = Stroke(width = 3f)
                )
            }
        }

        // Anneau du milieu + ticks
        rotate(rotMid, pivot = center) {
            drawCircle(
                color = ACCENT.copy(alpha = 0.85f),
                radius = r * 0.7f, center = center,
                style = Stroke(width = 2f)
            )
            for (i in 0 until 6) {
                val a = Math.toRadians((i * 60f).toDouble())
                drawLine(
                    color = ACCENT,
                    start = Offset(cx + (r * 0.7f) * cos(a).toFloat(),
                                   cy + (r * 0.7f) * sin(a).toFloat()),
                    end = Offset(cx + (r * 0.55f) * cos(a).toFloat(),
                                 cy + (r * 0.55f) * sin(a).toFloat()),
                    strokeWidth = 3f
                )
            }
        }

        // Anneau intérieur
        drawCircle(
            color = TEXT_PRIMARY,
            radius = r * 0.42f, center = center,
            style = Stroke(width = 2.5f)
        )
        drawCircle(color = Color(0xFF071528), radius = r * 0.40f, center = center)

        // 3 arcs rapides en rotation à l'intérieur
        rotate(rotIn, pivot = center) {
            for (i in 0 until 3) {
                drawArc(
                    color = ACCENT.copy(alpha = 0.7f),
                    startAngle = i * 120f,
                    sweepAngle = 60f,
                    useCenter = false,
                    topLeft = Offset(cx - r * 0.32f, cy - r * 0.32f),
                    size = androidx.compose.ui.geometry.Size(r * 0.64f, r * 0.64f),
                    style = Stroke(width = 2.5f)
                )
            }
        }

        // Triangle central blanc
        val triPath = androidx.compose.ui.graphics.Path().apply {
            val tr = r * 0.18f
            moveTo(cx, cy - tr)
            lineTo(cx + tr * 0.866f, cy + tr * 0.5f)
            lineTo(cx - tr * 0.866f, cy + tr * 0.5f)
            close()
        }
        drawPath(triPath, color = Color.White)

        // Cœur lumineux
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White, ACCENT_DEEP.copy(alpha = 0.5f), Color.Transparent),
                center = center, radius = r * 0.12f
            ),
            radius = r * 0.12f, center = center
        )
    }
}

/** Anneaux orbitaux subtils en arrière-plan, rotation très lente. */
@Composable
private fun BackgroundRings(modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "bg")
    val rot by infinite.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(120_000, easing = LinearEasing), RepeatMode.Restart),
        label = "bg-rot"
    )
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height * 0.30f // un peu vers le haut, derrière le réacteur
        val maxR = size.maxDimension * 0.8f
        rotate(rot, pivot = Offset(cx, cy)) {
            for (i in 1..6) {
                val r = maxR * (i / 6f)
                drawCircle(
                    color = ACCENT.copy(alpha = 0.04f + i * 0.01f),
                    radius = r,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1f)
                )
            }
        }
    }
}
