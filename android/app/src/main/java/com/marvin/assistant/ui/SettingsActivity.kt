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
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
    var webSearchEnabled by remember { mutableStateOf(settings.webSearchEnabled) }
    var proactiveNotifs by remember { mutableStateOf(settings.proactiveNotificationsEnabled) }
    var proactiveCal by remember { mutableStateOf(settings.proactiveCalendarAnnouncementsEnabled) }
    var wakeWord by remember { mutableStateOf(settings.wakeWord) }
    var customWakeVariants by remember { mutableStateOf(settings.customWakeWordVariants) }
    var localOnly by remember { mutableStateOf(settings.localOnlyMode) }
    val auditLog = remember { com.marvin.assistant.audit.AuditLog(ctx) }
    var auditEntries by remember { mutableStateOf(auditLog.all().take(20)) }
    var showAudit by remember { mutableStateOf(false) }
    var backupPwd by remember { mutableStateOf("") }
    var backupStatus by remember { mutableStateOf("") }
    var haUrl by remember { mutableStateOf(settings.homeAssistantUrl) }
    var haToken by remember { mutableStateOf(settings.homeAssistantToken) }
    var elevenKey by remember { mutableStateOf(settings.elevenLabsApiKey) }
    var elevenVoice by remember { mutableStateOf(settings.elevenLabsVoiceId) }
    var auddKey by remember { mutableStateOf(settings.auddApiKey) }
    var accent by remember { mutableStateOf(settings.accentColor) }
    var customPrompt by remember { mutableStateOf(settings.customSystemPrompt) }
    var ttsBackend by remember { mutableStateOf(settings.ttsBackend) }
    var certPinning by remember { mutableStateOf(settings.certPinningEnabled) }

    // Corrections STT : on lit a` chaque recomposition pour refléter les
    // ajouts faits via voix. Une carte par entrée + bouton supprimer.
    val sttCorrections = remember { com.marvin.assistant.audio.SttCorrections(ctx) }
    var corrections by remember { mutableStateOf(sttCorrections.all().toList()) }
    var newCorrectionHeard by remember { mutableStateOf("") }
    var newCorrectionMeant by remember { mutableStateOf("") }

    // Routines
    val routinesMgr = remember { com.marvin.assistant.routines.RoutinesManager(ctx) }
    var routines by remember { mutableStateOf(routinesMgr.all()) }

    // Rappels actifs
    val remindersMgr = remember { com.marvin.assistant.reminders.RemindersManager(ctx) }
    var reminders by remember { mutableStateOf(remindersMgr.all()) }
    var newRoutineName by remember { mutableStateOf("") }
    var newRoutineSteps by remember { mutableStateOf("") }
    var editingRoutineName by remember { mutableStateOf<String?>(null) }
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

        Spacer(Modifier.height(12.dp))
        ToggleRow(
            label = "Recherche web",
            description = "Permet à Jarvis de chercher sur internet pour les questions " +
                "factuelles d'actualité (météo précise, news, prix, etc.). Coût ~1 ¢ " +
                "par recherche, max 3 par requête.",
            checked = webSearchEnabled,
            onChange = { webSearchEnabled = it }
        )

        ToggleRow(
            label = "Notifications proactives",
            description = "Jarvis lit automatiquement à voix haute les SMS, messages " +
                "WhatsApp et appels manqués qui arrivent. Utile en mode voiture / " +
                "mains occupées. Nécessite que l'accès aux notifications soit accordé.",
            checked = proactiveNotifs,
            onChange = { proactiveNotifs = it }
        )

        ToggleRow(
            label = "Certificate pinning Anthropic",
            description = "Rejette toute connexion à api.anthropic.com qui n'utilise " +
                "pas le certificat attendu (anti-MITM). Nécessite que CERT_PINS soit " +
                "rempli dans ClaudeBackend.kt — sinon l'option n'a aucun effet. " +
                "Lance tools/extract-anthropic-pins.sh pour récupérer les pins.",
            checked = certPinning,
            onChange = { certPinning = it }
        )

        ToggleRow(
            label = "Mode 100 % local strict",
            description = "Desactive completement Claude et la recherche web. " +
                "Force Gemma local. Utile a l'etranger sans data ou pour " +
                "confidentialite maximale. Aucune donnee ne quitte l'appareil.",
            checked = localOnly,
            onChange = { localOnly = it }
        )

        ToggleRow(
            label = "Annonces calendrier",
            description = "Jarvis annonce vocalement chaque événement de ton " +
                "calendrier 5 minutes avant qu'il commence. Nécessite la " +
                "permission READ_CALENDAR.",
            checked = proactiveCal,
            onChange = { proactiveCal = it }
        )

        // ------ VOIX (TTS) ------
        Spacer(Modifier.height(20.dp))
        Text("Voix de Jarvis", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Choisis le moteur de synthèse vocale. ElevenLabs nécessite une " +
                "clé API (~5 €/mois pour 30 000 caractères).",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        com.marvin.assistant.util.TtsBackend.entries.forEach { mode ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = ttsBackend == mode, onClick = { ttsBackend = mode })
                val label = when (mode) {
                    com.marvin.assistant.util.TtsBackend.AUTO -> "Auto (ElevenLabs si dispo, sinon Piper)"
                    com.marvin.assistant.util.TtsBackend.ELEVENLABS -> "ElevenLabs (cloud, premium)"
                    com.marvin.assistant.util.TtsBackend.PIPER -> "Piper (local, gratuit)"
                    com.marvin.assistant.util.TtsBackend.ANDROID -> "Android (qualité moindre)"
                }
                Text(label, modifier = Modifier.padding(start = 6.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = elevenKey,
            onValueChange = { elevenKey = it },
            label = { Text("Clé API ElevenLabs") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = elevenVoice,
            onValueChange = { elevenVoice = it },
            label = { Text("Voice ID ElevenLabs (vide = Adam)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = auddKey,
            onValueChange = { auddKey = it },
            label = { Text("AudD API Token (reconnaissance musicale)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // ------ SYSTEM PROMPT CUSTOM ------
        Spacer(Modifier.height(20.dp))
        Text("Personnalité de Jarvis (system prompt)",
            style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Laisse vide pour le prompt par défaut (majordome posé, français). " +
                "Sinon, écris la personnalité que tu veux. Ex: « Tu es Jarvis, " +
                "drôle et sarcastique, parle en français avec un ton décontracté. »",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = customPrompt,
            onValueChange = { customPrompt = it },
            label = { Text("Prompt custom (vide = défaut)") },
            singleLine = false,
            minLines = 3,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth()
        )

        // ------ COULEUR ACCENT ------
        Spacer(Modifier.height(20.dp))
        Text("Couleur d'accent (réacteur)", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Settings.ACCENT_PRESETS.keys.forEach { name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = accent == name, onClick = { accent = name })
                Text(
                    name.replaceFirstChar { it.titlecase(java.util.Locale.FRENCH) },
                    modifier = Modifier.padding(start = 6.dp),
                    color = Color(Settings.ACCENT_PRESETS[name]!!),
                    fontWeight = if (accent == name) FontWeight.Bold else FontWeight.Normal
                )
            }
        }

        // ------ WAKE WORD ------
        Spacer(Modifier.height(20.dp))
        Text("Mot d'activation",
            style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Le mot que tu prononces pour activer l'assistant. Persona reste " +
                "« Jarvis » dans les réponses, seul le déclencheur change. " +
                "Redémarrer le service pour appliquer.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        Settings.WAKE_WORD_PRESETS.keys.forEach { word ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = wakeWord == word,
                    onClick = { wakeWord = word }
                )
                Text(
                    word.replaceFirstChar { it.titlecase(java.util.Locale.FRENCH) },
                    fontWeight = if (wakeWord == word) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(start = 6.dp)
                )
            }
        }
        // Custom
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = wakeWord == "custom", onClick = { wakeWord = "custom" })
            Text("Custom (mot à toi)",
                fontWeight = if (wakeWord == "custom") FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.padding(start = 6.dp))
        }
        if (wakeWord == "custom") {
            OutlinedTextField(
                value = customWakeVariants,
                onValueChange = { customWakeVariants = it },
                label = { Text("Variantes séparées par virgules (ex: bingo, bin go, bingot)") },
                singleLine = false,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Astuce : prononce le mot 3-4 fois après activation, " +
                    "regarde le log 'Vosk final:' pour voir ce que Vosk transcrit, " +
                    "et ajoute ces variantes ici.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF607D8B)
            )
        }

        // ------ SMART HOME (HOME ASSISTANT) ------
        Spacer(Modifier.height(20.dp))
        Text("Smart home (Home Assistant)",
            style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Pilote tes lampes / prises / scènes depuis Jarvis. " +
                "Crée un Long-Lived Access Token dans Home Assistant " +
                "(Profil → Tokens), puis colle l'URL et le token ici.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = haUrl,
            onValueChange = { haUrl = it },
            label = { Text("URL Home Assistant (ex. http://homeassistant.local:8123)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = haToken,
            onValueChange = { haToken = it },
            label = { Text("Long-Lived Access Token") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        // ------ APPRENTISSAGE / DICTIONNAIRE PERSO ------
        Spacer(Modifier.height(28.dp))
        Text("Mes corrections de prononciation",
            style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Ajoute manuellement ou via la voix : « Jarvis quand je dis l'air " +
                "comprends l'heure ».",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = newCorrectionHeard,
                onValueChange = { newCorrectionHeard = it },
                label = { Text("Forme entendue") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(
                value = newCorrectionMeant,
                onValueChange = { newCorrectionMeant = it },
                label = { Text("Correction") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            Button(onClick = {
                if (newCorrectionHeard.isNotBlank() && newCorrectionMeant.isNotBlank()) {
                    sttCorrections.add(newCorrectionHeard, newCorrectionMeant)
                    newCorrectionHeard = ""
                    newCorrectionMeant = ""
                    corrections = sttCorrections.all().toList()
                }
            }) { Text("+") }
        }
        Spacer(Modifier.height(8.dp))
        if (corrections.isEmpty()) {
            Text("Aucune correction.", style = MaterialTheme.typography.bodySmall)
        } else {
            corrections.forEach { (heard, meant) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) {
                    Text(
                        "« $heard » → « $meant »",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = {
                            sttCorrections.remove(heard)
                            corrections = sttCorrections.all().toList()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C))
                    ) { Text("X") }
                }
            }
        }

        // ------ ROUTINES ------
        Spacer(Modifier.height(20.dp))
        Text("Mes routines",
            style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Une routine = un enchaînement de commandes. Lance via " +
                "« Jarvis routine matin ». Edition manuelle des étapes : à venir.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        if (routines.isEmpty()) {
            Text("Aucune routine.", style = MaterialTheme.typography.bodySmall)
        } else {
            routines.forEach { r ->
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            r.name,
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Button(
                            onClick = {
                                routinesMgr.remove(r.name)
                                routines = routinesMgr.all()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C))
                        ) { Text("X") }
                    }
                    r.steps.forEach { step ->
                        Text(
                            "  • $step",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF607D8B)
                        )
                    }
                }
            }
        }
        Button(
            onClick = {
                routinesMgr.resetToDefaults()
                routines = routinesMgr.all()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Restaurer les routines par défaut") }

        // Édition / ajout de routine
        Spacer(Modifier.height(8.dp))
        Text("Ajouter / modifier une routine", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = newRoutineName,
            onValueChange = { newRoutineName = it },
            label = { Text("Nom de la routine (ex. matin)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = newRoutineSteps,
            onValueChange = { newRoutineSteps = it },
            label = { Text("Étapes séparées par | (ex. donne moi l'heure|météo|news)") },
            singleLine = false,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                val steps = newRoutineSteps.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                if (newRoutineName.isNotBlank() && steps.isNotEmpty()) {
                    routinesMgr.put(newRoutineName.trim(), steps)
                    routines = routinesMgr.all()
                    newRoutineName = ""
                    newRoutineSteps = ""
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Enregistrer cette routine") }

        // ------ RAPPELS ------
        Spacer(Modifier.height(20.dp))
        Text("Mes rappels actifs",
            style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        if (reminders.isEmpty()) {
            Text("Aucun rappel programmé.", style = MaterialTheme.typography.bodySmall)
        } else {
            reminders.forEach { r ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                ) {
                    Text(
                        r.describe(),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Button(
                        onClick = {
                            remindersMgr.remove(r.id)
                            reminders = remindersMgr.all()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C))
                    ) { Text("X") }
                }
            }
        }

        // ------ BACKUP / RESTORE ------
        Spacer(Modifier.height(20.dp))
        Text("Sauvegarde / Restauration",
            style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Exporte tes settings, routines, corrections, faits, rappels, " +
                "clés API dans un fichier chiffré par mot de passe. " +
                "Pratique pour réinstaller l'app ou changer de téléphone.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = backupPwd,
            onValueChange = { backupPwd = it },
            label = { Text("Mot de passe (à mémoriser !)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Row {
            Button(
                onClick = {
                    if (backupPwd.length < 6) {
                        backupStatus = "Mot de passe trop court (min 6)."
                        return@Button
                    }
                    try {
                        val mgr = com.marvin.assistant.backup.BackupManager(ctx)
                        val file = mgr.defaultExportFile()
                        file.writeBytes(mgr.export(backupPwd))
                        backupStatus = "Exporté : ${file.absolutePath}"
                    } catch (t: Throwable) {
                        backupStatus = "Erreur export : ${t.message}"
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("Exporter") }
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = {
                    if (backupPwd.length < 6) {
                        backupStatus = "Mot de passe requis pour décoder."
                        return@Button
                    }
                    try {
                        // Cherche le dernier .mvb dans le dossier
                        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
                        val latest = dir.listFiles { f ->
                            f.name.endsWith(".mvb")
                        }?.maxByOrNull { it.lastModified() }
                        if (latest == null) {
                            backupStatus = "Aucun backup trouvé."
                            return@Button
                        }
                        val mgr = com.marvin.assistant.backup.BackupManager(ctx)
                        val ok = mgr.import(latest.readBytes(), backupPwd)
                        backupStatus = if (ok) "Restauré : ${latest.name}"
                            else "Échec : mot de passe incorrect ?"
                    } catch (t: Throwable) {
                        backupStatus = "Erreur import : ${t.message}"
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text("Importer (dernier)") }
        }
        if (backupStatus.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(backupStatus, style = MaterialTheme.typography.bodySmall)
        }

        // ------ DIAGNOSTIC DUMP ------
        Spacer(Modifier.height(16.dp))
        // Verifier les updates GitHub
        var updateStatus by remember { mutableStateOf("") }
        val scope = rememberCoroutineScope()
        Button(
            onClick = {
                scope.launch {
                    val info = com.marvin.assistant.update.UpdateChecker(ctx).check()
                    updateStatus = if (info.hasUpdate) {
                        "Mise à jour dispo (commit ${info.latestSha}) : ${info.message}"
                    } else {
                        "À jour. Dernier commit : ${info.latestSha} ${info.message}"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Vérifier les mises à jour") }
        if (updateStatus.isNotBlank()) {
            Text(updateStatus, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp))
        }

        Button(
            onClick = {
                try {
                    val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
                    val file = java.io.File(dir, "marvin-diag-${System.currentTimeMillis()}.txt")
                    val s = settings
                    val sb = StringBuilder()
                    sb.append("=== Marvin diagnostic ===\n")
                    sb.append("Date: ${java.util.Date()}\n")
                    sb.append("Backend: ${s.backendChoice}\n")
                    sb.append("Claude model: ${s.claudeModel}\n")
                    sb.append("Local only: ${s.localOnlyMode}\n")
                    sb.append("Web search: ${s.webSearchEnabled}\n")
                    sb.append("Wake word: ${s.wakeWord}\n")
                    sb.append("TTS backend: ${s.ttsBackend}\n")
                    sb.append("Voice bio: ${s.voiceBiometricEnabled} threshold=${s.voiceBiometricThreshold}\n")
                    sb.append("Sleeping: ${s.isSleeping}\n")
                    sb.append("Quota used today: ${s.quotaUsedToday()}/${s.dailyLimit}\n")
                    sb.append("Has Claude key: ${s.anthropicApiKey.isNotBlank()}\n")
                    sb.append("Has ElevenLabs key: ${s.elevenLabsApiKey.isNotBlank()}\n")
                    sb.append("Has AudD key: ${s.auddApiKey.isNotBlank()}\n")
                    sb.append("Home Assistant configured: ${s.homeAssistantUrl.isNotBlank()}\n")
                    sb.append("HTTP server enabled: ${s.httpServerEnabled} port=${s.httpServerPort}\n")
                    sb.append("Cert pinning: ${s.certPinningEnabled}\n")
                    sb.append("Onboarding done: ${s.onboardingDone}\n")
                    sb.append("\n=== Audit log (50 derniers) ===\n")
                    auditLog.all().take(50).forEach { sb.append(it.describe()).append('\n') }
                    file.writeText(sb.toString())
                    backupStatus = "Diagnostic exporté : ${file.absolutePath}"
                } catch (t: Throwable) {
                    backupStatus = "Erreur diagnostic : ${t.message}"
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Exporter le diagnostic (debug)") }

        // ------ AUDIT LOG ------
        Spacer(Modifier.height(20.dp))
        Text("Historique d'activité (audit)",
            style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Trace de tes échanges avec Jarvis. Chiffré localement, max 500 " +
                "entrées. Utile pour debug ou pour vérifier ce que Jarvis a fait.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    showAudit = !showAudit
                    if (showAudit) auditEntries = auditLog.all().take(20)
                },
                modifier = Modifier.weight(1f)
            ) { Text(if (showAudit) "Cacher" else "Afficher (20 derniers)") }
            Spacer(Modifier.width(6.dp))
            Button(
                onClick = {
                    auditLog.clear()
                    auditEntries = emptyList()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
                modifier = Modifier.weight(1f)
            ) { Text("Effacer") }
        }
        if (showAudit) {
            Spacer(Modifier.height(8.dp))
            if (auditEntries.isEmpty()) {
                Text("(vide)", style = MaterialTheme.typography.bodySmall)
            } else {
                auditEntries.forEach { e ->
                    Text(
                        e.describe(),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF455A64),
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
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
                settings.webSearchEnabled = webSearchEnabled
                settings.proactiveNotificationsEnabled = proactiveNotifs
                val prevCal = settings.proactiveCalendarAnnouncementsEnabled
                settings.proactiveCalendarAnnouncementsEnabled = proactiveCal
                // Enclenche / arrête le watcher si on change le toggle
                if (proactiveCal && !prevCal) {
                    com.marvin.assistant.proactive.CalendarWatcher(ctx).enable()
                } else if (!proactiveCal && prevCal) {
                    com.marvin.assistant.proactive.CalendarWatcher(ctx).disable()
                }
                settings.homeAssistantUrl = haUrl.trim()
                settings.homeAssistantToken = haToken.trim()
                settings.localOnlyMode = localOnly
                settings.certPinningEnabled = certPinning
                settings.elevenLabsApiKey = elevenKey.trim()
                settings.elevenLabsVoiceId = elevenVoice.trim()
                settings.auddApiKey = auddKey.trim()
                settings.accentColor = accent
                settings.customSystemPrompt = customPrompt
                settings.ttsBackend = ttsBackend
                val wakeChanged = settings.wakeWord != wakeWord ||
                    settings.customWakeWordVariants != customWakeVariants
                settings.wakeWord = wakeWord
                settings.customWakeWordVariants = customWakeVariants
                if (wakeChanged) {
                    com.marvin.assistant.service.AssistantService.stop(ctx)
                    com.marvin.assistant.service.AssistantService.start(ctx)
                }
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
