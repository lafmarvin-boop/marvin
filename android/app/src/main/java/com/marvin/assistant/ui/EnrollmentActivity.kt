package com.marvin.assistant.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.marvin.assistant.audio.SpeakerVerifier
import com.marvin.assistant.audio.SpeakerVerifierFactory
import com.marvin.assistant.service.AssistantService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Écran d'enrôlement vocal. Demande à l'utilisateur de dire "Jarvis"
 * 5 fois, extrait l'embedding de chaque sample, calcule la moyenne et
 * la persiste comme référence.
 *
 * IMPORTANT: arrête le AssistantService avant de capter le micro pour
 * ne pas entrer en conflit avec la WakeWordEngine.
 */
class EnrollmentActivity : ComponentActivity() {

    private lateinit var verifier: SpeakerVerifier

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Stoppe le service pour libérer le micro
        AssistantService.stop(this)
        verifier = SpeakerVerifierFactory.create(this)
        verifier.resetEnrollment()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EnrollmentScreen(
                        isReady = verifier.isReady(),
                        captureSample = ::captureSample,
                        finalize = {
                            verifier.finalizeEnrollment()
                        },
                        cancel = {
                            verifier.resetEnrollment()
                            finish()
                        },
                        onDone = { finish() }
                    )
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun captureSample(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return false

        return withContext(Dispatchers.IO) {
            val sampleRate = 16_000
            val durationMs = 2_000
            val totalSamples = sampleRate * durationMs / 1000
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, totalSamples * 2)
            )
            try {
                recorder.startRecording()
                val out = ShortArray(totalSamples)
                var read = 0
                while (read < totalSamples) {
                    val n = recorder.read(out, read, totalSamples - read)
                    if (n <= 0) break
                    read += n
                }
                recorder.stop()
                if (read >= totalSamples / 2) {
                    verifier.enrollSample(out, sampleRate)
                    true
                } else false
            } catch (t: Throwable) {
                false
            } finally {
                runCatching { recorder.release() }
            }
        }
    }

    override fun onDestroy() {
        verifier.release()
        super.onDestroy()
    }
}

@Composable
private fun EnrollmentScreen(
    isReady: Boolean,
    captureSample: suspend () -> Boolean,
    finalize: () -> Unit,
    cancel: () -> Unit,
    onDone: () -> Unit
) {
    var samplesRecorded by remember { mutableStateOf(0) }
    var recording by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val target = 5
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Enrôlement vocal", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        if (!isReady) {
            Text(
                "Modèle d'embedding vocal absent. Cf. README pour télécharger " +
                    "speaker.onnx et le pousser dans le dossier de l'app.",
                color = Color(0xFFB71C1C)
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onDone) { Text("Retour") }
        } else {
            Text(
                "Dis « Jarvis » à voix naturelle, $target fois.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Varie un peu : intonation normale, plus posée, plus rapide. " +
                    "Évite les bruits de fond.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(24.dp))

            Text("Échantillons : $samplesRecorded / $target", fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(16.dp))

            if (recording) {
                RecordingPulse()
                Spacer(Modifier.height(8.dp))
                Text("J'enregistre… Dis « Jarvis »", color = Color(0xFF1976D2))
            }
            if (error != null) {
                Text(error!!, color = Color(0xFFB71C1C))
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(24.dp))
            if (samplesRecorded < target) {
                Button(
                    onClick = {
                        scope.launch {
                            recording = true
                            error = null
                            // Petite pause pour laisser le user se préparer
                            delay(300)
                            val ok = captureSample()
                            recording = false
                            if (ok) {
                                samplesRecorded += 1
                            } else {
                                error = "Pas assez de son détecté. Approche-toi du micro."
                            }
                        }
                    },
                    enabled = !recording,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (samplesRecorded == 0) "Enregistrer le 1er échantillon" else "Enregistrer le suivant") }
            } else {
                Button(
                    onClick = {
                        finalize()
                        onDone()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF388E3C)),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Valider l'enrôlement") }
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = cancel,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF607D8B)),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Annuler") }
        }
    }
}

@Composable
private fun RecordingPulse() {
    val infinite = rememberInfiniteTransition(label = "pulse")
    val alpha by infinite.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "alpha"
    )
    Text(
        text = "●",
        color = Color(0xFFD32F2F).copy(alpha = alpha),
        fontSize = 32.sp
    )
}
