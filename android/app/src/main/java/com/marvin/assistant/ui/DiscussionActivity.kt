package com.marvin.assistant.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * Plein écran "interface vocale" affiché pendant le mode discussion.
 *
 * Visuel : anneaux concentriques en rotation, glow cyan, triangle central
 * qui pulse. La couleur et la vitesse de pulsation varient selon la phase
 * (écoute / réflexion / parole). Conception géométrique originale.
 */
class DiscussionActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Auto-finish quand le service repasse en Idle.
        lifecycleScope.launch {
            DiscussionStateHolder.phase.collect { p ->
                if (p is DiscussionPhase.Idle && !isFinishing) finish()
            }
        }
        setContent {
            MaterialTheme {
                DiscussionScreen()
            }
        }
    }
}

@Composable
private fun DiscussionScreen() {
    val phase by DiscussionStateHolder.phase.collectAsState()
    val lastUser by DiscussionStateHolder.lastUserText.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF0F2240), Color(0xFF050B16)),
                    radius = 1400f
                )
            )
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            ReactorVisualizer(
                phase = phase,
                modifier = Modifier.fillMaxWidth(0.85f).aspectRatio(1f)
            )
            Spacer(Modifier.height(28.dp))
            PhaseLabel(phase)
            Spacer(Modifier.height(8.dp))
            if (lastUser.isNotBlank()) {
                Text(
                    text = "« $lastUser »",
                    color = Color(0xFF80D8FF).copy(alpha = 0.55f),
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
            if (phase is DiscussionPhase.Speaking) {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = (phase as DiscussionPhase.Speaking).text,
                    color = Color(0xFFE1F5FE),
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        Text(
            text = "Dis « merci » pour terminer",
            color = Color(0xFF4FC3F7).copy(alpha = 0.4f),
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        )
    }
}

@Composable
private fun PhaseLabel(phase: DiscussionPhase) {
    val text = when (phase) {
        DiscussionPhase.Idle -> ""
        DiscussionPhase.Listening -> "J'écoute…"
        DiscussionPhase.Thinking -> "Je réfléchis…"
        is DiscussionPhase.Speaking -> "Je parle"
    }
    Text(
        text = text,
        color = Color(0xFFE1F5FE),
        fontSize = 22.sp,
        fontWeight = FontWeight.Light
    )
}

@Composable
private fun ReactorVisualizer(phase: DiscussionPhase, modifier: Modifier = Modifier) {
    val infinite = rememberInfiniteTransition(label = "reactor")

    val rotOuter by infinite.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(28_000, easing = LinearEasing), RepeatMode.Restart),
        label = "rot_outer"
    )
    val rotMiddle by infinite.animateFloat(
        360f, 0f,
        infiniteRepeatable(tween(18_000, easing = LinearEasing), RepeatMode.Restart),
        label = "rot_middle"
    )
    val rotInner by infinite.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(9_000, easing = LinearEasing), RepeatMode.Restart),
        label = "rot_inner"
    )

    val pulseDur = when (phase) {
        DiscussionPhase.Listening -> 700
        is DiscussionPhase.Speaking -> 350
        DiscussionPhase.Thinking -> 1500
        DiscussionPhase.Idle -> 2200
    }
    val pulse by infinite.animateFloat(
        0.93f, 1.07f,
        infiniteRepeatable(
            tween(pulseDur, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ), label = "pulse"
    )

    val targetColor = when (phase) {
        DiscussionPhase.Listening -> Color(0xFF80D8FF)
        is DiscussionPhase.Speaking -> Color(0xFF29B6F6)
        DiscussionPhase.Thinking -> Color(0xFF7C4DFF)
        DiscussionPhase.Idle -> Color(0xFF4FC3F7)
    }
    val color by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(600), label = "color"
    )

    Canvas(modifier = modifier.scale(pulse)) {
        drawReactor(color, rotOuter, rotMiddle, rotInner)
    }
}

private fun DrawScope.drawReactor(
    color: Color,
    rotOuter: Float,
    rotMiddle: Float,
    rotInner: Float
) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val r = size.minDimension / 2f
    val center = Offset(cx, cy)

    // Halo radial
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.35f), Color.Transparent),
            center = center,
            radius = r
        ),
        radius = r,
        center = center
    )

    // Anneau extérieur en pointillés (rotation lente)
    rotate(rotOuter, pivot = center) {
        val arcCount = 12
        val gap = 4f
        val arcLen = 360f / arcCount - gap
        for (i in 0 until arcCount) {
            val startAngle = i * (arcLen + gap)
            drawArc(
                color = color,
                startAngle = startAngle,
                sweepAngle = arcLen,
                useCenter = false,
                topLeft = Offset(cx - r * 0.92f, cy - r * 0.92f),
                size = androidx.compose.ui.geometry.Size(r * 1.84f, r * 1.84f),
                style = Stroke(width = 3f)
            )
        }
    }

    // Anneau du milieu (continu, rotation inverse)
    rotate(rotMiddle, pivot = center) {
        drawCircle(
            color = color.copy(alpha = 0.85f),
            radius = r * 0.7f,
            center = center,
            style = Stroke(width = 2f)
        )
        // Six "ticks" radiaux
        for (i in 0 until 6) {
            val angle = Math.toRadians((i * 60f).toDouble())
            val outer = Offset(
                cx + (r * 0.7f) * cos(angle).toFloat(),
                cy + (r * 0.7f) * sin(angle).toFloat()
            )
            val inner = Offset(
                cx + (r * 0.55f) * cos(angle).toFloat(),
                cy + (r * 0.55f) * sin(angle).toFloat()
            )
            drawLine(
                color = color,
                start = outer, end = inner,
                strokeWidth = 3f
            )
        }
    }

    // Anneau intérieur
    drawCircle(
        color = color.copy(alpha = 0.95f),
        radius = r * 0.42f,
        center = center,
        style = Stroke(width = 2.5f)
    )

    // Cercle de fond intérieur (sombre)
    drawCircle(
        color = Color(0xFF071528),
        radius = r * 0.40f,
        center = center
    )

    // Trois petits arcs en rotation rapide à l'intérieur
    rotate(rotInner, pivot = center) {
        for (i in 0 until 3) {
            drawArc(
                color = color.copy(alpha = 0.7f),
                startAngle = i * 120f,
                sweepAngle = 60f,
                useCenter = false,
                topLeft = Offset(cx - r * 0.32f, cy - r * 0.32f),
                size = androidx.compose.ui.geometry.Size(r * 0.64f, r * 0.64f),
                style = Stroke(width = 2.5f)
            )
        }
    }

    // Triangle central
    val triPath = androidx.compose.ui.graphics.Path().apply {
        val tr = r * 0.18f
        moveTo(cx, cy - tr)
        lineTo(cx + tr * 0.866f, cy + tr * 0.5f)
        lineTo(cx - tr * 0.866f, cy + tr * 0.5f)
        close()
    }
    drawPath(triPath, color = Color(0xFFFFFFFF))

    // Cœur lumineux brillant
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Color.White, color.copy(alpha = 0.5f), Color.Transparent),
            center = center,
            radius = r * 0.12f
        ),
        radius = r * 0.12f,
        center = center
    )
}
