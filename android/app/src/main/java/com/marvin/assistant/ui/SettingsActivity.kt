package com.marvin.assistant.ui

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
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
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
    var backend by remember { mutableStateOf(settings.backendChoice) }
    var model by remember { mutableStateOf(settings.claudeModel) }
    var apiKey by remember { mutableStateOf(settings.anthropicApiKey) }
    var revealKey by remember { mutableStateOf(false) }
    val quotaUsed = remember { settings.quotaUsedToday() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
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
                    "dans /sdcard/Android/data/com.marvin.assistant/files/. " +
                    "Cf. README pour les commandes adb exactes.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(Modifier.height(28.dp))
        Divider()
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                settings.backendChoice = backend
                settings.claudeModel = model
                settings.anthropicApiKey = apiKey
                onClose()
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Enregistrer") }
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
