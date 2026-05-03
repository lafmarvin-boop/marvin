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
    private val keyword: String = DEFAULT_KEYWORD
) {

    private var audioRecord: AudioRecord? = null
    private var loopJob: Job? = null
    private val paused = AtomicBoolean(false)
    private var onDetectedCallback: (() -> Unit)? = null

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(onDetected: () -> Unit) {
        if (loopJob != null) return
        onDetectedCallback = onDetected
        startRecorderAndLoop(onDetected)
    }

    @SuppressLint("MissingPermission")
    private fun startRecorderAndLoop(onDetected: () -> Unit) {
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
            // Grammar restricts decoding to the keyword – cheaper than full
            // language model decoding, and any other speech maps to "[unk]".
            val grammar = """["${keyword.lowercase()}", "[unk]"]"""
            val recognizer = Recognizer(voskModel.get(), sampleRate.toFloat(), grammar)
            val buffer = ShortArray(frameSize)
            try {
                while (isActive) {
                    if (paused.get()) { delay(50); continue }
                    val read = recorder.read(buffer, 0, frameSize)
                    if (read <= 0) continue
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
                    if (text.isNotEmpty() && text.contains(keyword, ignoreCase = true)) {
                        Log.i(TAG, "Wake word detected: \"$text\"")
                        recognizer.reset()
                        onDetected()
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
        const val DEFAULT_KEYWORD = "yo poto"
    }
}
