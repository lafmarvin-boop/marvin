package com.marvin.assistant.audio

import ai.picovoice.porcupine.Porcupine
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import com.marvin.assistant.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Always-on wake word detector backed by Picovoice Porcupine.
 *
 * Le micro est libéré complètement quand on appelle [pause] – c'est nécessaire
 * parce que [SpeechToText] ouvre son propre AudioRecord sur la même source
 * (VOICE_RECOGNITION), et Android ne partage pas la source entre deux clients.
 *
 * Setup (cf. README):
 *  1. Crée un compte gratuit sur https://console.picovoice.ai/.
 *  2. Entraîne un wake word "yo poto" pour Android (arm64), télécharge le .ppn.
 *  3. Pose-le dans app/src/main/assets/wakeword/yo_poto_android.ppn.
 *  4. Renseigne PICOVOICE_ACCESS_KEY=... dans local.properties.
 */
class WakeWordEngine(private val context: Context) {

    private var porcupine: Porcupine? = null
    private var audioRecord: AudioRecord? = null
    private var loopJob: Job? = null
    private val paused = AtomicBoolean(false)
    private var onDetectedCallback: (() -> Unit)? = null

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(onDetected: () -> Unit) {
        if (loopJob != null) return // already running
        onDetectedCallback = onDetected

        val keywordPath = ensureKeywordFile()
        val accessKey = BuildConfig.PICOVOICE_ACCESS_KEY
        if (accessKey.isBlank()) {
            Log.e(TAG, "Missing Picovoice access key (BuildConfig.PICOVOICE_ACCESS_KEY).")
            return
        }

        val engine = Porcupine.Builder()
            .setAccessKey(accessKey)
            .setKeywordPath(keywordPath)
            .setSensitivity(0.6f)
            .build(context)
        porcupine = engine

        startRecorderAndLoop(engine, onDetected)
    }

    @SuppressLint("MissingPermission")
    private fun startRecorderAndLoop(engine: Porcupine, onDetected: () -> Unit) {
        val sampleRate = engine.sampleRate
        val frameLength = engine.frameLength
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuffer, frameLength * 2)

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
            val buffer = ShortArray(frameLength)
            while (isActive) {
                if (paused.get()) { delay(50); continue }
                val read = recorder.read(buffer, 0, frameLength)
                if (read != frameLength) continue
                try {
                    val keywordIndex = engine.process(buffer)
                    if (keywordIndex >= 0) {
                        Log.i(TAG, "Wake word detected.")
                        onDetected()
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Porcupine.process failed", t)
                }
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
        val engine = porcupine ?: return
        val cb = onDetectedCallback ?: return
        startRecorderAndLoop(engine, cb)
    }

    fun release() {
        runBlocking { loopJob?.cancelAndJoin() }
        loopJob = null
        audioRecord?.runCatching { stop(); release() }
        audioRecord = null
        porcupine?.delete()
        porcupine = null
        onDetectedCallback = null
    }

    private fun ensureKeywordFile(): String {
        val outDir = File(context.filesDir, "wakeword").apply { mkdirs() }
        val outFile = File(outDir, KEYWORD_ASSET_NAME.substringAfterLast('/'))
        if (!outFile.exists()) {
            context.assets.open(KEYWORD_ASSET_NAME).use { input ->
                FileOutputStream(outFile).use { input.copyTo(it) }
            }
        }
        return outFile.absolutePath
    }

    companion object {
        private const val TAG = "WakeWord"
        private const val KEYWORD_ASSET_NAME = "wakeword/yo_poto_android.ppn"
    }
}
