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

        // Acoustic Echo Canceler : annule la voix de Jarvis qui sort du
        // haut-parleur dans le micro. Permet de parler PENDANT que Jarvis
        // parle (vraie barge-in) sans que le wake word se déclenche sur
        // sa propre voix. Pas dispo sur tous les téléphones — fail silently.
        try {
            if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                android.media.audiofx.AcousticEchoCanceler.create(recorder.audioSessionId)?.apply {
                    enabled = true
                    Log.i(TAG, "AcousticEchoCanceler ACTIF")
                }
            } else {
                Log.i(TAG, "AcousticEchoCanceler indisponible sur ce device")
            }
            // Noise suppressor en bonus (capte mieux dans un environnement bruité)
            if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                android.media.audiofx.NoiseSuppressor.create(recorder.audioSessionId)?.apply {
                    enabled = true
                    Log.i(TAG, "NoiseSuppressor ACTIF")
                }
            }
            // Auto Gain Control (normalise le volume entrant)
            if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
                android.media.audiofx.AutomaticGainControl.create(recorder.audioSessionId)?.apply {
                    enabled = true
                    Log.i(TAG, "AutomaticGainControl ACTIF")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Audio effects setup failed", t)
        }

        recorder.startRecording()

        loopJob = CoroutineScope(Dispatchers.Default).launch {
            // Recognizer FULL VOCAB (sans grammaire). Stratégie :
            //  - On laisse Vosk accumuler l'audio en continu.
            //  - Dès qu'on voit "jarvis" dans le partial, on note que
            //    la phrase courante doit être traitée comme un wake.
            //  - On attend la FINALISATION Vosk (déclenchée par silence
            //    naturel à la fin de la phrase) pour avoir le texte
            //    complet — ça capte "jarvis donne moi l'heure" en entier.
            //  - Filet de sécurité : si pas de finalize dans
            //    POST_WAKE_LISTEN_MS, on force.
            val recognizer = Recognizer(voskModel.get(), sampleRate.toFloat())
            val buffer = ShortArray(frameSize)
            var wakeArmed = false
            var wakeArmedAt = 0L
            var armedPartial = ""
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

                    if (finalized) {
                        val text = JSONObject(recognizer.result).optString("text")
                        // DIAGNOSTIC : on log TOUTE finalisation pour voir ce
                        // que Vosk produit quand l'utilisateur parle. Si
                        // "jarvis" arrive systématiquement comme un autre
                        // mot, il faut le rajouter aux variantes.
                        if (text.isNotEmpty()) Log.i(TAG, "Vosk final: \"$text\"")
                        val matched = text.isNotEmpty() && (
                            keywords.any { text.contains(it, ignoreCase = true) } ||
                                JARVIS_FUZZY.containsMatchIn(text)
                        )
                        if (matched || wakeArmed) {
                            // Finalise — on dispatche maintenant.
                            handleWake(text)
                            wakeArmed = false
                            armedPartial = ""
                        }
                        // Reset pour repartir sur un buffer vierge (sinon Vosk
                        // accumule indéfiniment et finalise rarement).
                        recognizer.reset()
                    } else {
                        val partial = JSONObject(recognizer.partialResult).optString("partial")
                        if (!wakeArmed && partial.isNotEmpty() && (
                                keywords.any { partial.contains(it, ignoreCase = true) } ||
                                    JARVIS_FUZZY.containsMatchIn(partial)
                            )
                        ) {
                            // On a vu jarvis dans le partial — on arme le
                            // wake. Le dispatch se fera sur finalisation.
                            wakeArmed = true
                            wakeArmedAt = System.currentTimeMillis()
                            armedPartial = partial
                            Log.i(TAG, "Wake word armed (partial=\"$partial\")")
                        } else if (wakeArmed && partial.isNotEmpty() && partial != armedPartial) {
                            // Le partial évolue → l'utilisateur enchaîne une
                            // commande. On garde le mode "wait full" (jusqu'à
                            // POST_WAKE_LISTEN_MS).
                            armedPartial = partial
                            wakeArmedAt = System.currentTimeMillis()
                        }
                        // Si le wake est armé et que le partial n'a pas évolué
                        // depuis FAST_FIRE_MS, l'utilisateur a dit juste
                        // "jarvis" tout seul. On fire immédiatement pour pas
                        // attendre la finalisation Vosk (~1 s de silence).
                        if (wakeArmed && System.currentTimeMillis() - wakeArmedAt > FAST_FIRE_MS) {
                            Log.i(TAG, "Wake fast-fire (partial stable=\"$armedPartial\")")
                            handleWake(armedPartial)
                            wakeArmed = false
                            armedPartial = ""
                            recognizer.reset()
                        } else if (wakeArmed && System.currentTimeMillis() - wakeArmedAt > POST_WAKE_LISTEN_MS) {
                            // Filet de sécurité : si l'utilisateur a parlé
                            // longtemps sans pause, on force la finalisation.
                            val forced = JSONObject(recognizer.finalResult).optString("text")
                            Log.i(TAG, "Wake forced-finalize after timeout")
                            handleWake(forced)
                            wakeArmed = false
                            armedPartial = ""
                            recognizer.reset()
                        }
                    }
                }
            } finally {
                recognizer.close()
            }
        }
    }

    private fun handleWake(text: String) {
        Log.i(TAG, "Wake word + phrase: \"$text\"")
        // Voice biometric (si activé + enrôlé) : vérifie que c'est bien
        // l'utilisateur enrôlé qui parle.
        if (voiceBiometricEnabled() && speakerVerifier.isReady() && speakerVerifier.isEnrolled()) {
            val snapshot = audioBuffer.snapshot()
            val similarity = speakerVerifier.verify(snapshot, SAMPLE_RATE)
            val threshold = voiceBiometricThreshold()
            if (similarity < threshold) {
                Log.i(TAG, "Wake word REJECTED — speaker mismatch ($similarity < $threshold)")
                return
            }
            Log.i(TAG, "Wake word accepted — speaker match ($similarity >= $threshold)")
        }
        onDetectedCallback?.invoke(text)
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
        // Si l'utilisateur dit juste "jarvis" tout seul (le partial reste
        // stable, pas d'enchaînement), on fire après ce délai sans attendre
        // la finalisation Vosk (qui prend ~1 s de silence). Beaucoup plus
        // réactif pour le cas "jarvis seul → bip → commande".
        private const val FAST_FIRE_MS = 500L
        // "Jarvis" n'est pas un mot français — Vosk le transcrit souvent
        // de façons très variées selon la prononciation. On accepte large
        // pour ne pas rater le wake word. Quitte à avoir quelques faux
        // positifs (rares en pratique), c'est mieux que de manquer.
        // "bonjour" sert de wake word pour sortir du mode dodo.
        val DEFAULT_KEYWORDS = listOf(
            "jarvis", "djarvis", "djarviss", "djarvisse", "jarvisse",
            "jarvi", "yarvis", "djarvi", "charvis", "tchavis", "charvi",
            "jarvice", "yves", "yvre", "tarvis",
            "bonjour"
        )

        /**
         * Filet large : tout mot contenant "arv" précédé d'une consonne
         * tipique de Jarvis (j/dj/ch/y/sh) attrape les transcriptions
         * fantaisistes type "djarvise", "chervi", "yarbis" etc.
         * À combiner avec la voice biometric pour éviter les faux positifs
         * (un cri "marv'!" vers Jarvis activerait sinon le wake).
         */
        private val JARVIS_FUZZY = Regex(
            """\b(j|dj|tch|ch|sh|y)\w{0,3}(arv|erv|arb|arf|abv)\w*\b""",
            RegexOption.IGNORE_CASE
        )
    }
}
