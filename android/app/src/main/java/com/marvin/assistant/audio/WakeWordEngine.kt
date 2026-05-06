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
            // "jarvis" est un nom anglais; on liste plusieurs orthographes
            // probables pour que le modèle FR de Vosk en accroche au moins une.
            // Le terminal "[unk]" route le reste vers l'unknown-word model.
            val grammar = (keywords.map { "\"${it.lowercase()}\"" } + "\"[unk]\"")
                .joinToString(prefix = "[", postfix = "]")
            val recognizer = Recognizer(voskModel.get(), sampleRate.toFloat(), grammar)
            val buffer = ShortArray(frameSize)
            try {
                while (isActive) {
                    if (paused.get()) { delay(50); continue }
                    val read = recorder.read(buffer, 0, frameSize)
                    if (read <= 0) continue
                    // Alimente le buffer audio circulaire pour la vérif d'identité vocale.
                    audioBuffer.write(buffer, read)
                    val finalized = try {
                        recognizer.acceptWaveForm(buffer, read)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Vosk acceptWaveForm failed", t); false
                    }
                    val text = if (finalized) {
                        JSONObject(recognizer.result).optString("text")
                    } else {
                        JSONObject(recognizer.partialResult).optString("partial")
                    }
                    if (text.isNotEmpty() && keywords.any { text.contains(it, ignoreCase = true) }) {
                        Log.i(TAG, "Wake word detected: \"$text\"")

                        // Voice biometric: si activé + enrôlé, vérifier que c'est bien le locuteur.
                        // Snapshot AVANT d'étendre l'écoute pour rester sur l'audio du wake word.
                        if (voiceBiometricEnabled() && speakerVerifier.isReady() && speakerVerifier.isEnrolled()) {
                            val snapshot = audioBuffer.snapshot()
                            val similarity = speakerVerifier.verify(snapshot, sampleRate)
                            val threshold = voiceBiometricThreshold()
                            if (similarity < threshold) {
                                Log.i(TAG, "Wake word REJECTED — speaker mismatch ($similarity < $threshold)")
                                recognizer.reset()
                                continue // silencieux: pas de TTS pour pas alerter un intrus
                            }
                            Log.i(TAG, "Wake word accepted — speaker match ($similarity >= $threshold)")
                        }

                        recognizer.reset()

                        // Bascule sur un recognizer SANS grammaire (vocabulaire
                        // complet du modèle) pour transcrire la commande qui
                        // suit "jarvis". Le recognizer à grammaire ne peut
                        // produire que les mots-clés + [unk] et ne sait pas
                        // transcrire des phrases libres.
                        val freeRecognizer = Recognizer(voskModel.get(), sampleRate.toFloat())
                        var commandText = ""
                        try {
                            val deadline = System.currentTimeMillis() + POST_WAKE_LISTEN_MS
                            var lastChange = System.currentTimeMillis()
                            val accumulated = StringBuilder()
                            var currentPartial = ""
                            while (isActive && System.currentTimeMillis() < deadline) {
                                val readMore = recorder.read(buffer, 0, frameSize)
                                if (readMore <= 0) continue
                                audioBuffer.write(buffer, readMore)
                                val moreFinalized = try {
                                    freeRecognizer.acceptWaveForm(buffer, readMore)
                                } catch (t: Throwable) {
                                    Log.e(TAG, "Vosk acceptWaveForm failed (post-wake)", t); false
                                }
                                if (moreFinalized) {
                                    // Vosk a détecté un break (micro-silence). On
                                    // accumule mais on ne sort PAS — l'utilisateur
                                    // peut continuer à parler.
                                    val res = JSONObject(freeRecognizer.result).optString("text")
                                    if (res.isNotEmpty()) {
                                        if (accumulated.isNotEmpty()) accumulated.append(' ')
                                        accumulated.append(res)
                                        lastChange = System.currentTimeMillis()
                                    }
                                    currentPartial = ""
                                } else {
                                    val partial = JSONObject(freeRecognizer.partialResult).optString("partial")
                                    if (partial.isNotEmpty() && partial != currentPartial) {
                                        currentPartial = partial
                                        lastChange = System.currentTimeMillis()
                                    }
                                }
                                // Sortie sur silence prolongé : si rien n'a bougé
                                // depuis 1 s ET qu'on a déjà du contenu, l'utilisateur
                                // a fini de parler.
                                if (accumulated.isNotEmpty() || currentPartial.isNotEmpty()) {
                                    if (System.currentTimeMillis() - lastChange > POST_WAKE_SILENCE_MS) break
                                }
                            }
                            // Force la finalisation pour récupérer ce qui restait dans le buffer.
                            val forced = JSONObject(freeRecognizer.finalResult).optString("text")
                            if (forced.isNotEmpty()) {
                                if (accumulated.isNotEmpty()) accumulated.append(' ')
                                accumulated.append(forced)
                            }
                            commandText = accumulated.toString().trim().replace(Regex("\\s+"), " ")
                        } finally {
                            freeRecognizer.close()
                        }
                        // Concatène : "jarvis" + " " + commande
                        val fullText = if (commandText.isNotEmpty()) "$text $commandText" else text
                        Log.i(TAG, "Wake word + post-wake: \"$fullText\"")

                        onDetected(fullText)
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
