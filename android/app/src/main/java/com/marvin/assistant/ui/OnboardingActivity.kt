package com.marvin.assistant.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marvin.assistant.util.Settings as MarvinSettings

/**
 * Premier lancement : guide pas-à-pas (5 étapes) :
 * 1. Bienvenue + intro
 * 2. Permissions (mic, notifs, contacts, sms, etc.)
 * 3. Clé API Claude (optionnelle, peut être ajoutée plus tard)
 * 4. Choix du backend (Claude / Gemma)
 * 5. Fin : démarrer le service
 *
 * Une fois terminé, on note dans Settings que l'onboarding est fait
 * pour ne plus le re-afficher.
 */
class OnboardingActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* result handled via UI re-check */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                OnboardingScreen(
                    onDone = {
                        MarvinSettings(this).onboardingDone = true
                        startActivity(Intent(this, MainActivity::class.java))
                        finish()
                    },
                    onRequestPermissions = {
                        permissionLauncher.launch(REQUIRED_PERMISSIONS)
                    },
                    onOpenNotifAccess = {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }
                )
            }
        }
    }

    companion object {
        val REQUIRED_PERMISSIONS = arrayOf(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.POST_NOTIFICATIONS,
            android.Manifest.permission.READ_CONTACTS,
            android.Manifest.permission.READ_SMS,
            android.Manifest.permission.SEND_SMS,
            android.Manifest.permission.READ_CALL_LOG,
            android.Manifest.permission.CALL_PHONE,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.READ_CALENDAR,
            android.Manifest.permission.WRITE_CALENDAR,
            android.Manifest.permission.CAMERA
        )
    }
}

@Composable
private fun OnboardingScreen(
    onDone: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenNotifAccess: () -> Unit
) {
    var step by remember { mutableIntStateOf(0) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val settings = remember { MarvinSettings(ctx) }
    var apiKey by remember { mutableStateOf(settings.anthropicApiKey) }
    val accent = Color(0xFF00E5FF)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0A0E27), Color.Black)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            // Indicateur d'étape
            Row {
                repeat(5) { i ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .height(4.dp)
                            .background(if (i <= step) accent else Color(0xFF263238))
                            .fillMaxWidth(0.18f)
                    )
                }
            }
            Spacer(Modifier.height(28.dp))

            when (step) {
                0 -> StepWelcome(accent)
                1 -> StepPermissions(accent, onRequestPermissions, onOpenNotifAccess)
                2 -> StepApiKey(accent, apiKey) { apiKey = it }
                3 -> StepBackend(accent, settings)
                4 -> StepFinish(accent)
            }

            Spacer(Modifier.height(28.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                if (step > 0) {
                    Button(
                        onClick = { step-- },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF263238))
                    ) { Text("Précédent") }
                    Spacer(Modifier.height(0.dp).fillMaxWidth(0.05f))
                }
                Button(
                    onClick = {
                        if (step == 2) settings.anthropicApiKey = apiKey.trim()
                        if (step < 4) step++ else onDone()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color.Black)
                ) { Text(if (step < 4) "Suivant" else "C'est parti !", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun StepWelcome(accent: Color) {
    Text("Bienvenue", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = accent)
    Spacer(Modifier.height(8.dp))
    Text(
        "Je suis Jarvis, ton assistant vocal local. En 5 étapes courtes, " +
            "je vais te demander quelques autorisations et tu pourras me parler. " +
            "Tu pourras tout reconfigurer plus tard dans Réglages.",
        color = Color(0xFFB0BEC5),
        fontSize = 16.sp
    )
}

@Composable
private fun StepPermissions(accent: Color, onRequest: () -> Unit, onOpenNotif: () -> Unit) {
    Text("Permissions", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = accent)
    Spacer(Modifier.height(8.dp))
    Text(
        "J'ai besoin d'accéder à : micro, SMS, contacts, appels, calendrier, " +
            "caméra, position. Accepte tout sinon certaines commandes ne marcheront pas.",
        color = Color(0xFFB0BEC5), fontSize = 14.sp
    )
    Spacer(Modifier.height(16.dp))
    Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
        Text("Accorder les permissions standard")
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "L'accès aux notifications (pour lire les notifs entrantes) se gère " +
            "à part dans Paramètres Android.",
        color = Color(0xFF607D8B), fontSize = 12.sp
    )
    Button(onClick = onOpenNotif, modifier = Modifier.fillMaxWidth()) {
        Text("Accès aux notifications")
    }
}

@Composable
private fun StepApiKey(accent: Color, apiKey: String, onChange: (String) -> Unit) {
    Text("Clé API Claude (optionnelle)", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = accent)
    Spacer(Modifier.height(8.dp))
    Text(
        "Pour utiliser Claude (Anthropic) : crée une clé sur " +
            "console.anthropic.com/settings/keys (recharge 5 € minimum). " +
            "Sans clé, j'utiliserai Gemma local — moins capable mais gratuit.",
        color = Color(0xFFB0BEC5), fontSize = 14.sp
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = apiKey,
        onValueChange = onChange,
        label = { Text("Clé Claude (sk-ant-api03-...)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun StepBackend(accent: Color, settings: MarvinSettings) {
    Text("Choix du moteur IA", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = accent)
    Spacer(Modifier.height(8.dp))
    Text(
        "Tu pourras changer à tout moment dans Réglages.",
        color = Color(0xFFB0BEC5), fontSize = 14.sp
    )
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = {
            settings.backendChoice = com.marvin.assistant.util.LlmBackendChoice.CLOUD_CLAUDE
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text("Claude (cloud, ~5 €/mois) — recommandé") }
    Spacer(Modifier.height(8.dp))
    Button(
        onClick = {
            settings.backendChoice = com.marvin.assistant.util.LlmBackendChoice.LOCAL_GEMMA
        },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF263238))
    ) { Text("Gemma local (gratuit, qualité moindre)") }
}

@Composable
private fun StepFinish(accent: Color) {
    Text("Tout est prêt", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = accent)
    Spacer(Modifier.height(8.dp))
    Text(
        "Dis simplement « Jarvis » suivi de ta question. Pour découvrir tes " +
            "commandes : « Jarvis qu'est-ce que tu sais faire ». Bonnes " +
            "discussions !",
        color = Color(0xFFB0BEC5), fontSize = 16.sp
    )
}
