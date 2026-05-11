package com.marvin.assistant.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reconnaissance vocale via Whisper (sherpa-onnx, modèle multilingue).
 *
 * Setup :
 *  1. Télécharge un modèle Whisper ONNX (ex. whisper-tiny multilingual,
 *     ~80 Mo) depuis k2-fsa.github.io
 *  2. Push dans filesDir/whisper/ :
 *       - tiny-encoder.int8.onnx
 *       - tiny-decoder.int8.onnx
 *       - tiny-tokens.txt
 *  3. Active dans Réglages → "STT" → Whisper
 *
 * Avantages vs Vosk :
 *  - Multilingue (99 langues), pas juste FR
 *  - Meilleure qualité sur les phrases longues / vocabulaire technique
 *  - Modes "transcribe" et "translate" (traduit vers EN à la volée)
 *
 * Inconvénients :
 *  - Plus lent (offline, traite la phrase complète après silence)
 *  - Pas de partials → moins réactif que Vosk
 *  - Fichiers plus lourds
 */
class WhisperStt(private val context: Context) {

    private val modelDir = File(context.filesDir, "whisper")

    fun isReady(): Boolean {
        val enc = File(modelDir, ENCODER_NAME)
        val dec = File(modelDir, DECODER_NAME)
        val tok = File(modelDir, TOKENS_NAME)
        val ok = enc.exists() && dec.exists() && tok.exists()
        if (!ok) Log.i(TAG, "Whisper non configuré (encoder/decoder/tokens absent dans $modelDir)")
        return ok
    }

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun listenOnce(
        sampleRate: Int = 16_000,
        maxDurationMs: Long = 8_000,
        silenceTimeoutMs: Long = 1_500,
        language: String = "fr"
    ): String? = withContext(Dispatchers.IO) {
        if (!isReady()) return@withContext null
        // 1. Enregistrer audio (jusqu'a silence ou maxDuration)
        val pcm = recordPcm(sampleRate, maxDurationMs, silenceTimeoutMs)
            ?: return@withContext null
        if (pcm.isEmpty()) return@withContext null

        // 2. Convertir en float [-1, 1]
        val floats = FloatArray(pcm.size) { pcm[it] / 32768f }

        // 3. Recognize via sherpa-onnx Whisper
        return@withContext try {
            val recognizer = buildRecognizer(language)
            val stream = recognizer.createStream()
            stream.acceptWaveform(floats, sampleRate)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream).text.trim()
            stream.release()
            recognizer.release()
            Log.i(TAG, "Whisper result: $result")
            result.ifBlank { null }
        } catch (t: Throwable) {
            Log.e(TAG, "Whisper recognition failed", t)
            null
        }
    }

    private fun buildRecognizer(language: String): OfflineRecognizer {
        val whisper = OfflineWhisperModelConfig(
            encoder = File(modelDir, ENCODER_NAME).absolutePath,
            decoder = File(modelDir, DECODER_NAME).absolutePath,
            language = language,
            task = "transcribe"
        )
        val model = OfflineModelConfig(
            whisper = whisper,
            tokens = File(modelDir, TOKENS_NAME).absolutePath,
            numThreads = 2,
            provider = "cpu",
            modelType = "whisper"
        )
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = model
        )
        return OfflineRecognizer(config = config)
    }

    @SuppressLint("MissingPermission")
    private fun recordPcm(sampleRate: Int, maxDurationMs: Long, silenceTimeoutMs: Long): FloatArray? {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, minBuffer
        )
        val buf = ShortArray(minBuffer / 2)
        val out = mutableListOf<Short>()
        recorder.startRecording()
        val start = System.currentTimeMillis()
        var lastSpeech = start
        try {
            while (true) {
                val n = recorder.read(buf, 0, buf.size)
                if (n > 0) {
                    for (i in 0 until n) out.add(buf[i])
                    // Detection d'energie basique
                    var sumSq = 0.0
                    for (i in 0 until n) sumSq += buf[i] * buf[i]
                    val rms = kotlin.math.sqrt(sumSq / n)
                    if (rms > 500) lastSpeech = System.currentTimeMillis()
                }
                val now = System.currentTimeMillis()
                if (now - start > maxDurationMs) break
                if (out.size > sampleRate && now - lastSpeech > silenceTimeoutMs) break
            }
        } finally {
            recorder.stop(); recorder.release()
        }
        if (out.isEmpty()) return null
        return FloatArray(out.size) { out[it].toFloat() }
    }

    companion object {
        private const val TAG = "WhisperStt"
        const val ENCODER_NAME = "encoder.int8.onnx"
        const val DECODER_NAME = "decoder.int8.onnx"
        const val TOKENS_NAME = "tokens.txt"
    }
}
