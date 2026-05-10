package com.marvin.assistant.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marvin.assistant.service.AssistantService
import com.marvin.assistant.util.Settings

/**
 * Mode voiture : interface plein écran avec gros boutons.
 *
 * Particularités :
 *  - Écran toujours allumé tant qu'on est dans cette activity (FLAG_KEEP_SCREEN_ON)
 *  - Active automatiquement les notifications proactives (lues à voix haute)
 *  - Visualisation de la phase Jarvis (Listening / Thinking / Speaking)
 *  - Bouton « Quitter » qui désactive le mode voiture
 *
 * Désactive le mode voiture (et restaure le paramètre précédent des notifs
 * proactives) quand on sort de l'activity.
 */
class DrivingModeActivity : ComponentActivity() {

    private lateinit var settings: Settings
    private var previousProactiveNotifs = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        settings = Settings(this)
        // Force les notifs proactives pendant le mode voiture
        previousProactiveNotifs = settings.proactiveNotificationsEnabled
        settings.proactiveNotificationsEnabled = true
        // S'assure que le service tourne
        AssistantService.start(this)

        setContent { DrivingModeScreen(onExit = { finish() }) }
    }

    override fun onDestroy() {
        // Restaure l'état précédent du toggle proactif
        settings.proactiveNotificationsEnabled = previousProactiveNotifs
        super.onDestroy()
    }
}

@Composable
private fun DrivingModeScreen(onExit: () -> Unit) {
    val phase by DiscussionStateHolder.phase.collectAsState()
    val isListening = phase == DiscussionPhase.Listening
    val isThinking = phase == DiscussionPhase.Thinking
    val isSpeaking = phase is DiscussionPhase.Speaking

    val bgColor = when {
        isThinking -> Color(0xFF1A237E)  // bleu profond
        isSpeaking -> Color(0xFF1B5E20)  // vert profond
        else -> Color(0xFF000000)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(bgColor, Color.Black)
                )
            )
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // En-tête
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "MODE VOITURE",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF00E5FF)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Jarvis t'écoute en permanence. Dis « Jarvis » + ta commande.",
                    fontSize = 16.sp,
                    color = Color(0xFFB0BEC5),
                    textAlign = TextAlign.Center
                )
            }

            // Indicateur central XL
            val phaseLabel = when {
                isListening -> "À l'écoute"
                isThinking -> "Réflexion..."
                isSpeaking -> "Réponse..."
                else -> "Inactif"
            }
            val phaseColor = when {
                isListening -> Color(0xFF00E5FF)
                isThinking -> Color(0xFFFFB300)
                isSpeaking -> Color(0xFF4CAF50)
                else -> Color(0xFF607D8B)
            }
            PhasePulse(label = phaseLabel, color = phaseColor)

            // Bouton quitter XL
            Button(
                onClick = onExit,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                shape = RoundedCornerShape(40.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFB71C1C),
                    contentColor = Color.White
                )
            ) {
                Text(
                    "Quitter le mode voiture",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun PhasePulse(label: String, color: Color) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing)
        ),
        label = "pulse-scale"
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .height((240 * scale).dp)
                .fillMaxWidth(scale)
                .background(color = color.copy(alpha = 0.2f), shape = RoundedCornerShape(120.dp))
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    label,
                    fontSize = 36.sp,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            }
        }
    }
}
