package com.marvin.assistant.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Recognizer

/**
 * One-shot speech-to-text via Vosk (offline, French).
 *
 * Le modèle Vosk est partagé avec [WakeWordEngine] via [VoskModelHolder]
 * pour ne pas le charger deux fois.
 *
 * Setup (cf. README):
 *  Télécharge https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip
 *  Dézippe-le et pose son contenu dans app/src/main/assets/vosk-fr/
 *  (au final on doit avoir app/src/main/assets/vosk-fr/conf/model.conf, etc.)
 */
class SpeechToText(
    @Suppress("unused") private val context: Context,
    private val voskModel: VoskModelHolder
) {

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun listenOnce(
        sampleRate: Int = 16_000,
        maxDurationMs: Long = 6_000,
        silenceTimeoutMs: Long = 1_200
    ): String? = withContext(Dispatchers.IO) {
        val recognizer = Recognizer(voskModel.get(), sampleRate.toFloat())
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuffer, sampleRate / 5 * 2) // ~200 ms

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        // AEC + NS + AGC : améliore la captation pendant que Jarvis parle
        // ou dans un environnement bruité. Fail silently si indispo.
        try {
            if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                android.media.audiofx.AcousticEchoCanceler.create(recorder.audioSessionId)
                    ?.apply { enabled = true }
            }
            if (android.media.audiofx.NoiseSuppressor.isAvailable()) {
                android.media.audiofx.NoiseSuppressor.create(recorder.audioSessionId)
                    ?.apply { enabled = true }
            }
            if (android.media.audiofx.AutomaticGainControl.isAvailable()) {
                android.media.audiofx.AutomaticGainControl.create(recorder.audioSessionId)
                    ?.apply { enabled = true }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Audio effects setup failed", t)
        }

        val frame = ShortArray(bufferSize / 2)
        recorder.startRecording()

        val start = System.currentTimeMillis()
        var lastSpeech = start
        var lastPartial = ""

        try {
            while (true) {
                val read = recorder.read(frame, 0, frame.size)
                if (read <= 0) continue
                val finalized = recognizer.acceptWaveForm(frame, read)
                val partial = if (finalized) {
                    JSONObject(recognizer.result).optString("text")
                } else {
                    JSONObject(recognizer.partialResult).optString("partial")
                }
                if (partial.isNotEmpty() && partial != lastPartial) {
                    lastSpeech = System.currentTimeMillis()
                    lastPartial = partial
                }
                if (finalized) break

                val now = System.currentTimeMillis()
                if (now - start > maxDurationMs) break
                if (lastPartial.isNotEmpty() && now - lastSpeech > silenceTimeoutMs) break
            }
            val final = JSONObject(recognizer.finalResult).optString("text")
            Log.i(TAG, "STT result: $final")
            final.takeIf { it.isNotBlank() } ?: lastPartial.takeIf { it.isNotBlank() }
        } finally {
            recorder.stop()
            recorder.release()
            recognizer.close()
        }
    }

    companion object {
        private const val TAG = "STT"
    }
}
