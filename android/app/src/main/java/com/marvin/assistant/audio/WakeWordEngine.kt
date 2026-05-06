package com.marvin.assistant.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.vosk.Recognizer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Always-on wake word detector using Vosk's keyword-spotting mode.
 *
 * Pourquoi Vosk plutôt qu'un moteur dédié type Porcupine:
 *  - 100 % open-source, aucune inscription / clé API.
 *  - Le modèle Vosk est déjà chargé pour la transcription (cf. [VoskModelHolder]),
 *    donc zéro dépendance / asset supplémentaire.
 *
 * Compromis:
 *  - Un peu plus gourmand en CPU que Porcupine (~5-10 % d'un cœur en continu),
 *    négligeable sur un Snapdragon 8 Gen 3.
 *
 * Le micro est libéré complètement quand on appelle [pause] – nécessaire parce
 * que [SpeechToText] ouvre son propre AudioRecord sur la même source
 * (VOICE_RECOGNITION) et Android ne partage pas la source entre deux clients.
 */
class WakeWordEngine(
    private val context: Context,
    private val voskModel: VoskModelHolder,
    private val keywords: List<String> = DEFAULT_KEYWORDS,
    private val speakerVerifier: SpeakerVerifier = NoOpSpeakerVerifier(),
    /** Lambda lue à chaque détection — permet de toggle live depuis Settings. */
    private val voiceBiometricEnabled: () -> Boolean = { false },
    private val voiceBiometricThreshold: () -> Float = { 0.5f }
) {

    private var audioRecord: AudioRecord? = null
    private var loopJob: Job? = null
    private val paused = AtomicBoolean(false)
    private var onDetectedCallback: ((String) -> Unit)? = null
    private val audioBuffer = RollingAudioBuffer(durationSec = 2, sampleRate = SAMPLE_RATE)

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(onDetected: (transcript: String) -> Unit) {
        if (loopJob != null) return
        onDetectedCallback = onDetected
        startRecorderAndLoop(onDetected)
    }

    @SuppressLint("MissingPermission")
    private fun startRecorderAndLoop(onDetected: (String) -> Unit) {
        val sampleRate = SAMPLE_RATE
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // ~200 ms frames keeps Vosk fed without too much overhead.
        val frameSize = sampleRate / 5
        val bufferSize = maxOf(minBuffer, frameSize * 2 * 2)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
        audioRecord = recorder
        recorder.startRecording()

        loopJob = CoroutineScope(Dispatchers.Default).launch {
            // Recognizer FULL VOCAB (sans grammaire). On veut détecter
            // "jarvis" et capturer la commande qui suit dans le MEME flux —
            // switcher de recognizer en plein milieu produisait du garbage
            // (Vosk ne se cale pas correctement sur un mid-utterance frais).
            // Le full Vosk model est largement assez précis et le S24 Ultra
            // encaisse le CPU sans broncher.
            val recognizer = Recognizer(voskModel.get(), sampleRate.toFloat())
            val buffer = ShortArray(frameSize)
            // État : on est soit en mode "spotting" (on cherche jarvis),
            // soit en mode "capturing" (on accumule la commande après jarvis).
            var capturing = false
            var captureDeadline = 0L
            var captureLastChange = 0L
            val captureBuf = StringBuilder()
            var capturePartial = ""
            try {
                while (isActive) {
                    if (paused.get()) { delay(50); continue }
                    val read = recorder.read(buffer, 0, frameSize)
                    if (read <= 0) continue
                    audioBuffer.write(buffer, read)
                    val finalized = try {
                        recognizer.acceptWaveForm(buffer, read)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Vosk acceptWaveForm failed", t); false
                    }
                    val finalizedText = if (finalized) JSONObject(recognizer.result).optString("text") else ""
                    val partialText = if (!finalized) JSONObject(recognizer.partialResult).optString("partial") else ""

                    if (!capturing) {
                        // Mode spotting : surveille les partials/finalisations
                        // pour détecter "jarvis" ou un alias.
                        val text = if (finalized) finalizedText else partialText
                        val matched = text.isNotEmpty() &&
                            keywords.any { text.contains(it, ignoreCase = true) }
                        if (matched) {
                            Log.i(TAG, "Wake word detected: \"$text\"")

                            // Voice biometric : snapshot de l'audio jusqu'à
                            // maintenant pour vérifier le locuteur.
                            if (voiceBiometricEnabled() && speakerVerifier.isReady() && speakerVerifier.isEnrolled()) {
                                val snapshot = audioBuffer.snapshot()
                                val similarity = speakerVerifier.verify(snapshot, sampleRate)
                                val threshold = voiceBiometricThreshold()
                                if (similarity < threshold) {
                                    Log.i(TAG, "Wake word REJECTED — speaker mismatch ($similarity < $threshold)")
                                    recognizer.reset()
                                    continue
                                }
                                Log.i(TAG, "Wake word accepted — speaker match ($similarity >= $threshold)")
                            }

                            // Bascule en mode capture. On garde le SAME
                            // recognizer qui est déjà calé sur l'audio en
                            // cours, donc on ne perd pas la suite de la phrase.
                            capturing = true
                            captureDeadline = System.currentTimeMillis() + POST_WAKE_LISTEN_MS
                            captureLastChange = System.currentTimeMillis()
                            captureBuf.clear()
                            capturePartial = ""
                            // Si on était sur un finalized contenant "jarvis ...",
                            // on récupère ce qui est déjà transcrit.
                            if (finalized && finalizedText.isNotEmpty()) {
                                captureBuf.append(finalizedText)
                            } else if (partialText.isNotEmpty()) {
                                capturePartial = partialText
                            }
                        } else if (finalized) {
                            // Pas de wake mot dans le finalized. Reset pour
                            // ne pas accumuler des phrases sans intérêt.
                            recognizer.reset()
                        }
                    } else {
                        // Mode capturing : accumule jusqu'à silence prolongé
                        // ou deadline.
                        if (finalized) {
                            if (finalizedText.isNotEmpty()) {
                                if (captureBuf.isNotEmpty()) captureBuf.append(' ')
                                captureBuf.append(finalizedText)
                                captureLastChange = System.currentTimeMillis()
                            }
                            capturePartial = ""
                        } else {
                            if (partialText.isNotEmpty() && partialText != capturePartial) {
                                capturePartial = partialText
                                captureLastChange = System.currentTimeMillis()
                            }
                        }
                        val now = System.currentTimeMillis()
                        val silentLong = (captureBuf.isNotEmpty() || capturePartial.isNotEmpty()) &&
                            (now - captureLastChange > POST_WAKE_SILENCE_MS)
                        if (silentLong || now >= captureDeadline) {
                            // Force la finalisation de ce qui reste dans le buffer.
                            val forced = JSONObject(recognizer.finalResult).optString("text")
                            if (forced.isNotEmpty()) {
                                if (captureBuf.isNotEmpty()) captureBuf.append(' ')
                                captureBuf.append(forced)
                            }
                            val full = captureBuf.toString().trim().replace(Regex("\\s+"), " ")
                            Log.i(TAG, "Wake word + post-wake: \"$full\"")
                            recognizer.reset()
                            capturing = false
                            onDetected(full)
                        }
                    }
                }
            } finally {
                recognizer.close()
            }
        }
    }

    /** Stops the loop and releases the AudioRecord so STT can grab the mic. */
    fun pause() {
        paused.set(true)
        loopJob?.cancel()
        loopJob = null
        audioRecord?.runCatching { stop(); release() }
        audioRecord = null
    }

    /** Re-acquires the AudioRecord and resumes wake-word detection. */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun resume() {
        if (loopJob != null) return
        paused.set(false)
        val cb = onDetectedCallback ?: return
        startRecorderAndLoop(cb)
    }

    fun release() {
        runBlocking { loopJob?.cancelAndJoin() }
        loopJob = null
        audioRecord?.runCatching { stop(); release() }
        audioRecord = null
        onDetectedCallback = null
    }

    companion object {
        private const val TAG = "WakeWord"
        private const val SAMPLE_RATE = 16_000
        // Combien de temps max on garde le micro après "jarvis" pour
        // attraper une commande enchaînée. 3000 ms couvre une phrase courte
        // type "jarvis donne moi la météo de demain à Paris".
        private const val POST_WAKE_LISTEN_MS = 3000L
        // Silence prolongé après lequel on considère que l'utilisateur a fini
        // de parler. 1000 ms tolère les petites pauses naturelles entre les
        // mots tout en évitant de laisser le micro ouvert inutilement.
        private const val POST_WAKE_SILENCE_MS = 1000L
        // "Jarvis" n'est pas un mot français; on liste les orthographes
        // probables que le modèle Vosk small FR pourrait produire.
        // "bonjour" est inclus pour réveiller Jarvis quand il est en mode dodo
        // ("bonjour Jarvis" → wake-up).
        val DEFAULT_KEYWORDS = listOf(
            "jarvis", "djarvis", "djarviss", "djarvisse", "jarvisse",
            "bonjour"
        )
    }
}
