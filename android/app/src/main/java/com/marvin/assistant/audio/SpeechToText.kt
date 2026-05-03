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
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream

/**
 * One-shot speech-to-text via Vosk (offline, French).
 *
 * Setup (see README.md):
 *  Download https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip
 *  Unzip, then drop the contents into app/src/main/assets/vosk-fr/
 *  (so that app/src/main/assets/vosk-fr/conf/model.conf exists, etc.)
 */
class SpeechToText(private val context: Context) {

    private var model: Model? = null

    private fun ensureModel(): Model {
        model?.let { return it }
        val modelDir = File(context.filesDir, "vosk-fr")
        if (!modelDir.exists()) {
            modelDir.mkdirs()
            copyAssetTree("vosk-fr", modelDir)
        }
        return Model(modelDir.absolutePath).also { model = it }
    }

    @SuppressLint("MissingPermission")
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun listenOnce(
        sampleRate: Int = 16_000,
        maxDurationMs: Long = 6_000,
        silenceTimeoutMs: Long = 1_200
    ): String? = withContext(Dispatchers.IO) {
        val m = ensureModel()
        val recognizer = Recognizer(m, sampleRate.toFloat())
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
                if (finalized) {
                    val partial = JSONObject(recognizer.partialResult).optString("partial")
                    if (partial.isNotEmpty() && partial != lastPartial) {
                        lastSpeech = System.currentTimeMillis()
                        lastPartial = partial
                    }
                    break
                } else {
                    val partial = JSONObject(recognizer.partialResult).optString("partial")
                    if (partial.isNotEmpty() && partial != lastPartial) {
                        lastSpeech = System.currentTimeMillis()
                        lastPartial = partial
                    }
                }

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

    fun release() {
        model?.close()
        model = null
    }

    private fun copyAssetTree(assetPath: String, outDir: File) {
        val children = context.assets.list(assetPath) ?: emptyArray()
        if (children.isEmpty()) {
            // It's a file
            context.assets.open(assetPath).use { input ->
                FileOutputStream(outDir).use { input.copyTo(it) }
            }
            return
        }
        outDir.mkdirs()
        for (child in children) {
            val childAsset = "$assetPath/$child"
            val childOut = File(outDir, child)
            val sub = context.assets.list(childAsset) ?: emptyArray()
            if (sub.isEmpty()) {
                context.assets.open(childAsset).use { input ->
                    FileOutputStream(childOut).use { input.copyTo(it) }
                }
            } else {
                copyAssetTree(childAsset, childOut)
            }
        }
    }

    companion object {
        private const val TAG = "STT"
    }
}
