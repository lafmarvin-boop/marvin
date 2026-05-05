package com.marvin.assistant.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.marvin.assistant.audio.SpeakerVerifierFactory
import com.marvin.assistant.service.AssistantService
import com.marvin.assistant.util.ClaudeModel
import com.marvin.assistant.util.LlmBackendChoice
import com.marvin.assistant.util.Settings

class SettingsActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = Settings(this)
        setContent {
            MaterialTheme {
                Scaffold(
                    topBar = { TopAppBar(title = { Text("Réglages Marvin") }) }
                ) { padding ->
                    Surface(
                        modifier = Modifier.padding(padding).fillMaxWidth()
                    ) {
                        SettingsScreen(settings, onClose = { finish() })
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(settings: Settings, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var backend by remember { mutableStateOf(settings.backendChoice) }
    var model by remember { mutableStateOf(settings.claudeModel) }
    var apiKey by remember { mutableStateOf(settings.anthropicApiKey) }
    var revealKey by remember { mutableStateOf(false) }
    var confirmSensitive by remember { mutableStateOf(settings.confirmSensitiveActions) }
    val toolEnabled = remember {
        mutableStateMapOf<String, Boolean>().apply {
            Settings.ALL_TOOL_NAMES.forEach { name -> put(name, settings.isToolEnabled(name)) }
        }
    }
    var pinSet by remember { mutableStateOf(settings.isPinSet()) }
    var newPin by remember { mutableStateOf("") }
    var smsAllowlistText by remember {
        mutableStateOf(settings.smsAllowlist.joinToString(", "))
    }
    var voiceBioEnabled by remember { mutableStateOf(settings.voiceBiometricEnabled) }
    var voiceBioThreshold by remember { mutableStateOf(settings.voiceBiometricThreshold) }
    val verifier = remember { SpeakerVerifierFactory.create(ctx) }
    var voiceBioReady by remember { mutableStateOf(verifier.isReady()) }
    var voiceBioEnrolled by remember { mutableStateOf(verifier.isEnrolled()) }
    val quotaUsed = remember { settings.quotaUsedToday() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // ------ CERVEAU IA ------
        Text("Cerveau IA", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        BackendOption(
            label = "Cloud — Claude (qualité top, ~0,02 €/100 req)",
            selected = backend == LlmBackendChoice.CLOUD_CLAUDE,
            onClick = { backend = LlmBackendChoice.CLOUD_CLAUDE }
        )
        BackendOption(
            label = "Local — Gemma 2 2B (gratuit, qualité moindre, pas d'outils)",
            selected = backend == LlmBackendChoice.LOCAL_GEMMA,
            onClick = { backend = LlmBackendChoice.LOCAL_GEMMA }
        )

        if (backend == LlmBackendChoice.CLOUD_CLAUDE) {
            Spacer(Modifier.height(20.dp))
            Text("Modèle Claude", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            ModelOption(
                label = "Haiku 4.5 (recommandé, rapide, ~5× moins cher)",
                selected = model == ClaudeModel.HAIKU,
                onClick = { model = ClaudeModel.HAIKU }
            )
            ModelOption(
                label = "Sonnet 4.6 (raisonnement plus poussé)",
                selected = model == ClaudeModel.SONNET,
                onClick = { model = ClaudeModel.SONNET }
            )

            Spacer(Modifier.height(20.dp))
            Text("Clé API Anthropic", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Crée-toi une clé sur console.anthropic.com puis colle-la ci-dessous (5 € de crédit prépayé suffit pour des mois).",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it.trim() },
                label = { Text("sk-ant-...") },
                visualTransformation = if (revealKey) VisualTransformation.None
                    else PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = revealKey, onClick = { revealKey = !revealKey })
                Text("Afficher la clé")
            }
            Spacer(Modifier.height(16.dp))
            Text("Quota quotidien : ${settings.dailyLimit} requêtes / jour")
            Text("Utilisées aujourd'hui : $quotaUsed")
        } else {
            Spacer(Modifier.height(20.dp))
            Text("Modèle Gemma local", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Télécharge gemma-2-2b-it-cpu-int4.task depuis HuggingFace " +
                    "(google/gemma-2-2b-it, accepter la licence) puis pousse-le " +
                    "dans /sdcard/Android/data/com.marvin.assistant/files/.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        // ------ SÉCURITÉ ------
        Spacer(Modifier.height(28.dp))
        Divider()
        Spacer(Modifier.height(20.dp))
        Text("Sécurité", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        ToggleRow(
            label = "Confirmer les actions sensibles",
            description = "SMS, appels, WhatsApp : Marvin demande oralement « tu confirmes ? » avant d'exécuter.",
            checked = confirmSensitive,
            onChange = { confirmSensitive = it }
        )

        Spacer(Modifier.height(16.dp))
        Text(
            "PIN d'accès aux Réglages",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            if (pinSet) "PIN actif. Saisis 4-6 chiffres pour le changer, ou laisse vide et appuie « Désactiver le PIN »."
            else "Pas de PIN. Saisis 4-6 chiffres pour en créer un.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = newPin,
            onValueChange = { input -> newPin = input.filter { it.isDigit() }.take(6) },
            label = { Text("Nouveau PIN (4-6 chiffres)") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    if (newPin.length in 4..6) {
                        settings.setPin(newPin)
                        pinSet = true
                        newPin = ""
                    }
                },
                enabled = newPin.length in 4..6,
                modifier = Modifier.weight(1f)
            ) { Text(if (pinSet) "Changer le PIN" else "Activer le PIN") }
            if (pinSet) {
                Button(
                    onClick = {
                        settings.setPin("")
                        pinSet = false
                        newPin = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF607D8B)),
                    modifier = Modifier.weight(1f)
                ) { Text("Désactiver") }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "Allowlist SMS (optionnel)",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            "Si non vide, Claude ne peut lire que les SMS de contacts dont le nom contient un de ces fragments. Sépare par des virgules. Ex: « Marie, Papa, école ».",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = smsAllowlistText,
            onValueChange = { smsAllowlistText = it },
            label = { Text("Contacts autorisés (vide = tous)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(20.dp))
        Text(
            "Voice biometric",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        when {
            !voiceBioReady -> Text(
                "Modèle d'embedding vocal absent. Pour activer cette protection, " +
                    "télécharge un modèle (ex. WeSpeaker) et pousse-le dans le " +
                    "stockage de l'app sous le nom speaker.onnx (cf. README).",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF607D8B)
            )
            !voiceBioEnrolled -> Text(
                "Pas encore enrôlé. Tape « Enrôler ma voix » pour enregistrer 5 " +
                    "échantillons de toi disant « Jarvis ». Une fois enrôlé, tu " +
                    "pourras activer le toggle.",
                style = MaterialTheme.typography.bodySmall
            )
            else -> {
                Text(
                    "Voix enrôlée. Active le toggle pour rejeter les wake words " +
                        "qui ne correspondent pas à ta voix.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                ToggleRow(
                    label = "Activer la vérif d'identité vocale",
                    description = "Marvin n'écoutera que toi (sous réserve de la qualité du modèle).",
                    checked = voiceBioEnabled,
                    onChange = { voiceBioEnabled = it }
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Seuil : ${"%.2f".format(voiceBioThreshold)} (plus haut = plus strict)",
                    style = MaterialTheme.typography.bodySmall
                )
                Slider(
                    value = voiceBioThreshold,
                    onValueChange = { voiceBioThreshold = it },
                    valueRange = 0.3f..0.9f,
                    steps = 11
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    ctx.startActivity(Intent(ctx, EnrollmentActivity::class.java))
                },
                enabled = voiceBioReady,
                modifier = Modifier.weight(1f)
            ) { Text(if (voiceBioEnrolled) "Ré-enrôler" else "Enrôler ma voix") }
            if (voiceBioEnrolled) {
                Button(
                    onClick = {
                        verifier.clearEnrollment()
                        voiceBioEnrolled = false
                        voiceBioEnabled = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF607D8B)),
                    modifier = Modifier.weight(1f)
                ) { Text("Effacer la voix") }
            }
        }

        // ------ OUTILS IA ------
        Spacer(Modifier.height(28.dp))
        Divider()
        Spacer(Modifier.height(20.dp))
        Text("Outils que Claude peut appeler", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Désactivés = pas envoyés à Claude, donc Claude ne peut pas y accéder. " +
                "Pratique si tu ne veux pas qu'il puisse lire SMS / notifications / etc.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        Settings.ALL_TOOL_NAMES.forEach { name ->
            ToggleRow(
                label = Settings.TOOL_LABELS[name] ?: name,
                description = null,
                checked = toolEnabled[name] ?: true,
                onChange = { toolEnabled[name] = it }
            )
        }

        // ------ ENREGISTRER ------
        Spacer(Modifier.height(28.dp))
        Divider()
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                settings.backendChoice = backend
                settings.claudeModel = model
                settings.anthropicApiKey = apiKey
                settings.confirmSensitiveActions = confirmSensitive
                Settings.ALL_TOOL_NAMES.forEach { name ->
                    settings.setToolEnabled(name, toolEnabled[name] ?: true)
                }
                settings.smsAllowlist = smsAllowlistText
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
                // Voice biometric: ne s'active que si on est enrôlé.
                settings.voiceBiometricEnabled = voiceBioEnabled && voiceBioEnrolled
                settings.voiceBiometricThreshold = voiceBioThreshold
                onClose()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Enregistrer") }

        // ------ ZONE DANGER ------
        Spacer(Modifier.height(36.dp))
        Divider(color = Color(0xFFB71C1C))
        Spacer(Modifier.height(16.dp))
        Text(
            "Zone de danger",
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFFB71C1C)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Efface clé API, réglages, quota, historique. Irréversible. Les modèles " +
                "Vosk et Gemma ne sont pas effacés (ce sont des assets, pas des données perso).",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                AlertDialog.Builder(ctx)
                    .setTitle("Effacer toutes les données ?")
                    .setMessage("Clé API Anthropic, réglages, historique, quota — tout sera perdu. Continuer ?")
                    .setPositiveButton("Effacer") { _, _ ->
                        settings.wipeAll()
                        AssistantService.stop(ctx)
                        onClose()
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
            modifier = Modifier.fillMaxWidth()
        ) { Text("Tout effacer") }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun BackendOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun ModelOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}
